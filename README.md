# Artha · Daily Spending Tracker

Logs your daily spending and sorts each spend into a category for you.
You can paste quick notes like `250 swiggy`, paste bank or UPI SMS, import a bank-statement CSV,
or have your phone forward every bank SMS to the app automatically.

## Features
- **Quick text entry**: `250 swiggy dinner`, `petrol 1500 yesterday`, `spent 120 on uber`. You can add several lines at once.
- **Reads bank and UPI SMS**: finds the amount, debit or credit, merchant (including UPI IDs like `netflix@icici`) and the date.
- **Automatic categories**: 15 categories, with keyword rules for common Indian merchants (Swiggy, Zepto, Blinkit, Uber, Jio, Netflix and so on).
- **Learns from you**: change a transaction's category once, and that merchant gets the same category from then on.
- **No duplicates**: if the same SMS arrives twice, it is only recorded once.
- **Dashboard**: shows today's total, the month's total, spending by category and the transaction list. Works on phones and in dark mode.
- **CSV import and export**: works with HDFC, ICICI, SBI and similar statements (columns for date, narration and withdrawal/deposit or amount).

## Run it
```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
export ARTHA_TOKEN=some-long-random-string   # optional locally, required when exposed online
uvicorn app.main:app --host 0.0.0.0 --port 8000
```
Open http://localhost:8000.

With Docker:
```bash
docker build -t artha .
docker run -p 8000:8000 -e ARTHA_TOKEN=... -v artha-data:/data artha
```

## Automate it (no more copy-pasting)
Host the app somewhere your phone can reach. Any small VPS, Render, Railway or Fly.io works; keep the `/data` volume so your data persists.
Then send each bank SMS to the webhook:

```
POST https://<your-host>/api/sms?token=<ARTHA_TOKEN>
Content-Type: application/json
{"message": "<sms text>"}
```

- **Android**: use an "SMS Forwarder" app (or Tasker / MacroDroid). Make a rule that matches messages from your bank (e.g. sender contains `HDFCBK`, `ICICIB`, `SBIUPI`) and sends a webhook to the URL above with the body `{"message": "%body%"}`.
- **iPhone**: Shortcuts → Automation → Message → *Message contains "debited"* → **Get contents of URL** (POST, JSON, `message` = Shortcut Input) → Run immediately.

The webhook also accepts form data, or the SMS sent as plain text in the request body.

## API
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/ingest` `{"text": "..."}` | Add one or more lines or SMS |
| POST | `/api/sms` | Webhook for one forwarded SMS |
| POST | `/api/transactions` | Add one transaction with explicit fields |
| GET | `/api/transactions?month=YYYY-MM&category=` | List transactions |
| PATCH | `/api/transactions/{id}` | Edit a transaction (changing its category teaches the app) |
| DELETE | `/api/transactions/{id}` | Delete a transaction |
| GET | `/api/summary?month=YYYY-MM` | Totals by category and by day |
| POST | `/api/import/csv` | Import a bank-statement CSV |
| GET | `/api/export.csv` | Download all transactions |

If `ARTHA_TOKEN` is set, every `/api` call needs a `X-Token` header or a `?token=` query parameter.

## Tests
```bash
pip install pytest httpx && python -m pytest
```
