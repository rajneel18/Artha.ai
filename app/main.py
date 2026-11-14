"""Artha - zero-manual-entry spending tracker.

Payment happens -> bank/UPI SMS or notification reaches /api/sms (or you share / paste / speak / snap it)
-> merchant + amount extracted -> categorized -> clarification asked only when unsure
-> ledger, daily budget and Savings Pool update automatically.
"""
import csv
import io
import json
import os
import re
from contextlib import asynccontextmanager
import datetime as dt
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from fastapi import Depends, FastAPI, File, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel

from . import ai, budget, db, insights, ledger, recurring
from .categorizer import merchant_key
from .parser import is_non_transaction, parse_message
from .security import require_token, require_webhook_token, security_headers

STATIC = Path(__file__).parent / "static"
MAX_UPLOAD = 10 * 1024 * 1024


def today() -> date:
    """'Today' in the user's timezone (default India), not the server's - hosts usually run on UTC."""
    return datetime.now(ZoneInfo(os.environ.get("ARTHA_TZ", "Asia/Kolkata"))).date()


@asynccontextmanager
async def lifespan(_app):
    db.init_db(db.DB_PATH)
    yield


app = FastAPI(title="Artha", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
app.middleware("http")(security_headers)
auth = [Depends(require_token)]


# ---------- pages ----------

@app.get("/")
@app.get("/share")  # Android share target: Share an SMS -> Artha lands here with ?text=...
def index():
    return FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-cache"})


@app.get("/manifest.webmanifest")
def manifest():
    return FileResponse(STATIC / "manifest.webmanifest", media_type="application/manifest+json")


@app.get("/sw.js")
def service_worker():
    return FileResponse(STATIC / "sw.js", media_type="text/javascript", headers={"Cache-Control": "no-cache"})


@app.get("/icons/{name}")
def icons(name: str):
    path = (STATIC / "icons" / name).resolve()
    if path.parent != (STATIC / "icons").resolve() or not path.is_file():
        raise HTTPException(404)
    return FileResponse(path, headers={"Cache-Control": "public, max-age=604800"})


# ---------- helpers ----------

def _ai_on(conn) -> bool:
    return ai.available() and bool(budget.get_settings(conn)["ai_enabled"])


def _split_messages(text: str) -> list[str]:
    text = text.strip()
    if re.search(r"\n\s*\n", text):
        return [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    return [l.strip() for l in text.splitlines() if l.strip()]


def _record_ai(conn, e, source: str, cats: list[str]) -> dict | None:
    try:
        d = min(date.fromisoformat(e.date), today())
    except ValueError:
        d = today()
    return ledger.record(conn, d=d, amount=abs(e.amount), kind=e.kind if e.kind in ("debit", "credit") else "debit",
                         merchant=e.merchant or "Unknown", note=e.merchant, source=source,
                         category=e.category if e.category in cats else None,
                         split_with=e.split_with, split_ways=e.split_ways or None, confident=e.confident, today=today())


def ingest_text(text: str, source_override: str | None = None, smart: bool = False) -> dict:
    added, skipped, ignored, duplicates, notes = [], [], 0, 0, []
    with db.connect() as conn:
        use_ai = _ai_on(conn)
        cats = budget.all_categories(conn)
        if smart and use_ai:
            try:
                for e in ai.parse_expenses(text, cats, today()):
                    r = _record_ai(conn, e, source_override or "voice", cats)
                    if r:
                        added.append(r)
                return {"added": added, "skipped": [], "ignored": 0, "duplicates": 0, "notes": []}
            except ai.AIUnavailable as e:
                notes.append(str(e))  # fall back to the local parser
        for msg in _split_messages(text):
            if is_non_transaction(msg):
                ignored += 1  # OTPs, failed payments, reminders: not money spent
                continue
            p = parse_message(msg, today=today())
            if not p:
                if use_ai:
                    try:
                        for e in ai.parse_expenses(msg, cats, today()):
                            r = _record_ai(conn, e, source_override or "text", cats)
                            if r:
                                added.append(r)
                        continue
                    except ai.AIUnavailable as e:
                        notes.append(str(e))
                skipped.append(msg)
                continue
            r = ledger.record(conn, d=p.date, amount=p.amount, kind=p.kind, merchant=p.merchant, note=p.note,
                              source=source_override or p.source, raw=msg if p.source == "sms" else None,
                              split_with=p.split_with, split_ways=p.split_ways, confident=p.confident, today=today())
            if r is None:
                duplicates += 1
            else:
                added.append(r)
    return {"added": added, "skipped": skipped, "ignored": ignored, "duplicates": duplicates, "notes": notes}


# ---------- capture ----------

class IngestIn(BaseModel):
    text: str
    smart: bool = False  # voice / free-form: let AI interpret it when enabled


@app.post("/api/ingest", dependencies=auth)
def ingest(body: IngestIn):
    """Paste one or many lines: '250 swiggy', 'petrol 1500 yesterday', bank SMS, '900 dinner split with Rahul'."""
    if len(body.text) > 50_000:
        raise HTTPException(413, "Too much text at once")
    return ingest_text(body.text, smart=body.smart)


@app.post("/api/sms", dependencies=[Depends(require_webhook_token)])
async def sms_webhook(request: Request):
    """Webhook for phone automation (MacroDroid, SMS forwarder apps, Tasker, iOS Shortcuts).
    Accepts JSON {"text"|"message"|"body"|"msg": "..."}, form data with the same keys, or a raw text body."""
    ctype = request.headers.get("content-type", "")
    body = await request.body()
    if len(body) > 20_000:
        raise HTTPException(413, "Message too large")
    text = ""
    if "json" in ctype:
        try:
            data = json.loads(body or b"null")
        except ValueError:
            raise HTTPException(400, "Invalid JSON")
        if isinstance(data, dict):
            text = next((str(data[k]) for k in ("text", "message", "body", "msg", "sms") if data.get(k)), "")
        elif isinstance(data, str):
            text = data
    elif "form" in ctype:
        form = await request.form()
        text = next((str(form[k]) for k in ("text", "message", "body", "msg", "sms") if form.get(k)), "")
    else:
        text = body.decode("utf-8", "ignore")
    if not text.strip():
        raise HTTPException(400, "No message text found")
    r = ingest_text(" ".join(text.split()), source_override="sms")
    # Keep the webhook response minimal: automation apps may log it.
    return {"added": len(r["added"]), "duplicates": r["duplicates"], "ignored": r["ignored"],
            "needs_review": sum(1 for t in r["added"] if t["needs_review"])}


@app.post("/api/receipt", dependencies=auth)
async def scan_receipt(file: UploadFile = File(...)):
    """Photo or PDF of a receipt/invoice -> transaction (uses AI)."""
    media = (file.content_type or "").lower()
    if media == "image/jpg":
        media = "image/jpeg"
    if media not in ("image/jpeg", "image/png", "image/webp", "image/gif", "application/pdf"):
        raise HTTPException(400, "Use a JPG, PNG, WEBP photo or a PDF")
    data = await file.read(MAX_UPLOAD + 1)
    if len(data) > MAX_UPLOAD:
        raise HTTPException(413, "File is larger than 10 MB")
    with db.connect() as conn:
        if not _ai_on(conn):
            raise HTTPException(400, "Turn on AI features in Settings to scan receipts")
        cats = budget.all_categories(conn)
        try:
            rc = ai.scan_receipt(data, media, cats, today())
        except ai.AIUnavailable as e:
            raise HTTPException(503, str(e))
        try:
            d = min(date.fromisoformat(rc.date), today()) if rc.date else today()
        except ValueError:
            d = today()
        note = rc.merchant + (": " + ", ".join(rc.items[:8]) if rc.items else "")
        tx = ledger.record(conn, d=d, amount=rc.total, merchant=rc.merchant or "Unknown", note=note, source="receipt",
                           category=rc.category if rc.category in cats else None, confident=rc.confident, today=today())
        return {"transaction": tx, "items": rc.items}


class TxIn(BaseModel):
    amount: float
    merchant: str = "Unknown"
    category: str | None = None
    kind: str = "debit"
    date: dt.date | None = None
    note: str = ""


@app.post("/api/transactions", dependencies=auth)
def add_tx(tx: TxIn):
    if tx.amount <= 0 or tx.kind not in ("debit", "credit"):
        raise HTTPException(400, "Amount must be positive and kind debit/credit")
    with db.connect() as conn:
        if tx.category and tx.category not in budget.all_categories(conn):
            raise HTTPException(400, "Unknown category")
        return ledger.record(conn, d=tx.date or today(), amount=tx.amount, kind=tx.kind, merchant=tx.merchant,
                             note=tx.note or tx.merchant, source="manual", category=tx.category, today=today())


# ---------- ledger ----------

@app.get("/api/transactions", dependencies=auth)
def list_tx(month: str | None = None, category: str | None = None, merchant: str | None = None, limit: int = 500):
    q, args = "SELECT * FROM transactions WHERE 1=1", []
    if month:
        q += " AND substr(date,1,7)=?"; args.append(month)
    if category:
        q += " AND category=?"; args.append(category)
    if merchant:
        q += " AND lower(merchant)=?"; args.append(merchant_key(merchant))
    q += " ORDER BY date DESC, id DESC LIMIT ?"; args.append(min(limit, 2000))
    with db.connect() as conn:
        rows = [dict(r) for r in conn.execute(q, args)]
        split_ids = {r[0] for r in conn.execute("SELECT DISTINCT tx_id FROM splits")}
        for r in rows:
            r["is_split"] = r["id"] in split_ids
        return rows


class TxPatch(BaseModel):
    amount: float | None = None
    merchant: str | None = None
    category: str | None = None
    kind: str | None = None
    date: dt.date | None = None
    note: str | None = None


@app.get("/api/transactions/{tx_id}", dependencies=auth)
def get_tx(tx_id: int):
    with db.connect() as conn:
        r = ledger.row(conn, tx_id)
        if not r:
            raise HTTPException(404, "Not found")
        return r


@app.patch("/api/transactions/{tx_id}", dependencies=auth)
def update_tx(tx_id: int, patch: TxPatch):
    fields = {k: (v.isoformat() if isinstance(v, dt.date) else v)
              for k, v in patch.model_dump(exclude_unset=True).items() if v is not None and k != "category"}
    if "kind" in fields and fields["kind"] not in ("debit", "credit"):
        raise HTTPException(400, "kind must be debit or credit")
    with db.connect() as conn:
        if not conn.execute("SELECT 1 FROM transactions WHERE id=?", (tx_id,)).fetchone():
            raise HTTPException(404, "Not found")
        if patch.category and patch.category not in budget.all_categories(conn):
            raise HTTPException(400, "Unknown category")
        if fields:
            sets = ", ".join(f"{k}=?" for k in fields)  # keys come from the TxPatch model, not user input
            conn.execute(f"UPDATE transactions SET {sets} WHERE id=?", (*fields.values(), tx_id))
        if patch.category:
            ledger.set_category(conn, tx_id, patch.category, learn=True)  # teaches the app this merchant
            conn.execute("UPDATE transactions SET needs_review=0, review_reason=NULL WHERE id=? "
                         "AND review_reason IN ('uncategorized','low_confidence')", (tx_id,))
        return ledger.row(conn, tx_id)


@app.delete("/api/transactions/{tx_id}", dependencies=auth)
def delete_tx(tx_id: int):
    with db.connect() as conn:
        conn.execute("DELETE FROM transactions WHERE id=?", (tx_id,))
    return {"ok": True}


# ---------- clarification inbox ----------

@app.get("/api/review", dependencies=auth)
def review_list():
    with db.connect() as conn:
        ids = [r[0] for r in conn.execute("SELECT id FROM transactions WHERE needs_review=1 ORDER BY date DESC, id DESC")]
        return [ledger.row(conn, i) for i in ids]


class ReviewIn(BaseModel):
    action: str = "confirm"  # confirm | delete | reactivate_subscription
    category: str | None = None
    merchant: str | None = None


@app.post("/api/review/{tx_id}", dependencies=auth)
def review_resolve(tx_id: int, body: ReviewIn):
    if body.action not in ("confirm", "delete", "reactivate_subscription"):
        raise HTTPException(400, "Unknown action")
    with db.connect() as conn:
        if body.category and body.category not in budget.all_categories(conn):
            raise HTTPException(400, "Unknown category")
        r = ledger.resolve_review(conn, tx_id, body.action, body.category, body.merchant)
        if r is None:
            raise HTTPException(404, "Not found")
        return r


# ---------- splitting ----------

class SplitIn(BaseModel):
    people: list[str] = []
    ways: int | None = None
    amounts: dict[str, float] | None = None


@app.post("/api/transactions/{tx_id}/split", dependencies=auth)
def split_tx(tx_id: int, body: SplitIn):
    if not (body.people or body.ways or body.amounts):
        raise HTTPException(400, "Say who you split with or how many ways")
    if body.ways is not None and not 2 <= body.ways <= 50:
        raise HTTPException(400, "Split between 2 and 50 people")
    with db.connect() as conn:
        try:
            return ledger.apply_split(conn, tx_id, body.people[:50], body.ways, body.amounts)
        except ValueError as e:
            raise HTTPException(400, str(e))


@app.delete("/api/transactions/{tx_id}/split", dependencies=auth)
def unsplit_tx(tx_id: int):
    with db.connect() as conn:
        ledger.remove_split(conn, tx_id)
        return ledger.row(conn, tx_id)


@app.get("/api/splits", dependencies=auth)
def split_balances():
    with db.connect() as conn:
        return ledger.balances(conn)


class SettleIn(BaseModel):
    person: str
    amount: float | None = None


@app.post("/api/splits/settle", dependencies=auth)
def settle(body: SettleIn):
    with db.connect() as conn:
        return {"settled": ledger.settle(conn, body.person, body.amount, today()), "balances": ledger.balances(conn)}


# ---------- budget & settings ----------

@app.get("/api/categories", dependencies=auth)
def categories():
    with db.connect() as conn:
        return budget.all_categories(conn)


@app.get("/api/settings", dependencies=auth)
def get_settings():
    with db.connect() as conn:
        s = budget.get_settings(conn)
        s["ai_available"] = ai.available()
        s["webhook_token_set"] = bool(os.environ.get("ARTHA_WEBHOOK_TOKEN"))
        return s


@app.put("/api/settings", dependencies=auth)
def put_settings(values: dict):
    clean = {}
    try:
        if "daily_allowance" in values:
            v = float(values["daily_allowance"])
            if not 0 <= v <= 1_000_000:
                raise HTTPException(400, "Daily allowance must be between 0 and 10,00,000")
            clean["daily_allowance"] = round(v, 2)
        if "excluded_categories" in values:
            clean["excluded_categories"] = [str(c) for c in values["excluded_categories"]][:50]
        if "custom_categories" in values:
            clean["custom_categories"] = sorted({str(c).strip()[:30] for c in values["custom_categories"] if str(c).strip()})[:30]
        if "ai_enabled" in values:
            clean["ai_enabled"] = bool(values["ai_enabled"])
        if "savings_goal_pct" in values:
            clean["savings_goal_pct"] = max(0, min(90, int(values["savings_goal_pct"])))
        if "budget_start" in values:
            clean["budget_start"] = date.fromisoformat(values["budget_start"]).isoformat()
    except (TypeError, ValueError):
        raise HTTPException(400, "Invalid setting value")
    with db.connect() as conn:
        budget.ensure_started(conn, today())
        s = budget.set_settings(conn, clean, today())
        s["ai_available"] = ai.available()
        return s


@app.get("/api/budget", dependencies=auth)
def budget_status():
    with db.connect() as conn:
        return budget.status(conn, today())


@app.get("/api/summary", dependencies=auth)
def summary(month: str | None = None):
    month = month or today().strftime("%Y-%m")
    if not re.fullmatch(r"\d{4}-\d{2}", month):
        raise HTTPException(400, "month must be YYYY-MM")
    with db.connect() as conn:
        st = insights.month_stats(conn, month)
        by_cat = [{"category": c, "total": t} for c, t in sorted(st["by_category"].items(), key=lambda kv: -kv[1])]
        spent_today = conn.execute("SELECT COALESCE(SUM(COALESCE(my_share, amount)),0) FROM transactions "
                                   "WHERE kind='debit' AND date=?", (today().isoformat(),)).fetchone()[0]
        return {"month": month, "spent": st["spent"], "received": st["received"], "today": round(spent_today, 2),
                "by_category": by_cat, "budget": budget.status(conn, today()),
                "review_count": conn.execute("SELECT COUNT(*) FROM transactions WHERE needs_review=1").fetchone()[0]}


# ---------- custom rules ----------

class RuleIn(BaseModel):
    pattern: str
    match_type: str = "contains"
    category: str
    min_amount: float | None = None
    max_amount: float | None = None
    priority: int = 0
    apply_to_existing: bool = True


@app.get("/api/rules", dependencies=auth)
def list_rules():
    with db.connect() as conn:
        return {"rules": ledger.custom_rules(conn),
                "learned": [dict(r) for r in conn.execute("SELECT * FROM merchant_rules ORDER BY merchant")]}


@app.post("/api/rules", dependencies=auth)
def add_rule(rule: RuleIn):
    from .categorizer import rule_matches
    if rule.match_type not in ("contains", "regex", "merchant") or not rule.pattern.strip():
        raise HTTPException(400, "match_type must be contains, regex or merchant")
    if rule.match_type == "regex":
        try:
            re.compile(rule.pattern)
        except re.error as e:
            raise HTTPException(400, f"Invalid regex: {e}")
    with db.connect() as conn:
        if rule.category not in budget.all_categories(conn):
            raise HTTPException(400, "Unknown category")
        cur = conn.execute("INSERT INTO rules(pattern, match_type, category, min_amount, max_amount, priority) "
                           "VALUES (?,?,?,?,?,?)", (rule.pattern.strip()[:200], rule.match_type, rule.category,
                                                    rule.min_amount, rule.max_amount, rule.priority))
        changed = 0
        if rule.apply_to_existing:
            r = dict(conn.execute("SELECT * FROM rules WHERE id=?", (cur.lastrowid,)).fetchone())
            for t in conn.execute("SELECT id, merchant, note, amount FROM transactions").fetchall():
                if rule_matches(r, t["note"] or "", t["merchant"], t["amount"]):
                    conn.execute("UPDATE transactions SET category=?, "
                                 "needs_review=CASE WHEN review_reason='uncategorized' THEN 0 ELSE needs_review END, "
                                 "review_reason=CASE WHEN review_reason='uncategorized' THEN NULL ELSE review_reason END "
                                 "WHERE id=?", (rule.category, t["id"]))
                    changed += 1
        return {"id": cur.lastrowid, "updated_transactions": changed}


@app.delete("/api/rules/{rule_id}", dependencies=auth)
def delete_rule(rule_id: int):
    with db.connect() as conn:
        conn.execute("DELETE FROM rules WHERE id=?", (rule_id,))
    return {"ok": True}


@app.delete("/api/learned/{merchant}", dependencies=auth)
def forget_merchant(merchant: str):
    with db.connect() as conn:
        conn.execute("DELETE FROM merchant_rules WHERE merchant=?", (merchant_key(merchant),))
    return {"ok": True}


# ---------- subscriptions & recurring ----------

@app.get("/api/subscriptions", dependencies=auth)
def subscriptions():
    with db.connect() as conn:
        return recurring.list_subscriptions(conn, today())


@app.post("/api/subscriptions/scan", dependencies=auth)
def scan_subscriptions():
    with db.connect() as conn:
        recurring.scan_all(conn)
        return recurring.list_subscriptions(conn, today())


class SubIn(BaseModel):
    name: str | None = None
    amount: float | None = None
    period: str | None = None
    status: str | None = None
    next_date: dt.date | None = None


@app.post("/api/subscriptions", dependencies=auth)
def add_subscription(s: SubIn):
    if not s.name or not s.amount or s.amount <= 0:
        raise HTTPException(400, "Name and amount are required")
    period = s.period if s.period in recurring.PERIODS else "monthly"
    with db.connect() as conn:
        conn.execute("INSERT INTO subscriptions(merchant_key, name, amount, period, next_date, status, source) "
                     "VALUES (?,?,?,?,?, 'active', 'manual') ON CONFLICT(merchant_key) DO UPDATE SET "
                     "amount=excluded.amount, period=excluded.period, status='active'",
                     (merchant_key(s.name), s.name.strip()[:60], s.amount, period, (s.next_date or today()).isoformat()))
        return recurring.list_subscriptions(conn, today())


@app.patch("/api/subscriptions/{sub_id}", dependencies=auth)
def update_subscription(sub_id: int, s: SubIn):
    if s.status and s.status not in ("active", "suggested", "cancelled", "ignored"):
        raise HTTPException(400, "Unknown status")
    if s.period and s.period not in recurring.PERIODS:
        raise HTTPException(400, "Unknown period")
    fields = {k: (v.isoformat() if isinstance(v, dt.date) else v) for k, v in s.model_dump(exclude_unset=True).items()
              if v is not None}
    with db.connect() as conn:
        if fields:
            sets = ", ".join(f"{k}=?" for k in fields)  # keys come from the SubIn model
            conn.execute(f"UPDATE subscriptions SET {sets} WHERE id=?", (*fields.values(), sub_id))
        return recurring.list_subscriptions(conn, today())


# ---------- merchants, search, insights ----------

@app.get("/api/merchants", dependencies=auth)
def merchants(q: str = ""):
    with db.connect() as conn:
        return [dict(merchant=r[0], total=round(r[1], 2), count=r[2], last=r[3]) for r in conn.execute(
            "SELECT merchant, SUM(COALESCE(my_share, amount)) t, COUNT(*), MAX(date) FROM transactions "
            "WHERE kind='debit' AND lower(merchant) LIKE ? GROUP BY lower(merchant) ORDER BY t DESC LIMIT 50",
            (f"%{q.lower()}%",))]


@app.get("/api/merchants/history", dependencies=auth)
def merchant_history(name: str):
    with db.connect() as conn:
        return insights.merchant_history(conn, name, today())


class AskIn(BaseModel):
    question: str


@app.post("/api/ask", dependencies=auth)
def ask(body: AskIn):
    """Natural-language questions and conversational search: 'how much on food last month?',
    'show uber rides over 300', 'what did I spend at Blinkit in October'."""
    q = body.question.strip()[:500]
    if not q:
        raise HTTPException(400, "Ask a question")
    with db.connect() as conn:
        cats = budget.all_categories(conn)
        used_ai, plan = False, None
        if _ai_on(conn):
            try:
                plan, used_ai = ai.plan_query(q, cats, today()), True
            except ai.AIUnavailable:
                plan = None
        plan = plan or insights.local_plan(q, cats, today())
        result = insights.run_plan(conn, plan)
        return {"answer": insights.describe(plan, result), "plan": plan, "used_ai": used_ai, **result}


@app.get("/api/insights", dependencies=auth)
def get_insights(month: str | None = None):
    month = month or today().strftime("%Y-%m")
    with db.connect() as conn:
        return {"month": month, "insights": insights.rule_insights(conn, month, today()),
                "stats": insights.month_stats(conn, month),
                "recommendations": insights.recommendations(conn, month, today()),
                "ai_ready": _ai_on(conn)}


@app.post("/api/insights/ai", dependencies=auth)
def ai_insights(month: str | None = None):
    month = month or today().strftime("%Y-%m")
    with db.connect() as conn:
        if not _ai_on(conn):
            raise HTTPException(400, "Turn on AI features in Settings first")
        payload = {  # aggregated numbers only - no individual transactions or notes
            "month": insights.month_stats(conn, month),
            "previous_month": insights.month_stats(conn, insights.prev_month(month)),
            "daily_budget": budget.status(conn, today()),
            "subscriptions": recurring.list_subscriptions(conn, today())["monthly_total"],
            "suggested_budgets": insights.recommendations(conn, month, today()),
        }
        try:
            out = ai.monthly_insights(payload)
        except ai.AIUnavailable as e:
            raise HTTPException(503, str(e))
        return out.model_dump()


@app.get("/api/recommendations", dependencies=auth)
def get_recommendations(month: str | None = None):
    with db.connect() as conn:
        return insights.recommendations(conn, month or today().strftime("%Y-%m"), today())


# ---------- import / export / deletion ----------

@app.post("/api/import/csv", dependencies=auth)
async def import_csv(file: UploadFile = File(...)):
    """Import a bank statement CSV. Detects columns named like date / description / amount / debit / credit."""
    raw = await file.read(MAX_UPLOAD + 1)
    if len(raw) > MAX_UPLOAD:
        raise HTTPException(413, "File is larger than 10 MB")
    reader = csv.DictReader(io.StringIO(raw.decode("utf-8-sig", "ignore")))
    cols = {c.lower().strip(): c for c in (reader.fieldnames or [])}

    def pick(*names):
        for n in names:
            for low, orig in cols.items():
                if n in low:
                    return orig
        return None

    c_date = pick("date")
    c_desc = pick("narration", "description", "particular", "remark", "details", "merchant")
    c_amt, c_dr, c_cr = pick("amount"), pick("debit", "withdrawal"), pick("credit", "deposit")
    if not c_date or not (c_amt or c_dr):
        raise HTTPException(400, f"Could not find date/amount columns in {reader.fieldnames}")

    def num(v):
        v = (v or "").replace(",", "").strip()
        try:
            return float(v) if v else 0.0
        except ValueError:
            return 0.0

    def when(v):
        v = (v or "").strip()
        for fmt in ("%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y", "%d/%m/%y", "%d-%m-%y", "%d-%b-%Y", "%d %b %Y"):
            try:
                return datetime.strptime(v, fmt).date()
            except ValueError:
                pass
        return None

    added = skipped = 0
    with db.connect() as conn:
        for r in reader:
            d = when(r.get(c_date))
            desc = (r.get(c_desc) or "").strip() if c_desc else ""
            if c_dr or c_cr:
                dr, cr = num(r.get(c_dr)) if c_dr else 0, num(r.get(c_cr)) if c_cr else 0
                amount, kind = (dr, "debit") if dr else (cr, "credit")
            else:
                amount, kind = abs(num(r.get(c_amt))), "debit"
            if not d or not amount:
                skipped += 1
                continue
            merchant = _merchant_from_narration(desc)
            if ledger.record(conn, d=d, amount=amount, kind=kind, merchant=merchant, note=desc, source="csv",
                             raw=f"csv|{d}|{amount}|{desc}", today=today()):
                added += 1
            else:
                skipped += 1
    return {"added": added, "skipped": skipped}


def _merchant_from_narration(desc: str) -> str:
    """'UPI-BLINKIT-blinkit@hdfc-123' -> 'Blinkit'."""
    parts = [p for p in re.split(r"[-/*]", desc) if p.strip() and not re.search(r"\d{3,}|@", p)
             and p.strip().upper() not in {"UPI", "POS", "NEFT", "IMPS", "ACH", "ECOM", "PUR", "TXN", "DR", "CR"}]
    return parts[0].strip().title()[:40] if parts else (desc[:40] or "Unknown")


@app.get("/api/export.csv", dependencies=auth)
def export_csv():
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["date", "amount", "my_share", "kind", "merchant", "category", "note", "source"])
    with db.connect() as conn:
        for r in conn.execute("SELECT * FROM transactions ORDER BY date"):
            w.writerow([r["date"], r["amount"], r["my_share"], r["kind"], r["merchant"], r["category"], r["note"], r["source"]])
    return Response(buf.getvalue(), media_type="text/csv",
                    headers={"Content-Disposition": "attachment; filename=artha-transactions.csv"})


BACKUP_TABLES = ("transactions", "splits", "subscriptions", "rules", "merchant_rules", "settings", "allowance_history")


@app.get("/api/export.json", dependencies=auth)
def export_json():
    """Full backup: everything the app stores about you."""
    with db.connect() as conn:
        dump = {t: [dict(r) for r in conn.execute(f"SELECT * FROM {t}")] for t in BACKUP_TABLES}
    return Response(json.dumps(dump, indent=1, default=str), media_type="application/json",
                    headers={"Content-Disposition": "attachment; filename=artha-backup.json"})


class DeleteIn(BaseModel):
    scope: str  # everything | transactions | range | learned
    confirm: str
    date_from: dt.date | None = None
    date_to: dt.date | None = None


@app.post("/api/data/delete", dependencies=auth)
def delete_data(body: DeleteIn):
    """User-controlled deletion. Requires typing DELETE. Rows are overwritten (secure_delete) and the file compacted."""
    if body.confirm != "DELETE":
        raise HTTPException(400, "Type DELETE to confirm")
    with db.connect() as conn:
        if body.scope == "everything":
            for t in BACKUP_TABLES:
                conn.execute(f"DELETE FROM {t}")
        elif body.scope == "transactions":
            for t in ("splits", "transactions", "subscriptions"):
                conn.execute(f"DELETE FROM {t}")
        elif body.scope == "range":
            if not body.date_from or not body.date_to:
                raise HTTPException(400, "Pick a start and end date")
            conn.execute("DELETE FROM transactions WHERE date BETWEEN ? AND ?",
                         (body.date_from.isoformat(), body.date_to.isoformat()))
        elif body.scope == "learned":
            conn.execute("DELETE FROM merchant_rules")
            conn.execute("DELETE FROM rules")
        else:
            raise HTTPException(400, "Unknown scope")
        conn.commit()
        conn.execute("VACUUM")
        left = conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
    return {"ok": True, "remaining_transactions": left}
