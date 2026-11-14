"""Artha - automated daily spending tracker."""
import csv
import hashlib
import io
import os
import re
import datetime as dt
from contextlib import asynccontextmanager
from datetime import date, datetime
from pathlib import Path

from fastapi import Depends, FastAPI, File, Header, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel

from . import db
from .categorizer import CATEGORIES, categorize, merchant_key
from .parser import parse_message

STATIC = Path(__file__).parent / "static"


@asynccontextmanager
async def lifespan(_app):
    db.init_db(db.DB_PATH)
    yield


app = FastAPI(title="Artha Spending Tracker", lifespan=lifespan)


def require_token(
    x_token: str | None = Header(default=None),
    token: str | None = Query(default=None),
) -> None:
    expected = os.environ.get("ARTHA_TOKEN")
    if expected and (x_token or token) != expected:
        raise HTTPException(401, "Missing or wrong token")


# ---------- helpers ----------

def _learned(conn) -> dict[str, str]:
    return {r["merchant"]: r["category"] for r in conn.execute("SELECT merchant, category FROM merchant_rules")}


def _row(r) -> dict:
    return dict(r)


def _insert(conn, *, d: date, amount: float, kind: str, merchant: str, note: str, source: str,
            category: str | None = None, raw: str | None = None) -> dict | None:
    category = category or categorize(note, merchant, _learned(conn))
    raw_hash = hashlib.sha256(raw.strip().encode()).hexdigest() if raw else None
    try:
        cur = conn.execute(
            "INSERT INTO transactions(date, amount, kind, merchant, category, note, source, raw_hash)"
            " VALUES (?,?,?,?,?,?,?,?)",
            (d.isoformat(), round(amount, 2), kind, merchant, category, note, source, raw_hash),
        )
    except db.sqlite3.IntegrityError:
        return None  # duplicate message already recorded
    return _row(conn.execute("SELECT * FROM transactions WHERE id=?", (cur.lastrowid,)).fetchone())


