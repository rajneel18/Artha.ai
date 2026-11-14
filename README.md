# Artha · Daily Spending Tracker

Logs your daily spending and sorts each spend into a category for you.
You can paste quick notes like `250 swiggy`, paste bank or UPI SMS, import a bank-statement CSV,
or have your phone forward every bank SMS to the app automatically.

## How it works
**Goal: track spending without typing anything, wherever possible.**

You pay → your bank or UPI app sends an SMS or notification → your phone forwards it to Artha → Artha works out the merchant and amount → sorts it into a category → **asks you only when it isn't sure** → updates your ledger, your daily budget and your Savings Pool.

## Features
**Capture**
- **Automatic**: bank and UPI SMS or notifications arrive at `/api/sms` (see *Make it fully automatic* below). OTPs, failed payments, "will be debited" reminders and payment requests are ignored, because they aren't money spent.
- **Share an SMS to Artha, paste it, or type** something like `250 swiggy` or `900 dinner split with Rahul and Priya`.
- **Speak it**: tap 🎤 and say "450 on coffee with Asha, split it". Chrome on Android turns your speech into text, and with AI on Claude works out the details.
- **Scan a receipt or invoice** (photo or PDF) with AI. Claude reads the merchant, total, date and items.
- **Import a bank-statement CSV** (HDFC, ICICI, SBI and similar).

**Smart ledger**
- **Categorizes automatically.** It uses your custom rules first, then merchants you've corrected before, then built-in keywords for Indian merchants. You can add your own categories.
- **Asks only when needed.** The "Needs your input" inbox shows up only for unknown merchants, payments it couldn't categorize, possible double charges, or a subscription you cancelled charging again. Answer once and it remembers.
- **Duplicate payment detection.** The same amount to the same merchant within a day is flagged ("Were you charged twice?"). If the exact same SMS arrives twice, it's silently ignored.
- **Recurring payment detection and subscriptions.** It spots regular weekly, monthly, quarterly or yearly charges and known services (Netflix, Spotify, iCloud…). It shows the monthly and yearly cost and the next renewal date. You can confirm, ignore, cancel or add them yourself. Rent, bills and SIPs show up as recurring payments, not subscriptions.
- **Splitting shared expenses.** Split by names or by number of people. Only your share counts toward your budget, and "Who owes you" tracks the rest. When money arrives from that person, it's marked settled automatically.
- **Merchant history.** Tap any merchant to see the total, average and a 12-month chart.

**Budget**
- **Adjustable daily allowance.** Whatever you don't spend goes into the **Savings Pool**, and overspending takes it back out. You choose which categories (rent, bills…) don't count against it. Changing the allowance only affects today onwards.
- **Personalised budget recommendations** from your last 3 months: a suggested daily allowance (based on income minus fixed costs minus your savings goal) and a target for each category. Tap once to apply them.

**Ask and understand**
- **Ask questions in plain English, or search**: "how much on food last month?", "uber over 300", "show amazon in October". This works without AI, and with AI on Claude understands looser wording.
- **Monthly insights**: comparisons with the same days of last month, categories going up, top merchants and your spending patterns. With AI on, you also get a written summary and recommendations.

**App and privacy**
- **Light and dark themes** (or follow the system setting). Installs as a Pixel/Android app, has a Share target and opens offline.
- **You control deleting your data**: delete a date range, all transactions, what it learned, or everything. Deleted rows are overwritten in the database file, not just hidden. You can export a CSV or a full JSON backup first.
- **Financial data is handled securely**:
  - Token auth with constant-time comparison and lockout after 10 wrong tokens.
  - The API refuses remote access if no token is set.
  - The main token is never accepted in URLs. A separate add-only `ARTHA_WEBHOOK_TOKEN` is for automation apps.
  - Account and card numbers are masked, and balances and OTPs are removed before anything is stored or sent.
  - Strict security headers (CSP, no-referrer, no framing), `no-store` caching on API responses, and the database file is readable only by its owner.
  - Claude only sees what you send for one feature, and monthly insights use totals only, never your ledger.

## Run it
```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
export ARTHA_TOKEN=$(openssl rand -hex 24)   # required to use it from your phone
export ANTHROPIC_API_KEY=...                  # optional: AI features (then turn them on in Settings)
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
POST https://<your-host>/api/sms?token=<ARTHA_WEBHOOK_TOKEN>
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
Every `/api` call needs the `X-Token` header. Only `/api/sms` also accepts `?token=`.

| Area | Endpoints |
|---|---|
| Capture | `POST /api/ingest` `{text, smart}` · `POST /api/sms` · `POST /api/receipt` (file) · `POST /api/transactions` · `POST /api/import/csv` |
| Ledger | `GET /api/transactions?month=&category=&merchant=` · `GET/PATCH/DELETE /api/transactions/{id}` |
| Clarifications | `GET /api/review` · `POST /api/review/{id}` `{action: confirm/delete/reactivate_subscription, category, merchant}` |
| Splits | `POST/DELETE /api/transactions/{id}/split` `{people, ways, amounts}` · `GET /api/splits` · `POST /api/splits/settle` |
| Budget | `GET /api/budget` · `GET /api/summary?month=` · `GET/PUT /api/settings` · `GET /api/recommendations` |
| Rules | `GET/POST /api/rules` · `DELETE /api/rules/{id}` · `DELETE /api/learned/{merchant}` |
| Subscriptions | `GET/POST /api/subscriptions` · `PATCH /api/subscriptions/{id}` · `POST /api/subscriptions/scan` |
| Insights | `POST /api/ask` `{question}` · `GET /api/insights?month=` · `POST /api/insights/ai?month=` · `GET /api/merchants` · `GET /api/merchants/history?name=` |
| Your data | `GET /api/export.csv` · `GET /api/export.json` · `POST /api/data/delete` `{scope, confirm: "DELETE"}` |

`ARTHA_TZ` (default `Asia/Kolkata`) decides what "today" means. See `.env.example` for all settings.

## Tests
```bash
pip install pytest httpx && python -m pytest
```
