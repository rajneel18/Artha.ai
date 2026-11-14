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
- **Phone app**: installs to your Android home screen, shows up in Share menus so you can share an SMS to it, opens offline, has large touch targets and supports dark mode.
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

## Use it on your phone (tested on a Pixel 9)
1. **Host it on HTTPS.** Android only lets you install web apps and use Share from an HTTPS address. Render, Railway, Fly.io or a VPS with Caddy all give you HTTPS. Set `ARTHA_TOKEN` (a long random string). Set `ARTHA_TZ` only if you are not in India.
2. **Install it.** Open the URL in Chrome → ⋮ menu → **Add to home screen → Install** (or tap *Install app* on the page). It opens full-screen like a normal app, and enter your token the first time it asks.
3. **Add spending in three ways:**
   - Type `250 swiggy` and tap **Add**.
   - Copy a bank SMS and tap **Paste SMS**.
   - In Google Messages, long-press a bank SMS → **Share → Artha**. It is saved straight away, with an **Undo** option.
4. **Fix a category by tapping its chip.** The app remembers that merchant from then on.

## Make it fully automatic on Android
For this to work, your phone has to send each bank or UPI alert to the webhook:

```
POST https://<your-host>/api/sms?token=<ARTHA_TOKEN>
Content-Type: application/json
{"message": "<sms text>"}
```

**Option A: MacroDroid (free, works on Pixel)**
1. Install MacroDroid → **Add macro**.
2. **Trigger:** *Notification → Notification received*. Choose **Messages** (Google Messages), and add GPay, PhonePe or Paytm if you want UPI app alerts too. Set the text filter to *contains* `debited` (add `spent` and `sent Rs` as extra triggers).
3. **Action:** *Web interactions → HTTP request*. Use Method **POST**, the URL above, and content type **application/json**. Set the body to `{"message": "[notification]"}`. Insert `[notification]` with the magic-text button.
4. Save it. Then go to Android **Settings → Apps → MacroDroid → App battery usage → Unrestricted**, so Pixel's battery saver doesn't stop it in the background.

**Option B: an SMS-forwarder app.** Search the Play Store for *SMS forwarder webhook*, or install *SMS to URL Forwarder* from F-Droid. Filter by your bank's sender (e.g. `HDFCBK`, `ICICIT`, `SBIUPI`, `AXISBK`) and point it at the same URL with the same JSON body.

If the same SMS arrives twice (for example from both the SMS and the UPI-app notification), it is only saved once. The webhook also accepts form data, or the SMS sent as plain text in the request body.

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

`ARTHA_TZ` (default `Asia/Kolkata`) decides what "today" means, because servers usually run on UTC. If `ARTHA_TOKEN` is set, every `/api` call needs a `X-Token` header or a `?token=` query parameter.

## Tests
```bash
pip install pytest httpx && python -m pytest
```
