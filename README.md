# Artha · Spending tracker for Android

Artha reads your bank SMS with a **small AI model that runs on your phone** (Qwen 2.5 1.5B). There's no server, no account and no cloud AI. Your messages never leave the phone.

**How it works:** you pay → your bank sends an SMS → the on-device AI reads it (amount, who you paid, category) → a safety check makes sure OTPs, failed payments and scheduled AutoPay are never counted as spending → the payment appears under **Detected payments**, where you tap **Log** or **Skip**, or **Log all** at once. Logging updates your ledger, today's allowance and your Savings Pool.

## Install
1. Download [`releases/artha.apk`](releases/artha.apk) on your phone and open it. Allow installs from your browser or Files app.
2. In Artha → **Settings → On-device AI**, tap **Download**. This is a one-time 1.6 GB file, so use Wi-Fi.
3. In **Settings → Bank SMS**, tap **Allow**. If Android says **"Restricted setting"**, open App info (the link is in Settings) → three-dot menu → **Allow restricted settings**, then try again.
4. Optional: **Import past SMS** (1 to 12 months) or **Import statement** (CSV or text). Everything goes through the same Detected list.

Requires Android 11 or newer on a 64-bit phone. 8 GB of RAM or more is recommended; Pixel 9 is fine.

## Features
- **SMS only.** Only messages from bank senders (like `AX-SARBNK`) are read. Texts from people are ignored.
- **On-device AI reading, no regex.** The model reads each message. Its answer is checked: the amount must appear in the SMS, otherwise the item is marked "please check".
- **Safety check.** Messages mentioning an OTP, a failed or declined payment, or a pre-approved offer are skipped. "Scheduled" or "due" messages become **Upcoming**, and confirming one adds it to Subscriptions instead of spending. Skipped messages stay visible, and you can **Log anyway**.
- **You stay in control.** Every detection waits for **Log** or **Skip**, with a **Log all** button. Turn on **Log confident payments without asking** in Settings if you trust it.
- **Learns from you.** Change a category once, and that payee is categorized your way next time. Your own rules always win.
- **Light and dark** beige themes, with no emojis.
- **Budget**: adjustable daily allowance and Savings Pool, plus recommendations.
- **Splitting**: split a payment and track who owes you; it's settled automatically when they pay you back.
- **Recurring payments and subscriptions**, **merchant history**, **plain-English questions** ("how much on food last month?").
- **Privacy**: all data stays on the phone and is excluded from cloud backup. Balances and account numbers are removed before saving. Optional fingerprint lock. Screenshots are blocked by default. You can delete data, export a CSV or full backup, and restore a backup.

## Build it yourself
```bash
cd android
gradle testDebugUnitTest      # engine tests (AI flow with a stand-in model, safety check, budget, splits, rules, backup…)
gradle assembleRelease        # app/build/outputs/apk/release/app-release.apk
```
You need JDK 17+ and the Android SDK (platform 35). To sign with your own key, create `android/keystore.properties` with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`; it is git-ignored. Without it, the release build is signed with the debug key.

GitHub Actions (`.github/workflows/android.yml`) runs the tests and builds the APK on every push. To sign CI builds with your own key, add the repository secrets `ANDROID_KEYSTORE_BASE64` and `ANDROID_KEYSTORE_PASSWORD`.

To try the UI in a desktop browser with the real engine behind it, run `ARTHA_DEVSERVER=600 gradle testDebugUnitTest --tests '*DevServer*'` and open http://127.0.0.1:8130.

## How the code is organised
| Path | What it is |
|---|---|
| `android/app/src/main/java/ai/artha/core/` | The engine, in plain Kotlin: the AI extractor and safety check, the Detected queue, ledger, budget and Savings Pool, recurring payments, insights, and the API the UI calls. It's tested on the JVM with a stand-in model. |
| `android/app/src/main/java/ai/artha/app/` | The Android side: SQLite storage, SMS receiver, background AI worker, model download, the Qwen runner (MediaPipe), the screen and its bridge, and app lock. |
| `android/app/src/main/assets/index.html` | The UI, bundled inside the APK. |
