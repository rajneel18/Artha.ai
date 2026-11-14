"""Recurring payment detection and subscription management."""
from datetime import date, timedelta
from statistics import median

from .categorizer import merchant_key

PERIODS = {"weekly": (6, 8, 7), "monthly": (26, 35, 30), "quarterly": (85, 97, 91), "yearly": (350, 380, 365)}
# Known subscription services: a single charge is enough to suggest them.
KNOWN_SUBSCRIPTIONS = {
    "netflix", "spotify", "prime video", "amazon prime", "hotstar", "disney", "youtube premium", "youtube",
    "sonyliv", "jiocinema", "zee5", "apple", "icloud", "google one", "google storage", "chatgpt", "openai",
    "claude", "anthropic", "microsoft 365", "office 365", "adobe", "canva", "notion", "linkedin premium",
    "cult.fit", "cultfit", "audible", "kindle unlimited", "swiggy one", "zomato gold", "tata play", "airtel xstream",
}


def _period_for(gaps: list[int]) -> str | None:
    if not gaps:
        return None
    g = median(gaps)
    for name, (lo, hi, _) in PERIODS.items():
        if lo <= g <= hi and all(lo * 0.75 <= x <= hi * 1.25 for x in gaps):
            return name
    return None


def _next(last: date, period: str) -> date:
    if period == "monthly":
        m = last.month % 12 + 1
        y = last.year + (last.month == 12)
        for day in (last.day, 30, 29, 28):
            try:
                return date(y, m, day)
            except ValueError:
                continue
    return last + timedelta(days=PERIODS[period][2])


def is_known_subscription(merchant: str) -> bool:
    k = merchant_key(merchant)
    return any(s in k for s in KNOWN_SUBSCRIPTIONS)


def detect_for_merchant(conn, merchant: str) -> dict | None:
    """Look at a merchant's debits; if they repeat on a regular schedule with similar amounts, record it."""
    key = merchant_key(merchant)
    if not key or key == "unknown":
        return None
    rows = conn.execute(
        "SELECT id, date, amount, category FROM transactions WHERE kind='debit' AND lower(merchant)=? "
        "ORDER BY date", (key,)).fetchall()
    if not rows:
        return None
    dates = [date.fromisoformat(r["date"]) for r in rows]
    amounts = [r["amount"] for r in rows]
    gaps = [(b - a).days for a, b in zip(dates, dates[1:]) if (b - a).days > 0]
    period = _period_for(gaps) if len(rows) >= (3 if _period_for(gaps) == "weekly" else 2) else None
    typical = median(amounts[-4:])
    similar = all(abs(a - typical) <= max(0.15 * typical, 5) for a in amounts[-4:])
    known = is_known_subscription(merchant)
    if not ((period and similar) or known):
        return None

    existing = conn.execute("SELECT * FROM subscriptions WHERE merchant_key=?", (key,)).fetchone()
    period = period or (existing["period"] if existing else "monthly")
    status = "active" if (period and similar and len(rows) >= 2) else "suggested"
    last = dates[-1]
    nxt = _next(last, period)
    if existing:
        if existing["status"] in ("ignored",):
            return None
        new_status = existing["status"] if existing["status"] in ("active", "cancelled") else status
        conn.execute("UPDATE subscriptions SET amount=?, period=?, last_date=?, next_date=?, status=?, category=? "
                     "WHERE id=?", (rows[-1]["amount"], period, last.isoformat(), nxt.isoformat(), new_status,
                                    rows[-1]["category"], existing["id"]))
    else:
        conn.execute("INSERT INTO subscriptions(merchant_key, name, amount, period, category, last_date, next_date, "
                     "status, source) VALUES (?,?,?,?,?,?,?,?, 'detected')",
                     (key, merchant, rows[-1]["amount"], period, rows[-1]["category"], last.isoformat(),
                      nxt.isoformat(), status))
    if status == "active" or (existing and existing["status"] == "active"):
        conn.execute("UPDATE transactions SET is_recurring=1 WHERE kind='debit' AND lower(merchant)=?", (key,))
    return dict(conn.execute("SELECT * FROM subscriptions WHERE merchant_key=?", (key,)).fetchone())


def scan_all(conn) -> int:
    merchants = [r[0] for r in conn.execute("SELECT DISTINCT merchant FROM transactions WHERE kind='debit'")]
    return sum(1 for m in merchants if detect_for_merchant(conn, m))


FIXED_COSTS = {"Rent", "Bills & Utilities", "Investments", "Transfers", "Education"}
MONTHLY_FACTOR = {"weekly": 52 / 12, "monthly": 1, "quarterly": 1 / 3, "yearly": 1 / 12}


def list_subscriptions(conn, today: date) -> dict:
    subs = [dict(r) for r in conn.execute(
        "SELECT * FROM subscriptions WHERE status != 'ignored' ORDER BY status='cancelled', next_date")]
    for s in subs:
        s["monthly_cost"] = round(s["amount"] * MONTHLY_FACTOR.get(s["period"], 1), 2)
        s["days_until"] = (date.fromisoformat(s["next_date"]) - today).days if s["next_date"] else None
        s["is_subscription"] = s.get("category") not in FIXED_COSTS
    active = [s for s in subs if s["status"] == "active"]
    real = [s for s in active if s["is_subscription"]]  # rent, bills, SIPs recur but aren't subscriptions
    return {
        "subscriptions": subs,
        "monthly_total": round(sum(s["monthly_cost"] for s in real), 2),
        "yearly_total": round(sum(s["monthly_cost"] for s in real) * 12, 2),
        "fixed_monthly_total": round(sum(s["monthly_cost"] for s in active if not s["is_subscription"]), 2),
        "upcoming": [s for s in active if s["days_until"] is not None and 0 <= s["days_until"] <= 7],
    }
