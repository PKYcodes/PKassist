# PKassist v2.0 Pro — Floating Background AI Assistant

**— powered by RV GROUP —**

### What's Fixed & Upgraded in v2.0 Pro

1. **Always-On Floating Popup & Background Service (`PKFloatingService.kt`)**
   - Tap **Start Popup** inside the app to launch a draggable **PK** floating bubble that stays over WhatsApp, Instagram, YouTube, Chrome, and the Homescreen.
   - Tapping the bubble opens the **PKassist Live Popup** with Mic (🎤) and command input. Before executing screen taps or typing, the popup automatically minimizes so your command acts directly on the app underneath!

2. **Multi-Step & Hinglish NLU Engine (`CommandProcessor.kt`)**
   - **WhatsApp Direct Automation**: `"Rajesh ko WhatsApp per message karo hay likhkar"` or `"Open WhatsApp and search Rajesh"` — opens WhatsApp, searches the contact or deep-links to their phone number, types the message, and taps Send via Accessibility.
   - **Alarms**: `"alarm lagao 2:40"` or `"set alarm for 6:30 am"` — directly sets the system clock alarm via `AlarmClock.ACTION_SET_ALARM`.
   - **Fuzzy Screen Tap / Click**: `"tap on sand button"`, `"send button per tap karo"`, `"click search"` — strips filler words (`on`, `button`, `per`, `par`, `ko`), fixes phonetic typos (`sand` -> `send`), and clicks matching text or icon `contentDescription`.
   - **YouTube, Google, Volume, Torch, Screenshot**: `"YouTube par Arijit Singh chala do"`, `"volume badhao"`, `"screenshot lo"`, `"torch on"`.
   - **Keyless Free Online AI Fallback**: Works online even without an `AIzaSy...` key, while supporting your Google AI Studio key for Gemini 2.5 Flash-Lite.

3. **Signed Release APK in GitHub Actions (`.github/workflows/release.yml`)**
   - Generates a V1+V2 signed release keystore (`assembleRelease`) and removes raw `SEND_SMS` trojan-flagged permissions to avoid Google Play Protect blocks.