def _split_messages(text: str) -> list[str]:
    text = text.strip()
    if re.search(r"\n\s*\n", text):
        return [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    return [l.strip() for l in text.splitlines() if l.strip()]


def ingest_text(text: str, source_override: str | None = None) -> dict:
    added, skipped, duplicates = [], [], 0
    with db.connect() as conn:
        for msg in _split_messages(text):
            p = parse_message(msg)
            if not p:
                skipped.append(msg)
                continue
            row = _insert(conn, d=p.date, amount=p.amount, kind=p.kind, merchant=p.merchant,
                          note=p.note, source=source_override or p.source,
                          raw=msg if p.source == "sms" else None)
            if row is None:
                duplicates += 1
            else:
                added.append(row)
    return {"added": added, "skipped": skipped, "duplicates": duplicates}


# ---------- API ----------

class IngestIn(BaseModel):
    text: str


class TxIn(BaseModel):
    amount: float
    merchant: str = "Unknown"
    category: str | None = None
    kind: str = "debit"
    date: dt.date | None = None
    note: str = ""


class TxPatch(BaseModel):
    amount: float | None = None
    merchant: str | None = None
    category: str | None = None
    kind: str | None = None
    date: dt.date | None = None
    note: str | None = None


@app.get("/")
def index():
    return FileResponse(STATIC / "index.html")


@app.get("/api/categories")
def categories():
    return CATEGORIES


@app.post("/api/ingest", dependencies=[Depends(require_token)])
def ingest(body: IngestIn):
    """Paste one or many lines: '250 swiggy', 'petrol 1500 yesterday', or full bank SMS."""
    return ingest_text(body.text)


@app.post("/api/sms", dependencies=[Depends(require_token)])
async def sms_webhook(request: Request):
    """Webhook for phone automation (Android SMS forwarder apps, iOS Shortcuts, Tasker...).

    Accepts JSON {"text"|"message"|"body"|"msg": "..."}, form data with the same keys, or a raw text body.
    """
    ctype = request.headers.get("content-type", "")
    text = ""
    if "json" in ctype:
        data = await request.json()
        if isinstance(data, dict):
            text = next((str(data[k]) for k in ("text", "message", "body", "msg", "sms") if data.get(k)), "")
        elif isinstance(data, str):
            text = data
    elif "form" in ctype:
        form = await request.form()
        text = next((str(form[k]) for k in ("text", "message", "body", "msg", "sms") if form.get(k)), "")
    else:
        text = (await request.body()).decode("utf-8", "ignore")
    if not text.strip():
        raise HTTPException(400, "No message text found")
    # A single forwarded SMS should be treated as one message even if it has line breaks.
    one = " ".join(text.split())
    return ingest_text(one, source_override="sms")


@app.post("/api/transactions", dependencies=[Depends(require_token)])
def add_tx(tx: TxIn):
    with db.connect() as conn:
        return _insert(conn, d=tx.date or date.today(), amount=tx.amount, kind=tx.kind,
                       merchant=tx.merchant, note=tx.note or tx.merchant, source="manual",
                       category=tx.category)


@app.get("/api/transactions", dependencies=[Depends(require_token)])
def list_tx(month: str | None = None, category: str | None = None, limit: int = 500):
    q, args = "SELECT * FROM transactions WHERE 1=1", []
    if month:
        q += " AND substr(date,1,7)=?"
        args.append(month)
    if category:
        q += " AND category=?"
        args.append(category)
    q += " ORDER BY date DESC, id DESC LIMIT ?"
    args.append(limit)
    with db.connect() as conn:
        return [_row(r) for r in conn.execute(q, args)]


@app.patch("/api/transactions/{tx_id}", dependencies=[Depends(require_token)])
def update_tx(tx_id: int, patch: TxPatch):
    fields = {k: (v.isoformat() if isinstance(v, dt.date) else v)
              for k, v in patch.model_dump(exclude_unset=True).items() if v is not None}
    if patch.category and patch.category not in CATEGORIES:
        raise HTTPException(400, f"Unknown category. Use one of: {CATEGORIES}")
    with db.connect() as conn:
        row = conn.execute("SELECT * FROM transactions WHERE id=?", (tx_id,)).fetchone()
        if not row:
            raise HTTPException(404, "Not found")
        if fields:
            sets = ", ".join(f"{k}=?" for k in fields)
            conn.execute(f"UPDATE transactions SET {sets} WHERE id=?", (*fields.values(), tx_id))
        if patch.category:
            # Learn: next time this merchant shows up it gets the same category.
            key = merchant_key(fields.get("merchant", row["merchant"]))
            if key and key != "unknown":
                conn.execute("INSERT INTO merchant_rules(merchant, category) VALUES(?,?) "
                             "ON CONFLICT(merchant) DO UPDATE SET category=excluded.category",
                             (key, patch.category))
        return _row(conn.execute("SELECT * FROM transactions WHERE id=?", (tx_id,)).fetchone())


@app.delete("/api/transactions/{tx_id}", dependencies=[Depends(require_token)])
def delete_tx(tx_id: int):
    with db.connect() as conn:
        conn.execute("DELETE FROM transactions WHERE id=?", (tx_id,))
    return {"ok": True}


@app.get("/api/summary", dependencies=[Depends(require_token)])
def summary(month: str | None = None):
    month = month or date.today().strftime("%Y-%m")
    with db.connect() as conn:
        by_cat = [_row(r) for r in conn.execute(
            "SELECT category, ROUND(SUM(amount),2) AS total, COUNT(*) AS count FROM transactions "
            "WHERE kind='debit' AND substr(date,1,7)=? GROUP BY category ORDER BY total DESC", (month,))]
        by_day = [_row(r) for r in conn.execute(
            "SELECT date, ROUND(SUM(amount),2) AS total FROM transactions "
            "WHERE kind='debit' AND substr(date,1,7)=? GROUP BY date ORDER BY date", (month,))]
        spent = conn.execute("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE kind='debit' "
                             "AND substr(date,1,7)=?", (month,)).fetchone()[0]
        income = conn.execute("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE kind='credit' "
                              "AND substr(date,1,7)=?", (month,)).fetchone()[0]
        today_total = conn.execute("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE kind='debit' "
                                   "AND date=?", (date.today().isoformat(),)).fetchone()[0]
    return {"month": month, "spent": round(spent, 2), "received": round(income, 2),
            "today": round(today_total, 2), "by_category": by_cat, "by_day": by_day}


@app.post("/api/import/csv", dependencies=[Depends(require_token)])
async def import_csv(file: UploadFile = File(...)):
    """Import a bank statement CSV. Detects columns named like date / description / amount / debit / credit."""
    content = (await file.read()).decode("utf-8-sig", "ignore")
    reader = csv.DictReader(io.StringIO(content))
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
                a = num(r.get(c_amt))
                amount, kind = abs(a), ("credit" if a > 0 and "cr" in str(r).lower() else "debit")
            if not d or not amount:
                skipped += 1
                continue
            p = parse_message(desc) if desc else None
            merchant = p.merchant if p and p.merchant != "Unknown" else (desc[:40] or "Unknown")
            raw = f"csv|{d}|{amount}|{desc}"
            if _insert(conn, d=d, amount=amount, kind=kind, merchant=merchant, note=desc,
                       source="csv", raw=raw):
                added += 1
            else:
                skipped += 1
    return {"added": added, "skipped": skipped}


@app.get("/api/export.csv", dependencies=[Depends(require_token)])
def export_csv():
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["date", "amount", "kind", "merchant", "category", "note", "source"])
    with db.connect() as conn:
        for r in conn.execute("SELECT * FROM transactions ORDER BY date"):
            w.writerow([r["date"], r["amount"], r["kind"], r["merchant"], r["category"], r["note"], r["source"]])
    buf.seek(0)
    return StreamingResponse(iter([buf.getvalue()]), media_type="text/csv",
                             headers={"Content-Disposition": "attachment; filename=artha-transactions.csv"})
