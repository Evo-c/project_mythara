# Project M.Y.T.H.A.R.A — wiki

> **M**ind **Y**oked **T**onal **H**aptic **A**daptive **R**esonant **A**ssistant

> An open-source, local-first, fully-private **agentic AI mobile OS layer** for Android — an alternative to Google's "Android is now an intelligence system" pitch and the always-on cloud-Gemini posture of Android 17.

![Mythara — Home · Alerts · People · Appearance](https://raw.githubusercontent.com/ankurCES/project_mythara/main/docs/preview/hero.png)

Pick a section below — or start with [Why Mythara](Why-Mythara) if you're new.

## What the backronym means

| Letter | Word | Design constraint baked into the runtime |
|---|---|---|
| **M** | **Mind** | Agent works on YOUR mental model — local Big Five + values + preferences derived from your real conversations. |
| **Y** | **Yoked** | Harnessed to your phone via 65+ tools (calls, SMS, WhatsApp, calendar, alarms, shell, Termux, face recognition). Perceives, decides, acts through hardware you own. |
| **T** | **Tonal** | Tone-aware rendering — markdown-rich text, theme-driven colour, Music Mode that voices replies in a constructed OM-harmonic language with grammar particles. |
| **H** | **Haptic** | Rose-amulet push-to-talk, particle-bloom anticipation on long-press, edge-glow feedback. Touch is the primary input. |
| **A** | **Adaptive** | Skill suggester learns repeated tool chains; context-budget guard summarises history; plan executor decomposes long tasks. Grows around how you use it. |
| **R** | **Resonant** | Brand mark (geometric rose) breathes with your HR via Health Connect. Live Wallpaper + Watch Face + in-app amulet share the same pulse. |
| **A** | **Assistant** | One app, not a fragmented dashboard. Sideload it; everything else is configuration. |

## Tour

- **[Why Mythara](Why-Mythara)** — the Android 17 framing and the private-local stance
- **[Architecture](Architecture)** — chat → runner → loop → tools, with the on-device analytics, memory, and model layers explained
- **[Agentic Runtime](Agentic-Runtime)** — the agent loop, tools, plan-executor, skill-suggester, hooks, context-budget guard
- **[Local-First Memory & Personality](Local-First-Memory-and-Personality)** — Big Five, face index, graph, classifier-cleanup, cross-device sync model
- **[Privacy Model](Privacy-Model)** — what's stored, what syncs, what never leaves the device, how to wipe
- **[Build & Install](Build-and-Install)** — clone, gradle, adb, first-run permissions
- **[Bring Your Own Model](Bring-Your-Own-Model)** — swap MiniMax for Gemma Nano / Llama / Qwen / DeepSeek
- **[Design Language & Skins](Design-Language-and-Skins)** — Spatial / Aurora Glass / Living Rose / Holographic HUD; brightness modes; terminal mode
- **[Mobile UX Patterns](Mobile-UX-Patterns)** — rose-amulet PTT, spine launcher, alerts hub, pickup-only camera, BAL-exempted notification launch
- **[Contributing](Contributing)** — concrete contribution paths ranked by impact
- **[Roadmap](Roadmap)** — what's next + what's wanted from the community
- **[FAQ](FAQ)** — including the inevitable "but why not just use Gemini?"
- **[Glossary](Glossary)** — terms, acronyms, file paths

## TL;DR

| | Android 17 / Aluminium OS | Mythara |
|---|---|---|
| Brand | "Intelligence system" | "OS layer you sideload" |
| Data | Goes to Google | Stays on your device |
| Model | Gemini, always-on cloud | BYO (MiniMax cheap, local-LLM target) |
| Tools | Built by Google | Built by you, audited by you |
| Memory | Their cloud | Your phone (+ optional sync via *your* GitHub repo) |
| Comments | Disabled on the keynote | Issues + PRs welcome |

## Built by

[@ankurCES](https://github.com/ankurCES) — engineered using **Lumi**, the multi-agent platform Ankur built at [CES](https://www.ces.tech).

## License

MIT — fork it, ship it, build a private Pixel.
