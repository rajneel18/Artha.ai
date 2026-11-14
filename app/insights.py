"""Monthly stats, rule-based insights, budget recommendations, merchant history and expense search."""
import re
from datetime import date, timedelta
from statistics import median

from . import budget, recurring
from .categorizer import merchant_key

FIXED = {"Rent", "Bills & Utilities", "Investments", "Transfers", "Education"}


def _month_bounds(month: str) -> tuple[str, str]:
    y, m = map(int, month.split("-"))
    start = date(y, m, 1)
    end = date(y + (m == 12), m % 12 + 1, 1) - timedelta(days=1)
    return start.isoformat(), end.isoformat()


def prev_month(month: str, n: int = 1) -> str:
    y, m = map(int, month.split("-"))
    idx = y * 12 + (m - 1) - n
    return f"{idx // 12}-{idx % 12 + 1:02d}"


SPEND = "COALESCE(my_share, amount)"


def month_stats(conn, month: str, until_day: int | None = None) -> dict:
    lo, hi = _month_bounds(month)
    if until_day:  # same-period comparison, e.g. 1st-14th of last month
        hi = min(hi, f"{month}-{until_day:02d}")
    q = lambda sql, *a: conn.execute(sql, (lo, hi, *a)).fetchall()
    by_cat = {r[0]: round(r[1], 2) for r in q(
        f"SELECT category, SUM({SPEND}) FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ? GROUP BY category")}
    spent = round(sum(by_cat.values()), 2)
    received = round(q("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE kind='credit' AND date BETWEEN ? AND ?")[0][0], 2)
    top_merchants = [dict(merchant=r[0], total=round(r[1], 2), count=r[2]) for r in q(
        f"SELECT merchant, SUM({SPEND}) t, COUNT(*) FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ? "
        "GROUP BY lower(merchant) ORDER BY t DESC LIMIT 5")]
    biggest = q(f"SELECT merchant, {SPEND}, date, category FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ? "
                f"ORDER BY {SPEND} DESC LIMIT 1")
    by_weekday = {r[0]: round(r[1], 2) for r in q(
        f"SELECT strftime('%w', date), SUM({SPEND}) FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ? GROUP BY 1")}
    n_tx = q("SELECT COUNT(*) FROM transactions WHERE kind='debit' AND date BETWEEN ? AND ?")[0][0]
    return {"month": month, "spent": spent, "received": received, "by_category": by_cat, "transactions": n_tx,
            "top_merchants": top_merchants,
            "biggest": dict(merchant=biggest[0][0], amount=round(biggest[0][1], 2), date=biggest[0][2],
                            category=biggest[0][3]) if biggest else None,
            "by_weekday": by_weekday}


def recommendations(conn, month: str, today: date) -> dict:
    """Suggest category budgets and a daily allowance from the last 3 full months of spending."""
    months = [prev_month(month, i) for i in (1, 2, 3)]
    hist = [month_stats(conn, m) for m in months]
    hist = [h for h in hist if h["spent"] > 0]
    settings = budget.get_settings(conn)
    if not hist:
        hist = [month_stats(conn, month)]  # brand new user: use this month so far
    cats = sorted({c for h in hist for c in h["by_category"]})
    per_cat = []
    for c in cats:
        vals = [h["by_category"].get(c, 0) for h in hist]
        typical = median(vals)
        if typical <= 0:
            continue
        cut = 1.0 if c in FIXED else 0.9  # trim 10% from flexible spending
        per_cat.append({"category": c, "typical": round(typical), "suggested": round(typical * cut / 50) * 50 or 50,
                        "flexible": c not in FIXED})
    income = median([h["received"] for h in hist]) if hist else 0
    fixed = sum(p["typical"] for p in per_cat if not p["flexible"])
    excluded = set(settings["excluded_categories"])
    flexible_typical = sum(p["typical"] for p in per_cat if p["category"] not in excluded)
    goal = settings["savings_goal_pct"] / 100
    if income > 0:
        basis = "income"
        daily = max(0.0, (income * (1 - goal) - fixed) / 30)
    else:
        basis = "spending"
        daily = flexible_typical * 0.9 / 30
    daily = max(50, round(daily / 10) * 10)
    tips = []
    subs = recurring.list_subscriptions(conn, today)
    if subs["monthly_total"]:
        tips.append(f"Subscriptions cost ₹{subs['monthly_total']:,.0f}/month (₹{subs['yearly_total']:,.0f}/year). "
                    "Cancel any you haven't used in the last month.")
    for p in sorted(per_cat, key=lambda p: -p["typical"])[:2]:
        if p["flexible"]:
            tips.append(f"Keeping {p['category']} to ₹{p['suggested']:,} would save about "
                        f"₹{p['typical'] - p['suggested']:,} a month.")
    return {"based_on": [h["month"] for h in hist], "basis": basis, "monthly_income": round(income),
            "suggested_daily_allowance": daily, "current_daily_allowance": settings["daily_allowance"],
            "categories": per_cat, "tips": tips}


