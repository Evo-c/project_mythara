# Sideloading Mythara

Mythara isn't on the Play Store — Google's January 2026 policy update bans non-accessibility apps from using `AccessibilityService`, and Mythara's automation mode depends on it. Sideload installation is the only supported path.

## Step 1 — download the APK

Latest signed release: see the [Releases](https://github.com/lumilyra2026-ces/project_mythara/releases) page on GitHub. Pick the `.apk` for your CPU architecture (most modern Android phones are `arm64-v8a`).

## Step 2 — enable "Install unknown apps" for your browser / file manager

1. Open **Settings → Apps → Special app access → Install unknown apps**.
2. Find the app you used to download the APK (Chrome, Firefox, Files, etc.).
3. Toggle **Allow from this source**.

## Step 3 — install

Open the downloaded APK. Android shows a one-time prompt summarising the permissions Mythara declares. Tap **Install**, then **Open**.

## Step 4 — first-run onboarding

Mythara walks through every permission it needs, with a brief rationale per item:

- **Microphone** — push-to-talk + Observe mode
- **Camera** — vision queries
- **Notifications post** — needed for the foreground service status
- **Accessibility (deep link to Settings)** — automation mode + read-screen
- **Notification Listener (deep link to Settings)** — read incoming notifications
- **SMS, Phone, Contacts, Calendar, Location** — gated tools (opt-in)

You can skip any optional permission and grant it later from Settings → Permissions.

## Step 5 — paste your MiniMax API key

Settings → MiniMax → pick **Global (minimax.io)** or **China (minimaxi.com)**, paste your key, tap **Validate**. You should see "Key OK · Model: MiniMax-M2".

If you get **Key rejected**, double-check you copied from the correct region's dashboard.

---

## Updating

Each new release is a new signed APK on the Releases page. Download and install — Android keeps your data because the package id matches.

## Uninstalling

`Settings → Apps → Mythara → Uninstall`. This wipes all on-device data (chat history, settings, Observe vault).
