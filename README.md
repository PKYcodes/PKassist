# PKassist v3.0 Ultra — Gemini Glass & Voice Unlock Edition

**— powered by RV GROUP —**

### Extreme Features in v3.0 Ultra

1. **Gemini-Style Glassmorphism Bottom Sheet Popup (`layout_floating_widget.xml` & `PKFloatingService.kt`)**
   - Docked at the bottom of your screen with frosted dark glass (`#EB121622`) and a neon Orange-Purple-Cyan glowing border (`bg_gemini_glass_sheet.xml`) — just like Google Gemini's overlay.
   - Displays live real-time speech transcription (`onPartialResults`) as you speak, plus quick glass chips (`🔓 Unlock`, `👁 Read Screen`, `➤ Tap Send`).

2. **24/7 Background & Lock-Screen Voice Listening**
   - Uses `foregroundServiceType="microphone|specialUse"` + `PARTIAL_WAKE_LOCK` + `FLAG_SHOW_WHEN_LOCKED` so PKassist listens continuously in the background and on the lock screen.
   - Trigger anytime by saying `"Hey PK"`, `"PK"`, or speaking a direct command like `"Unlock phone"`, `"Torch on"`, or `"Rajesh ko WhatsApp karo"`.

3. **Voice PIN / Password Device Unlock (`unlockDeviceWithPin`)**
   - Save your Lock-Screen PIN/Password via the **🔐 PIN** button in the top bar (or speak `"Unlock 1234"` directly).
   - When triggered on a locked phone, PKassist wakes the screen (`FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`), swipes up the lock screen, automatically taps each digit of your PIN on the SystemUI Keyguard keypad (`id/key1`, `id/key2`, etc.) or fills the password field, and taps Enter to unlock the device hands-free!