def rule_insights(conn, month: str, today: date) -> list[str]:
    """Plain-language observations computed locally (no AI needed)."""
    in_progress = month == today.strftime("%Y-%m")
    cur = month_stats(conn, month)
    prev = month_stats(conn, prev_month(month), until_day=today.day if in_progress else None)
    period = f"the same {today.day} days last month" if in_progress else "last month"
    out = []
    if cur["spent"] == 0:
        return ["No spending recorded this month yet."]
    if prev["spent"]:
        ch = (cur["spent"] - prev["spent"]) / prev["spent"] * 100
        out.append(f"You spent ₹{cur['spent']:,.0f}, {abs(ch):.0f}% {'more' if ch > 0 else 'less'} than {period} "
                   f"(₹{prev['spent']:,.0f}).")
    else:
        out.append(f"You spent ₹{cur['spent']:,.0f} across {cur['transactions']} payments.")
    for c, v in sorted(cur["by_category"].items(), key=lambda kv: -kv[1]):
        p = prev["by_category"].get(c, 0)
        if p and v > p * 1.3 and v - p > 500:
            out.append(f"{c} is up ₹{v - p:,.0f} compared with {period}.")
    top = max(cur["by_category"].items(), key=lambda kv: kv[1])
    out.append(f"Biggest category: {top[0]} at ₹{top[1]:,.0f} ({top[1] / cur['spent'] * 100:.0f}% of spending).")
    if cur["top_merchants"]:
        m = cur["top_merchants"][0]
        out.append(f"You paid {m['merchant']} {m['count']} time{'s' * (m['count'] > 1)}, ₹{m['total']:,.0f} in total.")
    if cur["by_weekday"]:
        names = ["Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays"]
        wd = max(cur["by_weekday"].items(), key=lambda kv: kv[1])
        out.append(f"You spend the most on {names[int(wd[0])]}.")
    b = budget.status(conn, today)
    out.append(f"Savings Pool: ₹{b['savings_pool']:,.0f} since {b['since']}"
               + (f", over budget on {b['days_over_budget']} day(s)." if b["days_over_budget"] else "."))
    return out


def merchant_history(conn, merchant: str, today: date) -> dict:
    key = merchant_key(merchant)
    rows = [dict(r) for r in conn.execute(
        "SELECT id, date, amount, my_share, kind, category FROM transactions WHERE lower(merchant)=? ORDER BY date DESC",
        (key,))]
    debits = [r for r in rows if r["kind"] == "debit"]
    by_month: dict[str, float] = {}
    for r in debits:
        by_month[r["date"][:7]] = by_month.get(r["date"][:7], 0) + (r["my_share"] if r["my_share"] is not None else r["amount"])
    months = [prev_month(today.strftime("%Y-%m"), i) for i in range(11, -1, -1)]
    total = sum(by_month.values())
    sub = conn.execute("SELECT * FROM subscriptions WHERE merchant_key=?", (key,)).fetchone()
    return {
        "merchant": merchant,
        "total": round(total, 2), "count": len(debits),
        "average": round(total / len(debits), 2) if debits else 0,
        "first": debits[-1]["date"] if debits else None, "last": debits[0]["date"] if debits else None,
        "monthly": [{"month": m, "total": round(by_month.get(m, 0), 2)} for m in months],
        "subscription": dict(sub) if sub else None,
        "transactions": rows[:50],
    }


# ---------- search / natural-language questions ----------

MONTHS = {m: i for i, m in enumerate(
    ["january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
     "november", "december"], 1)}
MONTHS.update({k[:3]: v for k, v in list(MONTHS.items())})


