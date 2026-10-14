# PKassist — Hybrid Online & Offline Android AI Assistant

**— powered by RV GROUP —**

`PKassist` is a full-control Android AI Assistant designed for **RV GROUP LTD** (`PKYcodes/PKassist`). It executes phone tasks seamlessly in both **Offline** and **Online** modes, with automated GitHub CI/CD versioning and in-app OTA APK updates.

## Key Capabilities

1. **Dual-Engine Intelligence (Offline + Free-Tier Gemini Lite)**
   - **Offline Engine (0 Internet Needed)**: Handles calling contacts/numbers (`Call Rahul`, `9876543210 par call lagao`), sending SMS (`Send SMS to ...`), launching any installed app (`Open WhatsApp`, `YouTube kholo`), typing text on screen (`Type Namaste`), clicking UI buttons (`Click Send`), scrolling, flashlight control, Wi-Fi settings, battery level, and current time.
   - **Online Engine (Gemini Free Tier)**: Uses low-demand, high-quota free-tier models (`gemini-2.5-flash-lite` with automatic fallback to `gemini-2.0-flash-lite` and `gemini-1.5-flash`) via Google AI Studio API to answer complex questions and map natural Hindi/Hinglish/English speech into structured phone actions.

2. **Full Screen & Typing Automation (`PKAssistAccessibilityService`)**
   - Uses Android's `AccessibilityService` to type into any active text box across WhatsApp, Messages, Chrome, or Notes, click buttons by label, scroll screens, and trigger Back/Home/Notifications.

3. **Auto-Version Bump & Auto-Release Download (`PKYcodes/PKassist`)**
   - **GitHub Actions CI/CD (`.github/workflows/release.yml`)**: On every `git push` to `main`, GitHub Actions automatically increments `versionCode` (`100 + run_number`) and `versionName` (`1.0.<run_number>`), compiles the APK, and publishes a new GitHub Release with `PKassist-v1.0.<run_number>.apk`.
   - **In-App Auto-Updater (`UpdateManager.kt`)**: Checks `https://api.github.com/repos/PKYcodes/PKassist/releases/latest` on app launch, fires a system notification when a newer version is available, downloads the APK via `DownloadManager`, and launches the Android package installer.

## Quick Setup & Push to GitHub

1. Extract `PKassist-Android-Repo.zip` on your computer.
2. Run the included push script (or standard git commands):
   ```bash
   chmod +x push_to_github.sh
   ./push_to_github.sh
   ```
3. *(Optional)* In your GitHub repo **Settings → Secrets and variables → Actions**, add `GEMINI_API_KEY` to pre-bundle your free Google AI Studio key, or enter the key directly inside the app via the **API Key** button.
