"""Settings, daily allowance and the Savings Pool.

Every day you get a daily allowance for day-to-day spending. Whatever you don't spend rolls into the
Savings Pool; overspending takes it back out. Fixed costs (rent, bills, investments, transfers) are
excluded by default so they don't wreck the daily budget. For split expenses only your share counts.
"""
import json
from datetime import date, timedelta

from .categorizer import CATEGORIES

DEFAULTS = {
    "daily_allowance": 500.0,
    "excluded_categories": ["Rent", "Bills & Utilities", "Investments", "Transfers"],
    "custom_categories": [],
    "ai_enabled": False,
    "savings_goal_pct": 20,
    "budget_start": None,  # set on first use
}


def get_settings(conn) -> dict:
    s = dict(DEFAULTS)
    for r in conn.execute("SELECT key, value FROM settings"):
        try:
            s[r["key"]] = json.loads(r["value"])
        except ValueError:
            pass
    return s


def set_settings(conn, values: dict, today: date) -> dict:
    for k, v in values.items():
        if k not in DEFAULTS:
            continue
        conn.execute("INSERT INTO settings(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                     (k, json.dumps(v)))
        if k == "daily_allowance":
            # Changes apply from today; earlier days keep the allowance they had.
            conn.execute("INSERT INTO allowance_history(start_date, amount) VALUES(?,?) "
                         "ON CONFLICT(start_date) DO UPDATE SET amount=excluded.amount", (today.isoformat(), float(v)))
    return get_settings(conn)


def all_categories(conn) -> list[str]:
    custom = [c for c in get_settings(conn)["custom_categories"] if c not in CATEGORIES]
    return CATEGORIES[:-1] + custom + ["Other"]


def ensure_started(conn, today: date) -> date:
    s = get_settings(conn)
    if s["budget_start"]:
        return date.fromisoformat(s["budget_start"])
    first = conn.execute("SELECT MIN(date) FROM transactions").fetchone()[0]
    start = min(date.fromisoformat(first), today) if first else today
    set_settings(conn, {"budget_start": start.isoformat()}, today)
    if not conn.execute("SELECT 1 FROM allowance_history").fetchone():
        conn.execute("INSERT INTO allowance_history(start_date, amount) VALUES(?,?)",
                     (start.isoformat(), float(s["daily_allowance"])))
    return start


def _allowance_on(history: list[tuple[str, float]], day: str, default: float) -> float:
    amt = history[0][1] if history else default
    for start, a in history:
        if start <= day:
            amt = a
        else:
            break
    return amt


def budget_spend_by_day(conn, start: date, end: date, excluded: list[str]) -> dict[str, float]:
    marks = ",".join("?" * len(excluded)) or "''"
    rows = conn.execute(
        f"SELECT date, SUM(COALESCE(my_share, amount)) FROM transactions WHERE kind='debit' "
        f"AND date BETWEEN ? AND ? AND category NOT IN ({marks}) GROUP BY date",
        (start.isoformat(), end.isoformat(), *excluded)).fetchall()
    return {r[0]: r[1] for r in rows}


def status(conn, today: date) -> dict:
    s = get_settings(conn)
    start = ensure_started(conn, today)
    history = [(r[0], r[1]) for r in conn.execute("SELECT start_date, amount FROM allowance_history ORDER BY start_date")]
    spend = budget_spend_by_day(conn, start, today, s["excluded_categories"])
    pool, d, days_over, streak = 0.0, start, 0, 0
    while d < today:
        key = d.isoformat()
        diff = _allowance_on(history, key, s["daily_allowance"]) - spend.get(key, 0.0)
        pool += diff
        if diff < 0:
            days_over, streak = days_over + 1, 0
        else:
            streak += 1
        d += timedelta(days=1)
    allowance_today = _allowance_on(history, today.isoformat(), s["daily_allowance"])
    spent_today = spend.get(today.isoformat(), 0.0)
    return {
        "daily_allowance": allowance_today,
        "spent_today": round(spent_today, 2),
        "left_today": round(allowance_today - spent_today, 2),
        "savings_pool": round(pool, 2),
        "pool_if_day_ends_now": round(pool + allowance_today - spent_today, 2),
        "since": start.isoformat(),
        "days_over_budget": days_over,
        "under_budget_streak": streak,
        "excluded_categories": s["excluded_categories"],
    }
