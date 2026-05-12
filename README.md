# Mythara

> Field intelligence in your pocket.

Voice-first Android AI assistant. **Crush-styled** terminal aesthetic. Runs your agent loop on **MiniMax** with a key you paste in settings — no backend, no telemetry. Two phone-control modes: **Assistive** (read-and-suggest) and **Automation** (taps, swipes, opens apps, sends SMS, places calls) — switchable in-app, with per-call confirmation by default.

Plus a hidden **Observe** mode behind a triple-tap and a password: continuous on-device speech recognition, learnings extracted by a local LLM, raw audio and transcripts auto-purged so only durable learnings accumulate over time as evolving assistant memory.

---

## Status

**Pre-alpha.** M0–M1 shipped:
- `[x]` Repo skeleton + Gradle 8.10 + Kotlin 2.0 + Compose BOM
- `[x]` Charmtone Pantera palette + JetBrains Mono + glyph alphabet
- `[x]` MYTHARA wordmark with Charple→Bok gradient + thinking shimmer
- `[x]` Voice-first chat scaffold (mic button placeholder)

Upcoming:
- `[ ]` M2 — MiniMax client + streaming chat + settings screen
- `[ ]` M3 — Push-to-talk + native TTS
- `[ ]` M4 — Permission onboarding + release keystore
- `[ ]` M5 — Assistive tools (Accessibility, notifications, camera)
- `[ ]` M6 — Automation tools (tap/swipe/type/open_app)
- `[ ]` M7 — Communication (SMS, calls, calendar)
- `[ ]` M8 — Secret Observe mode (Vosk + local LLM extractor + encrypted vault)
- `[ ]` M9 — Polish + signed APK release

---

## Distribution

**Sideload only.** Mythara uses Android's Accessibility Service for automation, which Google Play's January 2026 policy enforcement explicitly bans for non-accessibility apps. We don't submit to Play.

Signed APKs land on the GitHub Releases page from M9 onward. See [docs/SIDELOAD.md](docs/SIDELOAD.md) for install instructions.

---

## Build (dev)

Prerequisites (Homebrew on macOS):

```bash
brew install openjdk@17
brew install --cask android-commandlinetools
brew install gradle

# SDK + platform 36
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses
sdkmanager --install "platforms;android-36" "build-tools;36.0.0" "platform-tools"

# Build
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk

# Install on a USB-connected device
./gradlew installDebug
```

---

## Architecture (target)

```
Voice mic     ┐
              ├──► Agent loop ──► MiniMax (Bearer + SSE)
Camera frame  │      ▲                │
              │      │     tool_calls │
              │  ┌───┴────────────────▼───┐
              └──┤ Tool Registry          │
                 │  • read_screen         │
                 │  • tap / swipe / type  │
                 │  • open_app, list_apps │
                 │  • read_notifications  │
                 │  • take_photo          │
                 │  • send_sms / call     │
                 │  …                     │
                 └────────────────────────┘
                              ▲
                              │ confirmation
                              │ allowlist
                          ┌───┴────┐
                          │ Gate   │
                          └────────┘
```

**On-device, local-only:**
- Push-to-talk ASR via Android `SpeechRecognizer`
- Speech-out via Android `TextToSpeech` (MiniMax T2A optional)
- Chat history via Room (no cloud sync)
- API key via DataStore + Tink AEAD (key in Android Keystore)
- Observe mode (M8): Vosk ASR + MediaPipe Gemma-2-2B extractor + SQLCipher learning vault — **never** leaves the device.

---

## Privacy

The privacy posture is: no backend, no telemetry, no analytics, no crash reporters. The only network calls Mythara makes are to your MiniMax endpoint. See [docs/PRIVACY.md](docs/PRIVACY.md) for the full disclosure, especially around Observe mode.

---

## License

(TBD)
