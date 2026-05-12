# Mythara — privacy disclosure

Mythara is a personal AI assistant. Its privacy posture is **local-only by default**.

## What leaves the device

Only your **chat turns** (text + optional images), sent to the MiniMax API endpoint you configured in Settings. Mythara has **no Mythara-operated backend**, no analytics, no crash reporting, no telemetry.

The API key you paste is stored on the device, encrypted at rest using AES-GCM (Tink AEAD) with the wrapping key held in the Android Keystore. Mythara never transmits the key anywhere except in the `Authorization` header of API calls to MiniMax.

## What stays on the device

- **Chat history** — Room database in the app's private storage. Not backed up to Google Drive (see `data_extraction_rules.xml`).
- **Settings** — DataStore preferences in the app's private storage.
- **Allowlist for automation tools** — same DataStore.
- **Observe mode vault** (Secret mode only) — SQLCipher-encrypted database. Holds extracted "learnings"; raw audio and transcripts are auto-deleted after processing.

## Observe mode (Secret)

Observe mode is a hidden feature accessible by triple-tapping the app name in the About screen and entering a separate password. When enabled, it captures audio through the microphone continuously, transcribes it with an **on-device** ASR model (Vosk), extracts durable learnings using an **on-device** LLM (MediaPipe Gemma-2-2B), and **deletes the raw audio within 60 seconds** of transcription and the raw transcript within 24 hours. Only the structured learnings persist.

### Hard rules baked into code

- **Observe audio and transcripts never leave the device.** Mythara's network interceptor refuses any request whose payload references the `observe/` path; a CI test asserts this invariant.
- **The foreground service notification is mandatory.** Android forces foreground services to display a persistent notification; Mythara complies. The notification text reads "Mythara is running" — neutral, but unhidable.
- **"Forget everything" purges immediately.** The secret settings panel has a button that wipes the vault, pending audio, pending transcripts, and learning embeddings in one transaction.

### Legal disclaimer (jurisdictional)

Recording laws vary. The United States uses **one-party consent** in most states but **two-party consent** in California, Washington, Massachusetts, and others. The European Union, UK, and many Asia-Pacific countries require informed consent from all parties. **You are responsible for using Observe mode within the laws of your jurisdiction.** Mythara surfaces a one-time disclaimer on first enable that you must explicitly acknowledge.

## What Mythara does not do

- No advertising identifiers, no ad SDKs.
- No third-party analytics (Firebase, Mixpanel, Amplitude, etc.).
- No crash reporters (Crashlytics, Bugsnag, Sentry, etc.).
- No cloud backup of any app data — `data_extraction_rules.xml` excludes everything.
- No reverse-engineering protection that would phone home if the APK is modified.

## What you can verify

The repository is open in the sense that the binary's behaviour matches the source. Every network call originates from the `com.mythara.minimax` package; grepping for `OkHttpClient` and `Retrofit` will surface every endpoint Mythara contacts. If you find any network call elsewhere, that's a bug — open an issue.
