# Artha · Spending tracker for Android

Artha logs your spending without you typing it. Everything runs **on your phone**: no server, no account, and no internet needed except for the optional AI features.

**How it works:** you pay → your bank's SMS or your UPI app's notification arrives → Artha reads it on the phone → works out the merchant and amount → sorts it into a category → **only asks you when it isn't sure** → updates your ledger, today's allowance and your Savings Pool.

## Install
1. Download [`releases/artha.apk`](releases/artha.apk) on your phone and open it. Android will ask you to allow installs from your browser or Files app.
2. Open Artha → **Settings → Automatic capture**:
   - **Bank SMS → Allow**
   - **UPI & bank app notifications → Allow** (turn on *Artha payment capture* in the list that opens)
3. If Android says **“Restricted setting”**: open **App info** (the link is right there in Settings) → ⋮ menu → **Allow restricted settings**, then try again. Android does this for every app installed outside the Play Store.
4. Optional: paste a Claude API key in **Settings → AI features** to turn on receipt scanning, smarter voice entry, questions in plain English and AI-written monthly insights.

Requires Android 11 or newer. Built for and tested on Pixel-sized screens.

## Features
**Capture without typing**
- **Bank SMS**, read the moment they arrive. Only messages from bank-style senders (like `AX-HDFCBK`) are considered. Texts from people are ignored.
- **Payment notifications** from GPay, PhonePe, Paytm, BHIM, Amazon Pay, CRED and major bank apps. No other app's notifications are read.
- If the same payment arrives as both an SMS and an app notification, it's merged automatically without asking you.
- OTPs, failed payments, “will be debited” reminders and payment requests are ignored, because they aren't money spent.
- **Share** any message to Artha, **paste** it, **type** `250 swiggy` or `900 dinner split with Rahul and Priya`, **speak** it 🎤, or **scan a receipt or invoice** 📷 (AI).
- **Import a bank-statement CSV** (HDFC, ICICI, SBI and similar).

**Smart ledger**
- **Categorizes automatically.** Your custom rules come first, then merchants you've corrected before, then built-in keywords for Indian merchants. You can add your own categories.
- **Asks only when it needs to.** A notification and the “Needs your input” inbox appear only for unknown merchants, payments it couldn't categorize, possible double charges, or a subscription you cancelled charging again. Answer once and it remembers.
- **Recurring payments and subscriptions**: shows monthly and yearly cost and the next renewal. You can confirm, ignore, mark cancelled or add them yourself. Rent and bills are listed separately from real subscriptions.
- **Duplicate payment detection.**
- **Splitting shared expenses.** Only your share counts toward your budget. “Who owes you” tracks the rest, and when that person pays you back by UPI, it's marked settled automatically.
- **Merchant history**: total, average and a 12-month chart.

**Budget**
- **Adjustable daily allowance.** Whatever you don't spend goes into the **Savings Pool**, and overspending takes it back out. Fixed costs (rent, bills, investments) don't count against it by default.
- **Personalised budget recommendations** from your last 3 months: a suggested daily allowance (income − fixed costs − savings goal) and a target per category.

**Ask and understand**
- **Ask questions in plain English, or search**: “how much on food last month?”, “uber over 300”. This works offline, and with AI on it understands looser wording.
- **Monthly insights** that compare against the same days of last month. With AI on, you also get a written summary and recommendations.

**Privacy and control**
- **Light, dark or system theme.**
- **All data stays on the phone** in Artha's private storage, and is excluded from Google cloud backup and device-to-device transfer.
- Account numbers, balances and OTPs are removed before anything is saved.
- Optional **fingerprint or screen-lock app lock**. By default Artha is **hidden from recent apps and blocks screenshots**.
- **Delete data** by date range, all transactions, what it learned, or everything. Deleted rows are overwritten in the database file, not just hidden.
- **Export** a CSV or a full backup, and **restore** a backup on a new phone.
- **AI is optional.** Your Claude key is stored encrypted with Android Keystore. Claude only sees what one feature needs (a receipt you scan, a sentence you speak or type, a question you ask, or monthly totals), never your ledger or account numbers.

## Build it yourself
```bash
cd android
gradle testDebugUnitTest      # engine tests (parser, budget, splits, recurring, rules, backup…)
gradle assembleRelease        # app/build/outputs/apk/release/app-release.apk
```
You need JDK 17+ and the Android SDK (platform 35). To sign with your own key, create `android/keystore.properties` with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`; it is git-ignored. Without it, the release build is signed with the debug key.

GitHub Actions (`.github/workflows/android.yml`) runs the tests and builds the APK on every push. To sign CI builds with your own key, add the repository secrets `ANDROID_KEYSTORE_BASE64` and `ANDROID_KEYSTORE_PASSWORD`.

To try the UI in a desktop browser with the real engine behind it, run `ARTHA_DEVSERVER=600 gradle testDebugUnitTest --tests '*DevServer*'` and open http://127.0.0.1:8130.

## How the code is organised
| Path | What it is |
|---|---|
| `android/app/src/main/java/ai/artha/core/` | The engine, in plain Kotlin: parser, categorizer, redaction, ledger, budget and Savings Pool, recurring payments, insights, and the API the UI calls. It's tested on the JVM. |
| `android/app/src/main/java/ai/artha/app/` | The Android side: SQLite storage, SMS receiver, notification listener, the screen and its bridge, app lock, encrypted key storage, and the Claude client. |
| `android/app/src/main/assets/index.html` | The UI, bundled inside the APK. |
