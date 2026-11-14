"""The ledger pipeline every transaction goes through, whatever its source:

detect -> clean & redact -> categorize -> check duplicates / subscriptions -> apply splits
-> flag for clarification only when we're unsure -> store.
"""
import hashlib
import re
import sqlite3
from datetime import date, timedelta

from . import budget, recurring
from .categorizer import categorize, merchant_key
from .security import redact

REVIEW_QUESTIONS = {
    "duplicate": "Looks like the same payment twice. Were you charged twice?",
    "cancelled_subscription": "You marked this subscription as cancelled, but it charged again.",
    "unknown_merchant": "Couldn't tell who this payment went to. Who was it?",
    "uncategorized": "What was this for?",
    "low_confidence": "Please double-check the amount and merchant.",
}


def learned_rules(conn) -> dict[str, str]:
    return {r["merchant"]: r["category"] for r in conn.execute("SELECT merchant, category FROM merchant_rules")}


def custom_rules(conn) -> list[dict]:
    return [dict(r) for r in conn.execute("SELECT * FROM rules ORDER BY priority DESC, id")]


def row(conn, tx_id: int) -> dict | None:
    r = conn.execute("SELECT * FROM transactions WHERE id=?", (tx_id,)).fetchone()
    if not r:
        return None
    d = dict(r)
    d["splits"] = [dict(s) for s in conn.execute("SELECT * FROM splits WHERE tx_id=? ORDER BY id", (tx_id,))]
    d["question"] = REVIEW_QUESTIONS.get(d["review_reason"]) if d["needs_review"] else None
    return d


def _find_duplicate(conn, d: date, amount: float, merchant: str) -> int | None:
    lo, hi = (d - timedelta(days=1)).isoformat(), (d + timedelta(days=1)).isoformat()
    key = merchant_key(merchant)
    r = conn.execute(
        "SELECT id, merchant FROM transactions WHERE kind='debit' AND amount=? AND date BETWEEN ? AND ? "
        "ORDER BY id DESC", (amount, lo, hi)).fetchall()
    for x in r:
        other = merchant_key(x["merchant"])
        if key and other and (key == other or key in other or other in key) and key != "unknown":
            return x["id"]
    return None