def local_plan(question: str, categories: list[str], today: date) -> dict:
    """Turn a simple question into a query plan without AI ("food last month", "how much on uber this week")."""
    q = question.lower()
    plan = {"date_from": None, "date_to": None, "categories": [], "merchant": None, "text": None,
            "kind": "debit", "min_amount": None, "max_amount": None, "aggregate": "list"}
    if re.search(r"\b(how much|total|spent|spend)\b", q):
        plan["aggregate"] = "sum"
    if re.search(r"\b(how many|count|times)\b", q):
        plan["aggregate"] = "count"
    if re.search(r"\b(by category|breakdown|categories)\b", q):
        plan["aggregate"] = "by_category"
    if re.search(r"\b(received|income|credited|salary)\b", q):
        plan["kind"] = "credit"
    if "today" in q:
        plan["date_from"] = plan["date_to"] = today.isoformat()
    elif "yesterday" in q:
        plan["date_from"] = plan["date_to"] = (today - timedelta(days=1)).isoformat()
    elif "this week" in q:
        plan["date_from"], plan["date_to"] = (today - timedelta(days=today.weekday())).isoformat(), today.isoformat()
    elif "last week" in q:
        start = today - timedelta(days=today.weekday() + 7)
        plan["date_from"], plan["date_to"] = start.isoformat(), (start + timedelta(days=6)).isoformat()
    elif "last month" in q:
        plan["date_from"], plan["date_to"] = _month_bounds(prev_month(today.strftime("%Y-%m")))
    elif "this month" in q:
        plan["date_from"], plan["date_to"] = today.replace(day=1).isoformat(), today.isoformat()
    elif m := re.search(r"last (\d+) days", q):
        plan["date_from"], plan["date_to"] = (today - timedelta(days=int(m.group(1)) - 1)).isoformat(), today.isoformat()
    elif "this year" in q:
        plan["date_from"], plan["date_to"] = today.replace(month=1, day=1).isoformat(), today.isoformat()
    else:
        for name, num in MONTHS.items():
            if re.search(rf"\b{name}\b", q):
                y = today.year if num <= today.month else today.year - 1
                plan["date_from"], plan["date_to"] = _month_bounds(f"{y}-{num:02d}")
                break
    if m := re.search(r"(?:over|above|more than|>)\s*(?:rs\.?|₹)?\s*(\d+)", q):
        plan["min_amount"] = float(m.group(1))
    if m := re.search(r"(?:under|below|less than|<)\s*(?:rs\.?|₹)?\s*(\d+)", q):
        plan["max_amount"] = float(m.group(1))
    aliases = {"food": "Food & Dining", "eating out": "Food & Dining", "dining": "Food & Dining",
               "grocer": "Groceries", "travel": "Travel", "cab": "Transport", "transport": "Transport",
               "petrol": "Fuel", "fuel": "Fuel", "shopping": "Shopping", "bill": "Bills & Utilities",
               "rent": "Rent", "movie": "Entertainment", "entertainment": "Entertainment", "health": "Health",
               "medic": "Health", "subscription": "Entertainment"}
    for c in categories:
        if c.lower() in q:
            plan["categories"].append(c)
    for k, c in aliases.items():
        if k in q and c not in plan["categories"] and c in categories:
            plan["categories"].append(c)
    if not plan["categories"]:
        stop = {"how", "much", "did", "i", "spend", "spent", "on", "at", "in", "the", "this", "last", "month", "week",
                "what", "my", "show", "me", "all", "total", "for", "today", "yesterday", "many", "times", "pay", "paid",
                "to", "year", "days", "over", "under", "above", "below", "than", "more", "less", "expenses",
                "expense", "payments", "transactions", "find", "list", "a", "was", "were", "with", "and", "count"}
        words = [w for w in re.findall(r"[a-z][a-z0-9.&'-]+", q) if w not in stop and w not in MONTHS]
        if words:
            plan["text"] = " ".join(words[:3])
    return plan


def run_plan(conn, plan: dict) -> dict:
    sql, args = " FROM transactions WHERE 1=1", []
    if plan.get("kind") in ("debit", "credit"):
        sql += " AND kind=?"; args.append(plan["kind"])
    if plan.get("date_from"):
        sql += " AND date>=?"; args.append(plan["date_from"])
    if plan.get("date_to"):
        sql += " AND date<=?"; args.append(plan["date_to"])
    if plan.get("categories"):
        sql += f" AND category IN ({','.join('?' * len(plan['categories']))})"; args += plan["categories"]
    if plan.get("merchant"):
        sql += " AND lower(merchant) LIKE ?"; args.append(f"%{plan['merchant'].lower()}%")
    if plan.get("text"):
        for w in plan["text"].lower().split():
            sql += " AND (lower(merchant) LIKE ? OR lower(note) LIKE ? OR lower(category) LIKE ?)"
            args += [f"%{w}%"] * 3
    if plan.get("min_amount") is not None:
        sql += " AND amount>=?"; args.append(plan["min_amount"])
    if plan.get("max_amount") is not None:
        sql += " AND amount<=?"; args.append(plan["max_amount"])
    rows = [dict(r) for r in conn.execute(f"SELECT *{sql} ORDER BY date DESC, id DESC LIMIT 200", args)]
    total = conn.execute(f"SELECT COALESCE(SUM({SPEND}),0), COUNT(*){sql}", args).fetchone()
    by_cat = [dict(category=r[0], total=round(r[1], 2)) for r in conn.execute(
        f"SELECT category, SUM({SPEND}) t{sql} GROUP BY category ORDER BY t DESC", args)]
    return {"total": round(total[0], 2), "count": total[1], "by_category": by_cat, "transactions": rows}


def describe(plan: dict, result: dict) -> str:
    what = ", ".join(plan.get("categories") or []) or plan.get("merchant") or plan.get("text") or "everything"
    if plan.get("date_from") and plan.get("date_from") == plan.get("date_to"):
        when = f" on {plan['date_from']}"
    elif plan.get("date_from"):
        when = f" from {plan['date_from']} to {plan.get('date_to') or 'today'}"
    else:
        when = ""
    verb = "received" if plan.get("kind") == "credit" else "spent"
    if result["count"] == 0:
        return f"No matching transactions for {what}{when}."
    if plan.get("aggregate") == "count":
        return f"{result['count']} payment{'s' * (result['count'] != 1)} for {what}{when}."
    if plan.get("aggregate") == "by_category" and result["by_category"]:
        return f"Breakdown{when}: " + ", ".join(f"{c['category']} ₹{c['total']:,.0f}" for c in result["by_category"][:6])
    return f"You {verb} ₹{result['total']:,.2f} on {what}{when} ({result['count']} transaction{'s' * (result['count'] != 1)})."