def record(conn, *, d: date, amount: float, kind: str = "debit", merchant: str = "Unknown", note: str = "",
           source: str = "manual", category: str | None = None, raw: str | None = None,
           split_with: list[str] | None = None, split_ways: int | None = None, confident: bool = True,
           today: date) -> dict | None:
    """Store one transaction. Returns None if this exact message was already recorded."""
    note = redact(note or merchant)
    amount = round(float(amount), 2)
    merchant = (merchant or "Unknown").strip()[:60] or "Unknown"
    if not category:
        category = categorize(note, merchant, learned_rules(conn), custom_rules(conn), amount)

    reason, dup = None, None
    if kind == "debit":
        dup = _find_duplicate(conn, d, amount, merchant) if source != "manual" or raw else None
        sub = conn.execute("SELECT status FROM subscriptions WHERE merchant_key=?", (merchant_key(merchant),)).fetchone()
        if dup:
            reason = "duplicate"
        elif sub and sub["status"] == "cancelled":
            reason = "cancelled_subscription"
        elif merchant == "Unknown":
            reason = "unknown_merchant"
        elif category == "Other":
            reason = "uncategorized"
        elif not confident:
            reason = "low_confidence"

    raw_hash = hashlib.sha256(raw.strip().encode()).hexdigest() if raw else None
    try:
        cur = conn.execute(
            "INSERT INTO transactions(date, amount, kind, merchant, category, note, source, raw_hash, needs_review, "
            "review_reason, duplicate_of) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (d.isoformat(), amount, kind, merchant, category, note, source, raw_hash, int(bool(reason)), reason, dup))
    except sqlite3.IntegrityError:
        return None  # identical message already recorded (e.g. forwarded twice)
    tx_id = cur.lastrowid

    if split_with or split_ways:
        apply_split(conn, tx_id, split_with or [], split_ways)
    if kind == "debit":
        recurring.detect_for_merchant(conn, merchant)
    else:
        _auto_settle_from_credit(conn, merchant, amount, today)
    budget.ensure_started(conn, today)
    return row(conn, tx_id)


# ---------- splitting ----------

def apply_split(conn, tx_id: int, people: list[str], ways: int | None = None,
                amounts: dict[str, float] | None = None) -> dict:
    tx = conn.execute("SELECT amount FROM transactions WHERE id=?", (tx_id,)).fetchone()
    if not tx:
        raise ValueError("Transaction not found")
    total = tx["amount"]
    conn.execute("DELETE FROM splits WHERE tx_id=? AND settled=0", (tx_id,))
    if amounts:
        others = {p.strip().title(): round(float(a), 2) for p, a in amounts.items() if p.strip() and float(a) > 0}
    else:
        people = [p.strip().title() for p in people if p.strip()]
        n = max(ways or 0, len(people) + 1)
        while len(people) < n - 1:
            people.append(f"Friend {len(people) + 1}")
        share = round(total / n, 2)
        others = {p: share for p in people}
    if sum(others.values()) > total + 0.01:
        raise ValueError("Split amounts are more than the payment")
    for p, a in others.items():
        conn.execute("INSERT INTO splits(tx_id, person, amount) VALUES (?,?,?)", (tx_id, p, a))
    settled = conn.execute("SELECT COALESCE(SUM(amount),0) FROM splits WHERE tx_id=? AND settled=1", (tx_id,)).fetchone()[0]
    my_share = round(total - sum(others.values()) - settled, 2) if others or settled else None
    conn.execute("UPDATE transactions SET my_share=? WHERE id=?", (my_share, tx_id))
    return row(conn, tx_id)


def remove_split(conn, tx_id: int) -> None:
    conn.execute("DELETE FROM splits WHERE tx_id=?", (tx_id,))
    conn.execute("UPDATE transactions SET my_share=NULL WHERE id=?", (tx_id,))


def balances(conn) -> list[dict]:
    return [dict(r) for r in conn.execute(
        "SELECT person, ROUND(SUM(amount),2) AS owed, COUNT(*) AS items FROM splits WHERE settled=0 "
        "GROUP BY lower(person) ORDER BY owed DESC")]


def settle(conn, person: str, amount: float | None, today: date) -> float:
    """Mark what `person` owes as paid back (oldest first). Returns the amount settled."""
    left = float("inf") if amount is None else amount
    done = 0.0
    for s in conn.execute("SELECT s.id, s.amount FROM splits s JOIN transactions t ON t.id=s.tx_id "
                          "WHERE s.settled=0 AND lower(s.person)=lower(?) ORDER BY t.date, s.id", (person,)).fetchall():
        if s["amount"] > left + 0.01:
            break
        conn.execute("UPDATE splits SET settled=1, settled_at=? WHERE id=?", (today.isoformat(), s["id"]))
        left -= s["amount"]
        done += s["amount"]
    return round(done, 2)


def _auto_settle_from_credit(conn, merchant: str, amount: float, today: date) -> None:
    """Money received from someone who owes you -> settle their share automatically."""
    m = merchant_key(merchant)
    for b in balances(conn):
        name = b["person"].lower()
        if not name.startswith("friend ") and re.search(rf"\b{re.escape(name)}\b", m):
            settle(conn, b["person"], amount, today)
            return


# ---------- review (clarification inbox) ----------

def resolve_review(conn, tx_id: int, action: str, category: str | None = None, merchant: str | None = None) -> dict | None:
    tx = conn.execute("SELECT * FROM transactions WHERE id=?", (tx_id,)).fetchone()
    if not tx:
        return None
    if action == "delete":  # e.g. confirmed duplicate
        conn.execute("DELETE FROM transactions WHERE id=?", (tx_id,))
        return {"deleted": tx_id}
    if merchant:
        conn.execute("UPDATE transactions SET merchant=? WHERE id=?", (merchant.strip()[:60], tx_id))
    if category:
        set_category(conn, tx_id, category, learn=True)
    conn.execute("UPDATE transactions SET needs_review=0, review_reason=NULL WHERE id=?", (tx_id,))
    if action == "reactivate_subscription":
        conn.execute("UPDATE subscriptions SET status='active' WHERE merchant_key=?", (merchant_key(tx["merchant"]),))
    return row(conn, tx_id)


def set_category(conn, tx_id: int, category: str, learn: bool = True) -> None:
    tx = conn.execute("SELECT merchant FROM transactions WHERE id=?", (tx_id,)).fetchone()
    conn.execute("UPDATE transactions SET category=? WHERE id=?", (category, tx_id))
    key = merchant_key(tx["merchant"]) if tx else ""
    if learn and key and key != "unknown":
        conn.execute("INSERT INTO merchant_rules(merchant, category) VALUES(?,?) "
                     "ON CONFLICT(merchant) DO UPDATE SET category=excluded.category", (key, category))
        # Apply what we just learned to other open questions about the same merchant.
        conn.execute("UPDATE transactions SET category=?, needs_review=0, review_reason=NULL "
                     "WHERE lower(merchant)=? AND needs_review=1 AND review_reason='uncategorized'", (category, key))
