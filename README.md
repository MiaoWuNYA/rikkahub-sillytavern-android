<div align="center">

# RikkaHub Plus · HuaDeng

### An **AI chat client** for Android that is also a **SillyTavern**-compatible endpoint

**Chat out of the box · prefix caching saves tokens · memory that doesn't fade · character cards / lorebooks / presets imported with official SillyTavern semantics**

[**English**](README.md) | [**简体中文**](README_CN.md) | [Divergence map](DIVERGENCE.md) | [Divergence map (EN)](DIVERGENCE_EN.md)

[![Release](https://img.shields.io/github/v/release/MiaoWuNYA/rikkahub-sillytavern-android?label=release&color=brightgreen)](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/releases)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/releases)
[![Stars](https://img.shields.io/github/stars/MiaoWuNYA/rikkahub-sillytavern-android?color=yellow)](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/stargazers)

</div>

> **A deeply customized fork of [RikkaHub](https://github.com/rikkahub/rikkahub)** (merged with upstream v2.5.4, plus 2150+ commits of additions), natively implemented in Kotlin + Jetpack Compose.
>
> - **Just want a good AI chat app**: plug in any API and start talking — multi-provider, streaming, voice calls, plugin tools, memory, cache savings. No "tavern" concepts required.
> - **Migrating from desktop SillyTavern**: character cards, lorebooks, presets, regex and themes import losslessly with official semantics — no Termux, no Node.js.
>
> Both routes share the same client and don't interfere with each other.
>


---

### Tavern compatibility

| Character cards (V3 card + 62-entry embedded lorebook) | Macro engine (60+ official macros) |
|---|---|
| <img src="docs/screenshots-en/tavern-cards.jpg" width="280" alt="Cards"> | <img src="docs/screenshots-en/macros.jpg" width="280" alt="Macros"> |

### Memory & caching

| Multi-dimensional memory settings | Prefix divergence diagnostics (character-level diff) |
|---|---|
| <img src="docs/screenshots-en/memory.jpg" width="280" alt="Memory"> | <img src="docs/screenshots-en/cache-diagnostics.jpg" width="280" alt="Cache diagnostics"> |

### This fork's exclusives

| HuaDeng Settings (compatibility & helpers) | HuaDeng Settings (access & automation) | Jev decisioning |
|---|---|---|
| <img src="docs/screenshots-en/huadeng-toggles.jpg" width="280" alt="HuaDeng toggles"> | <img src="docs/screenshots-en/huadeng-integrations.jpg" width="280" alt="HuaDeng integrations"> | <img src="docs/screenshots-en/jev.jpg" width="280" alt="Jev"> |

| Plugin system |
|---|
| <img src="docs/screenshots-en/plugins.jpg" width="280" alt="Plugins"> |

---

## ✨ Fork features

| | Area | In one line |
|---|---|---|
| ⚡ | **Prompt cache optimization** | Removes per-turn context divergence — long-conversation token cost drops sharply |
| 🧠 | **Memory & long conversations** | Semantic RAG + three-layer memory + rolling compression |
| 🧿 | **Jev decisioning** | Mounts the TypeSafe System One judge model: memory screening + quick Q&A for the main model |
| 🧩 | **Plugin system** | QuickJS-sandboxed plugins, ZIP import, directly callable by the AI |
| 🍺 | **Deep SillyTavern compatibility** | Cards / lorebooks / presets / regex / QR / themes, lossless with official semantics |
| 🗼 | **Proxy-station compatibility** | Auto-fixes the three classic Gemini-via-OpenAI-proxy pathologies |
| 🔊 | **Doubao voice** | TTS 2.0 + Volcengine ASR; one Agent Plan key powers voice/video calls |
| 📱 | **On-device augmentation** | 30 device tools (lazy-discovery), WeChat/QQ Bots, AI proactive messaging |
| 🏮 | **HuaDeng Settings** | One page collecting this fork's new switches |
| 🎨 | **Appearance & themes** | 7 custom colors + SillyTavern theme import |
| 🛠 | **Skills & tools** | GitHub one-click skill install, 20 new tools, DSML text tool-call compat |
| 💞 | **Companionship** | Couples space, life hub (cycle / memo / calendar / music / reading) |
| 🛡 | **Privacy & stability** | Log sanitization, approval boundaries, telemetry off, SSE & DB hardening |

<details>
<summary><b>🗺️ Where this fork sits between its two upstreams (click to expand)</b></summary>

Two upstream lineages meet here — knowing them makes the diff readable:

```
rikkahub/rikkahub (original upstream, v2.5.4)
        │
        ├──► heikeyangle-code/rikkahub-plus (intermediate fork, mingli2)
        │            │  tavern system / macro engine / slash commands / group chats
        │            ▼
        └──► MiaoWuNYA/rikkahub-sillytavern-android  ← this repo
                     │  cache optimization / plugins / memory / proxy fix / on-device
                     │
                     ├─ also from orangechat & Tumin: three-layer memory, couples space,
                     │  life hub, appearance customization, early plugin system
                     └─ also ported from Rikkahub-Revised: semantic memory RAG, rolling compression
```

- **vs. original upstream `rikkahub`**: everything preserved; the tavern/plugin/memory work is purely additive
- **vs. intermediate `heikeyangle-code/rikkahub-plus` (mingli2)**: the tavern core comes from it; this fork adds cache optimization, plugins, memory, proxy fix, voice, and privacy hardening on top
- **vs. other same-origin forks**: see [Credits](#-credits) — feature provenance is stated per project

</details>

---

## 🚀 Quick start

1. Grab the latest APK from [Releases](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/releases/latest) (`arm64-v8a`, Android 8.0+)
2. Settings → Models & Services → **Providers**, add your API; then **Default models & prompts** to pick models for chat / title / compression
3. Start chatting. Want more:
   - **Import a character card** (Assistants page → import, PNG / V2 / V3 JSON; lorebooks and presets come along automatically)
   - **Import lorebooks / presets / regex / QR** (Settings → Extensions)
   - **Try a SillyTavern theme** (Settings → Preferences → Chat appearance → import theme)
   - **Add plugins** (Settings → Plugins → import ZIP; [bundled plugins](#-bundled-plugins) work out of the box)

> In-app update: Settings → About → Check for updates (GitHub Releases API, with an auto-fallback mirror for mainland China)

---

## 💬 Chat features (this fork's core enhancements)

### ⚡ Prompt Prefix Cache Optimization

Major providers (DeepSeek / Kimi / Claude, etc.) offer automatic prefix caching: a byte-identical prefix to the previous request is a cache hit, billed far below the normal rate. But in chat, plenty of content changes **every turn** (timestamps, recent chats, memories, random numbers, rolling summaries) — one divergence kills the whole cache.

Drawing on the DeepSeek Harness prefix-stability design, this fork eliminates those divergence points one by one:

| Divergence point | How it's handled |
|---|---|
| Dynamic injections (recent chats, memory refs) | **Frozen anchors**: old block stays in place, new block appends at the tail |
| `{{random}}` / `{{pick}}` and other random macros | Resolved deterministically per message — history no longer re-rolls |
| Lorebooks / skills / cross-window memory / OCR / regex depth | Frozen per *user turn*, so repeated requests in a turn share a prefix |
| Macro rendering of assistant messages already in history | Frozen per message — no longer varies with time or variables |
| Time-family macros (`{{time}}`, …) | Aligned to 5-minute granularity, avoiding second-level jitter |
| Rolling compression summaries | Append-style chaining keeps the token prefix stable across compressions |
| Full memory injection / Recent Chats | Moved out of the prefix zone (the former to the context tail) |
| Multiple API keys | Sticky per session, so upstream caching isn't invalidated by key switching |
| Claude `redacted_thinking` | Preserved and replayed, keeping the thinking-block structure intact |

**Diagnostics**: a built-in **prefix divergence view** compares consecutive requests item by item, shows the common prefix and estimated hit rate, and supports **character-level diff location** — you can always see exactly why a cache missed. A **prompt viewer** also shows the exact prompt sent this turn.

> Actual results vary by conversation shape: in steady, append-only chats the hit rate improves noticeably; the exact gain depends on how often content changes and on each provider's pricing.

### 🧠 Memory & Long Conversations

- **Semantic memory RAG** (ported from [Rikkahub-Revised](https://github.com/YaeNovin/Rikkahub-Revised)): memories split into FACT / EPISODIC, embedded with the vector model and retrieved via cosine similarity (with a lexical fallback of word terms + CJK bigrams); episodic memories get recency weighting; results are injected within a budget
- **Three-layer memory** (from orangechat/Tumin): a fixed-memory section (independently editable without touching the character card) + a recent-life stream (cross-conversation memory of the current chat; unread events from other conversations auto-injected) + long-term recall via lexical-overlap scoring; the stream is auto-summarized in the background past a threshold
- **Enhanced `memory_tool`**: a `list` read operation plus fact / episodic distinction — the model can review existing memories before writing or updating
- **Memory management page**: view / edit / delete entries per assistant
- **Rolling context compression**: past a threshold (auto-computed from the model's context window or set manually), a compression model rolls earlier turns into a summary (originals preserved, replaced only at request time), injected as a system message — staying in-window without losing persona or foreshadowing
- **Recent chats reference**: optionally inject the assistant's recent conversation list for cross-session continuity
- **Transient-content pruning**: web-search results / images / audio / video older than two turns are dropped from requests (with the message ID so the AI can retrieve the original via `read_history_message`)
- **First-turn auto memory**: the first turn injects the most recent memories (no relevance query exists yet), later turns recall by relevance — the AI knows you from the first message

> Memory features (memory tool / RAG / three-layer memory / cross-window life stream) are **on by default** — memory helps the AI know you better and isn't sacrificed to save context. Existing assistants keep their settings and can opt out per assistant.

### 🧿 Jev decisioning (TypeSafe System One)

Settings → HuaDeng Settings → **Jev decisioning**. Jev is a dedicated small model that **only judges — it never generates text** (~100ms end to end, output tokens free). It mounts as an invisible decision layer inside the client: paste an API key and it works. Two takeover switches:

| Switch | What it does |
|---|---|
| **Automatic memory screening** | Instead of embedding-similarity retrieval, Jev judges each memory "is this relevant to the current conversation?" — no embedding API calls. Candidates are batched in parallel newest-first; anything over 50% relevance probability is injected; identity memories (what to call you, preferences) count as relevant even when not mentioned |
| **judge tool for the main model** | Gives the main model a `judge` tool (shown as "Jev 明断" in the UI): when unsure about a yes/no, multiple-choice, or rating question it asks Jev instead of guessing. A tool-routing note is injected into the system prompt so weaker models discover it too |

**Silence is a hard requirement**: whenever Jev is unavailable (no key, network failure, low confidence), memory and tools fall back to the original logic — no error dialogs, no blocking.

### 🧩 Plugin System

QuickJS-sandboxed plugins. A plugin is a ZIP package (`manifest.json` + `main.js`); import it in Settings → **Plugins**.

> The plugin system originated in orangechat / Tumin; this fork added the sandbox capabilities tool-style plugins need (session-aware HTTP, image decoding, detail-page cards).

#### What it can do

- `tools` declared in the manifest become AI-callable tools (unified `plugin_` prefix)
- **System-prompt injection**: `systemPrompt` injected directly, or `sections` each gated by a config switch (supports `file:` external files and `enc:` AES-256-GCM encrypted files)
- **Detail-page data cards**: declare `detailCard` and the plugin page calls that exported function and renders a live status card (e.g. calorie "today's intake")
- Folders, enable/disable, per-plugin config forms (text / password / boolean / select / **model picker**)
- Per-plugin `dataStore` key-value storage under `plugin_data_<id>`

#### Sandbox capabilities

| API | Purpose |
|---|---|
| `fetch(url, options)` | One-shot request, **no cookie persistence**, fail-closed host allowlist |
| **`http.*`** | Session-aware requests with a **built-in cookie jar** (saves each hop's `Set-Cookie`), binary-capable via `bytes()` / `base64()`. Built for "get a session, then log in with it" flows — academic systems, forums |
| **`image.decode(data)`** | Image → RGBA pixel array (same layout as `canvas.getImageData`). There is no canvas in the sandbox, so per-pixel work (captcha OCR, color picking) relies on it |
| `dataStore` | `set` / `get` / `del` / `list` |
| `btoa` / `atob` / `TextEncoder` / `TextDecoder` | Encoding helpers |
| `config` | Global object holding the plugin's config |

Single-threaded; 30s tool timeout, 8s detail-card timeout. **ES5 only** (`async`/`await` are preprocessed into synchronous calls).

#### Security

- Two-stage import: parse and preview the manifest, then commit
- **SHA-256 integrity check** (`.integrity`) in the plugin directory — a modified plugin is disabled automatically
- **Zip-Slip protection**: every entry's canonical path is verified on extraction
- Empty `allowedHosts` = all network requests denied
- Plugins reach only the explicitly injected bridge objects, never host capabilities

> ⚠️ Plugins are third-party code. Only import sources you trust.

#### Bundled plugins

| Plugin | Description |
|---|---|
| 🍱 **Calories & Protein** | Say "I had a bowl of beef noodles for lunch" and both the calories and the protein are recorded. Daily targets are set in plugin settings; the detail page shows today's intake and each remaining budget |
| 🌍 **AI Model World** | Profiles and benchmark scores of 600+ LLMs (data from liyupi/ai-model-world, hourly sync of Epoch AI / models.dev / LiveBench). Ask "how much does model X cost / how long is its context / is it stronger than Y / what are the cheap models" and the AI answers from the local database; one-tap data refresh |
| 🎓 **YNUFE Academic System** | Timetable / grades / exam schedule / notices / empty classrooms for Yunnan University of Finance and Economics. Credentials live in plugin settings; captchas are OCR'd locally, with an image fallback for manual entry |

All packages (source included) are in [`docs/plugins/`](docs/plugins/); see [docs/PLUGINS_GUIDE.md](docs/PLUGINS_GUIDE.md) for the development guide.

<details>
<summary><b>✍️ Minimal plugin example (click to expand)</b></summary>

`manifest.json`:
```json
{
  "id": "com.example.hello",
  "name": "Say hello",
  "version": "1.0.0",
  "entry": "main.js",
  "tools": [{
    "name": "hello",
    "description": "Greet someone by name",
    "parameters": [{ "name": "who", "type": "string", "required": true }]
  }],
  "allowedHosts": []
}
```

`main.js`:
```js
exports.hello = function (args) {
  var name = args && args.who ? args.who : 'world';
  return { greeting: 'Hello, ' + name + '!' };
};
```

Zip it (files must sit at the root — **no top-level folder**) and import.

</details>

### 🗼 Proxy-Station Compatibility & Anti-Empty-Reply

Classic pathologies when Gemini is accessed through OpenAI-compatible proxy stations (newapi etc.), fixed automatically:

- **Body swallowed by `reasoning_content`**: some proxies put the actual reply into the reasoning field, leaving artifacts in `content` — after the stream ends, if the body is empty while reasoning has substance, the reasoning is promoted to the body (no false positives during the thinking phase)
- **`response` prefix artifacts**: stray `response` / `Response:` leftovers at the start of the body — stripped on both streaming and non-streaming paths, even when split across deltas; boundary checks avoid mangling words like `responses`
- **Truncation**: promotion + stripping present the reply in full instead of "answer in the thinking block, half a reply in the body"

**Anti-empty-reply (global)**:

- **System prompt into the conversation flow**: SYSTEM messages convert in place to user turns (with an acknowledgment turn after the first); lorebook / persona / rolling-summary positions and content stay untouched — bypassing Gemini's safety blocking of `systemInstruction`
- **Auto-perturb retry**: on a reply with no text and no tool calls, the last user message is perturbed (add/remove periods, add space — 4 rotating variants) and retried up to 3 times

Both are off by default; enable in HuaDeng Settings or per assistant.

### 🔊 Doubao Voice (Volcengine Agent Plan)

- **Doubao TTS**: Doubao speech-synthesis large model 2.0
  - `seed-tts-2.0` resource with **14 built-in 2.0 voice presets** (default Cancan 2.0, plus Kuaile Xiaodong, Tianmei Taozi, Gaoleng Yujie, …); any voice ID can be typed manually
  - Speech-rate control; audio format (mp3/wav/pcm/ogg/opus) and sample rate selectable
  - Fully parses Volcengine's concatenated-JSON chunked streaming responses; long-audio synthesis verified
  - The Agent Plan dedicated endpoint is built in as the default; standard-console users can switch back
- **Volcengine ASR**: Agent Plan `ark-xxx` keys are only valid on the dedicated `/api/v3/plan/` path — the default URL now points there; standard-console users can change it back in settings
- **One Agent Plan API key** drives both voice input and voice output

> Pairs with voice / video calls: one tap on the chat top bar enters the call screen (shown only when both TTS and ASR are configured) — local voice-activity detection → ASR incremental transcription → auto-send → streaming TTS of the reply, interruptible at any time; on hang-up the call folds into an archive card in the conversation.

### 📱 On-Device Augmentation

#### Device toolbox (30 tools)

Enable it on an assistant and the AI can call phone system capabilities:

torch · vibrate · volume read/write · brightness read/write · toast · battery · storage · Wi-Fi / audio / telephony / sensor info · share · wallpaper · notifications · alarm / timer · music control · SMS reading · contacts · call log · location · app launching · open URL · media scanning · file download · open file

To save tokens it uses a **lazy-discovery meta-tool**: only one `device_toolbox` slot is registered in context. The AI calls `action=list` to fetch the catalog (parameter schemas + permission status), then `action=run` to invoke a specific tool.

#### WeChat Bot / QQ Bot

Connect an existing assistant to a messaging channel (AI, memories, and tools all reused from that assistant):

- **WeChat Bot**: QR-login with your own WeChat account (iLink protocol); HTTP long-polling receives messages → auto reply; an expired token stops the service with a notification
- **QQ Bot**: official QQ Open Platform API — enter AppID + AppSecret; WebSocket gateway for real-time send/receive with automatic token refresh

Both are off by default with a privacy risk-confirmation dialog before enabling.

#### AI proactive messaging

AlarmManager exact alarms + WorkManager fallback; the assistant reaches out at random intervals (configurable range). Generation injects context (time since last chat, current time, battery) and politely skips a trigger while a generation is running. Off by default.

### 🎨 Appearance & Themes

- **Color overrides**: 7 custom colors (primary, global text, user / AI / thinking bubbles, chat background, input field; ARGB)
- **Bubble beautification**: user / AI bubble background images + corner radius + theme-color overlay; drawer background image and **chat background image** (tinted with the chat background color)
- **Fine-grained text colors**: separate body / quote / italics colors, with Tavern-orange, warm-gold, rose, coral, lavender, sky-blue and mint presets
- **SillyTavern theme import**: one-tap import of SillyTavern beautification theme JSONs, bulk-verified against **537 real themes** (537/537 parse successfully):
  - **Layered color compositing**: theme tints (`blur_tint` → `chat_tint` → message bubble tints → background image) are composited source-over into opaque approximations following SillyTavern's render stack, so transparent-bubble themes no longer collapse into flat color blocks; 8-digit hex parsed per CSS spec as `#RRGGBBAA`
  - **Background image**: `background-image` on `#bg1` / `body` / `#chat` detected from `custom_css` (URLs auto-downloaded, data URIs decoded)
  - **Bubble styling**: `border-radius` on `.mes` / `#chat` maps to bubble corner radius; `chat_display=1` (bubble mode) enables assistant bubbles automatically
  - **Theme fonts**: ttf/otf from `@font-face` are auto-downloaded and set as the chat font (woff/woff2 ignored)
  - Also: `main_text_color` → global text, `quote/italics_text_color` → quote/italics colors, `font_scale` → font scale
- Preset palettes + HCT custom themes + dynamic color remain unchanged

### 🛠 Skills & Tools

#### Skills

- **Automatic triggering**: matching keywords inject `SKILL.md` without relying on the model
- **Public skills directory** `/Rikkahub/skills`: add or remove via any file manager
- **GitHub one-click install / batch download / update detection**: subdirectories and multi-skill repos supported, with source + directory-hash tracking

#### Tools

New on top of upstream:

| Tool | Description |
|---|---|
| `file` | Unified file tool: read / write / patch / list / search / copy / move / mkdir / delete |
| `execute_command` | Shell command on device; returns stdout/stderr/exit code |
| `execute_python` | Isolated on-device Python execution (Chaquopy) |
| `calculator` | 700+ function calculator (statistics / finance / matrix / calculus / physics) |
| `database_query` | Read-only SQLite query against the local database |
| `task_*` | Task list creation / lookup / update / team orchestration |
| `web_fetch` | HTTP requests to any URL (GET/POST/PUT/PATCH/DELETE) |
| `present_file` | Share a file via the system share sheet |
| `read_history_message` | Retrieve the original text of pruned messages (registered when pruning is on) |
| `device_toolbox` | Lazy-discovery entry point to the 30 device tools |
| `life_*` / `shared_*` / `*_couple_space` | Life hub and couples space operations |
| `memory_tool` / `conversation_search` / `recent_chats` | Memory and conversation retrieval |
| `judge` | Ask the Jev judge model a yes/no / multiple-choice / rating question (see [Jev decisioning](#-jev-decisioning-typesafe-system-one)) |

There is also a **system-prompt assembler** (tool-selection guide / work ethics).

#### Tool-calling compatibility

- **DSML text tool-call compat**: when a model writes tool calls into the message body instead of using function calling, they are parsed and executed, and the markers are stripped from the body
- **Tool alias mapping**: DSML-idiomatic names like `web_search` are mapped to the actually-registered name (`search_web`)
- **Chinese tool display names**: tool calls always show a Chinese name in the UI (`web_search` → 联网搜索, `execute_python` → 灵枢演算)

### 💞 Companionship

Hidable in one tap via **HuaDeng Settings → Clean simple mode**, which also stops registering the related tools.

- **Couples space**: bind a partner to unlock the "Rabbit's Burrow" feed (both sides post, and the AI genuinely replies in the comments), "Our Diary", and "Anniversaries"
- **Life hub**: six panels — Today / Cycle & Body / Memos / Calendar reminders / Listen together / Shared reading shelf — which the AI can participate in via tools

---

## 🍺 SillyTavern compatibility (aligned with official semantics)

**Existing tavern assets migrate directly** — parsed with official SillyTavern semantics rather than an approximate conversion:

| Asset type | Compatibility |
|---|---|
| **Character cards** (PNG / V2 / V3 JSON) | **20+ fields** (upstream keeps only 6): example messages, alternate greetings, multilingual notes, post-history instructions, character version, tags, nickname, assets, embedded lorebook, raw `extensions` JSON… nothing upstream drops is lost — **import → export round-trips without data loss** |
| **Lorebooks** | **30+ entry fields**, aligned rule by rule with official `world-info.js`: four secondary-keyword logic modes, whole-word/regex/case, per-entry scan depth, constant activation, cross-book groups + weight + override, trigger probability, sticky / cooldown, delayed activation, recursion controls, budget exemption, character-field matching ×6 |
| **Presets** | Imported under the official prompt-manager structure |
| **Regex scripts** | Find / Replace / `_ALT`, OnlyFormat, macros, injection depth (minDepth/maxDepth), ordering and caching — applied at both the display and prompt layers |
| **Quick Replies** (QR) | QR sets import; run from the slash popup in one tap |
| **Beautification themes** | **Verified against 537 real themes** (537/537 parse): layered color compositing, `custom_css` backgrounds, bubble corner radius, theme fonts |
| **HTML display cards** | Rendered in-chat, expanded by default with tap-to-fullscreen |
| **Multiple greetings** | Full `alternate_greetings` import with in-chat switching |

**The prompt pipeline follows the official structure too**: main prompt, standalone character-field messages, example messages split on `<START>` into real user/assistant turns, PHI appended after history, depth prompts injected at their configured depth and role.

### Differences from other Android tavern projects

Searching "Android SillyTavern" surfaces mostly **containers or launchers** — projects that bundle Node.js with SillyTavern and run it (their descriptions typically use terms such as *runner*, *launcher*, *container*, *installer*, *local Node.js server*).

This project is a **native implementation**: a complete Kotlin + Jetpack Compose client in which the tavern compatibility layer is core code rather than an external wrapper. The differences:

- **No Node service to run** — no background process holding memory, faster cold start
- **Tavern features interoperate with client features**: a card's lorebook can drive plugin tools, the memory system, TTS read-aloud, and the device toolbox
- **A complete mobile experience**: Material You theming, gestures, notifications, the share sheet

### It also addresses two common tavern pain points

- ⚡ **Long chats get expensive** → [Prompt prefix cache optimization](#-prompt-prefix-cache-optimization) removes per-turn divergence points
- 🧠 **The AI forgets / the context blows up** → [Memory & long conversations](#-memory--long-conversations): semantic RAG + three-layer memory + rolling compression

> The tavern core (character-card structure, lorebook engine, Macro Engine 2.0, slash commands, group chats) comes from the `mingli2` branch of the intermediate fork [heikeyangle-code/rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus).
> This branch (huadeng) **adds on top**: HTML card rendering, multiple-greetings import, preset & regex import, QR import, lorebook editor field completion + token-budget fallback, Vector Storage semantic entries, a prompt viewer, regex depth limits & caching, and greeting macro substitution.

<details>
<summary><b>📖 Tavern system in detail (click to expand)</b></summary>

#### 1. Character Cards: import → structure → inject → export → edit

- **Field coverage grows from 6 (upstream) to 20+**: example messages, alternate greetings, multilingual creator notes, post-history instructions, character version, tags, nickname, assets, group_only_greetings, creation/modification dates, embedded lorebook, raw `extensions` JSON (including depth-prompt depth/role) — what upstream drops, this fork keeps. **Import → export round-trips without data loss.**
- **Official Chat Completion injection structure**: main prompt, standalone character-field messages, example messages split on `<START>` into real user/assistant turns, PHI appended after history, depth prompts injected at their configured depth/role
- **V2 / V3 dual version**: V3 advanced fields and ccv3 PNG cards; non-PNG cards auto-convert to PNG with macro substitution
- **Visual character-card editor**: all fields + embedded-lorebook management + one-tap export (JSON / PNG embed)
- **Multiple greetings**: full `alternate_greetings` import with in-chat switching
- **HTML cards**: SillyTavern HTML display cards render in-chat, expanded by default with tap-to-fullscreen

#### 2. Lorebooks

Aligned rule by rule with the official `world-info.js`; entry fields grow from 6 to 30+:

| Capability | Official counterpart |
|---|---|
| Four secondary-keyword logic modes | `selective_logic` |
| Whole-word / regex / case sensitivity | `match_whole_words` / `key_regex` / `key_case_sensitive` |
| Per-entry scan depth | `scan_depth` |
| Constant activation | `constant` |
| Cross-book groups + weight + override | `group` / `group_weight` / `group_override` |
| Trigger probability | `probability` / `use_probability` |
| Sticky / cooldown | `sticky` / `cooldown` |
| Delayed activation | `extensions.delay` |
| Recursion controls | `exclude_recursion` / `prevent_recursion` / `delay_until_recursion` |
| Budget exemption | `extensions.ignore_budget` |
| Character-field matching ×6 | `extensions.match_*` |
| Display order / generation filter / triggers | `display_index` / `display_position` / `triggers` |

**Scan engine**: the full official `checkWorldInfo` state machine (INITIAL → recursion / min-activations / delay-level loop) with budget, overflow, sticky and cooldown lifecycles; cross-book groups elect a single entry per official rules (sticky → keyword score → group override → weighted random).

**Lorebook editor**: global settings (scan depth, token budget + absolute cap, min activations + max depth, recursion + step cap, insertion strategy, overflow alert, group scoring), full per-entry editing, drag-to-reorder, two-way external/embedded sync, Vector Storage semantic entries.

#### 3. Presets / Regex / Themes

- **Preset import**: SillyTavern JSON presets imported under the official prompt-manager structure
- **Regex script import**: Find/Replace/`_ALT`, OnlyFormat, macros, injection depth (minDepth/maxDepth), ordering and caching — applied at both display and prompt layers
- **Theme import**: see [Appearance & Themes](#-appearance--themes)

#### 4. Quick Replies

SillyTavern QR sets import; run from the slash popup in one tap.

#### 5. Macro Engine 2.0

- **Variables**: `{{setvar}}` `{{getvar}}`, the `{{.var}}` shorthand family, global + per-conversation persistence — cards can remember story state
- **Conditionals**: `{{if}} / {{else}}`, comparison operators, `&&` / `||`, scoped blocks, nesting
- **Random & time**: `{{pick}}` (stable within a turn), `{{roll::1d20}}`, `{{random}}`, `{{time}}`, `{{datetimeformat}}`
- **Conversation-aware**: `{{lastUserMessage}}` `{{lastCharMessage}}` `{{idleDuration}}` `{{charFirstMessage::N}}` `{{original}}` — **60+ official macros** supported
- Unknown macros pass through untouched

#### 6. Slash Commands

Type them in the input box; `/help` lists everything with descriptions. **20+ built-in commands**:

- **Roleplay**: `/impersonate`, `/continue`, `/sendas`, `/sys`, `/sysgen`, `/trigger`, `/message-name`, `/delname`
- **Variables & random**: `/listvar` `/setvar` `/getvar` `/addvar` `/incvar` `/decvar` `/flushvar` `/reroll-pick`
- **Character management**: `/char-update` `/char-duplicate` `/rename-char`
- **Injection**: `/inject` (position/depth/role), `/prompt`
- Skill-provided commands appear automatically in the popup

#### 7. Personas & Author's Note

- **Personas**: official five-position injection (IN_PROMPT / TOP / BOTTOM / AT_DEPTH / NONE), per-character binding, standalone SYSTEM-message injection, one-tap disable
- **Author's note**: official interval semantics (every / every N user messages), injection depth & role, master switch

#### 8. Group Chats

Multi-character conversations with independent prompts / personas / models per member; 4 speaker-selection strategies (NATURAL AI-picked / list / weighted random / manual) + 5 extended modes; auto-reply (1–10 configurable rounds & delay, interrupted by user messages); live speaker status; full persistence.

</details>

### Switching from Chatbox or another general-purpose AI client?

If you currently use **Chatbox** (or any other general AI chat client) to talk to APIs, this app is a worthwhile upgrade — the fundamentals are fully covered (multi-provider access, streaming, Markdown / LaTeX / syntax highlighting, chat export, data backup), plus an entire layer Chatbox doesn't have:

| Chatbox has | This app adds |
|---|---|
| Multi-provider access, streaming | ✅ Yes, plus **native Anthropic / Gemini protocols** and auto-fix for proxy-station pathologies |
| Chat history | ✅ + **semantic memory RAG / three-layer memory** — the AI remembers you across sessions, no pasting context manually |
| Full context resent every turn, long chats get expensive | ✅ **Prompt prefix cache optimization** — long-conversation token cost drops sharply |
| Single interface | ✅ + **SillyTavern compatibility** (character cards / lorebooks / presets / regex / themes imported with official semantics) |
| No extension mechanism | ✅ **QuickJS plugin system** + 30 device tools + WeChat / QQ Bots |
| Text chat only | ✅ + **voice / video calls** (Doubao TTS 2.0 + Volcengine ASR) |
| Cloud sync | ✅ Data stays **local**, with optional S3 / WebDAV backup |

No "tavern" concepts required as a pure chat client — both routes coexist. Start with it as a plain AI client and import a character card whenever you want.

---

## 🔍 Common needs and where they map

| Need | Corresponding feature |
|---|---|
| **Find a good Android AI chat app** | This project: plug in an API and chat — multi-provider + streaming + voice + memory + plugins |
| **Using Chatbox, want memory / roleplay / lower token cost** | This project — see [Switching from Chatbox](#-switching-from-chatbox-or-another-general-purpose-ai-client) |
| **Cut long-conversation API cost** | Prompt prefix cache optimization — cache hits are billed far below the normal rate |
| **The AI keeps forgetting** | Semantic memory RAG + three-layer memory + rolling compression, with optional Jev memory screening |
| **Use tavern character cards and lorebooks on Android** | Cards (V2/V3/PNG), lorebooks (30+ fields), presets, regex, QR, themes — all imported with official semantics |
| **Find a SillyTavern client for Android** | This project; the tavern compatibility layer is a core module |
| **Migrate desktop tavern data to a phone** | Six asset types import: cards, lorebooks, presets, regex, QR, themes |
| **Mobile tavern performance is insufficient, or Termux setup is undesirable** | Native client — no Termux, no Node.js, just install the APK |
| Connect a self-hosted proxy station or third-party API | OpenAI / Anthropic / Google / DeepSeek compatible; the proxy-fix switch cures the common pathologies |
| Extend the AI with custom tools | QuickJS plugin system — write a `main.js`, zip it, import it |
| Continue conversations from WeChat / QQ | WeChat Bot (QR login), QQ Bot (official API), reusing any assistant's AI and memory |
| Speech output and voice conversation | Doubao TTS 2.0 + Volcengine ASR, powering voice / video calls |
| Let the AI operate the phone | Device toolbox: 30 tools — torch, volume, SMS, contacts, location, notifications… |
| Privacy | Sanitized request logging, tool-approval boundaries, telemetry off by default, all data stays local |

> **Search keywords**: AI chat app, Android AI client, AI assistant, AI memory, prompt cache,
> Android SillyTavern, SillyTavern Android client, mobile SillyTavern, tavern client,
> character card import, lorebook, world info, roleplay AI, AI roleplay, RP client,
> Android LLM chat, AI chat client, RAG memory, OpenAI-compatible proxy,
> Chatbox for Android, Chatbox alternative, Chatbox with memory, Chatbox roleplay,
> Kotlin Jetpack Compose AI app, local AI chat.

---

## 🏮 HuaDeng Settings (fork-exclusive)

Settings → **HuaDeng Settings** collects this fork's compatibility and helper features on one page. Global switches apply to all assistants (per-assistant switches can override).

### Compatibility & helpers

| Switch | Description |
|---|---|
| **Proxy fix** | Auto-fixes the three classic pathologies of Gemini via OpenAI-compatible proxies (off by default) |
| **Anti-empty-reply** | Gemini empty-reply auto-perturb retry + system prompt into the conversation flow (off by default) |
| **Transient-content pruning** | Web-search results, images, audio and video older than two turns stop being sent each request (placeholders carry the message ID) — a large token saving on image- and search-heavy chats |
| **Clean simple mode** | Hides the couples space, life hub and similar entries, and stops registering their tools — cleaner UI, fewer tokens |
| **Rolling context compression** | Compresses early messages into a summary when a chat grows long. Turning it off disables auto-compression (per-assistant switches still work) |
| **Tool result truncation** | Tool output over 32KB is truncated and saved to a file. Try turning it off if a proxy station breaks tool calls |
| **System prompt escaping** | Converts `<` `>` in system messages to HTML entities to bypass proxy WAFs (enable on `upstream_content_rejected`) |

### Access & automation

Five entries: WeChat Bot, QQ Bot, AI proactive messaging, Security settings, and Author's note.

> **Security settings** (Settings → Security): the global tool-call approval policy — **force-confirm all tool calls** (confirm before every execution) or **auto-approve all tool calls** (skip approval; use with caution). They are mutually exclusive. The former is off by default; the latter is on by default.

### Jev decisioning

The configuration page for the TypeSafe System One judge model: API base URL / key / model name, a confidence-threshold slider, and the two takeover switches (automatic memory screening, judge tool). See [Jev decisioning](#-jev-decisioning-typesafe-system-one).

---

## 🛡 Privacy & Stability

### Privacy hardening

- **Sanitized request logging**: request-header allowlist, prompt / schema / binary / credential redaction, secret masking and size caps on error messages
- **Tool approval boundaries**: clipboard / screen-time / shell require approval on every execution; file tools require it for writes; screen-time queries are scope-limited and never expose package names
- **Backup-restore resource budget**: entry counts and per-entry / total decompression sizes are capped against malicious archives
- **Daily file cleanup**: chat attachments and generated images can auto-expire by retention days (off by default)
- **Firebase telemetry off by default**: Google services and Crashlytics only activate with a build property
- **Plugin sandbox**: see [Plugins → Security](#security)

### Stability

- **Foreground-service keep-alive**: generation survives app switching
- **SSE long-connection hardening**: OkHttp 30s PING keep-alive; event-stream requests disable caching and compression so proxies no longer buffer streaming output
- **Smooth database upgrades**: every schema change ships as an explicit migration — existing data upgrades losslessly; migrations are idempotent
- Consistent-snapshot backup import with safe startup recovery, PickVisualMedia image picking, and many fixes

---

## 📦 Download

| Channel | Description |
|---|---|
| **Stable** | Versioned releases (`2.5.4.N`; historical versions used `2.5.4fixN` — installs upgrade over them directly) on [Releases](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/releases) |
| **Nightly** | Actions build twice daily (skipped if no commit in the past 24h) and overwrite the [nightly](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/releases/tag/nightly) prerelease; versions advance by date (`2.5.4.YYYYMMDD`) |
| **Manual builds** | Every push produces an APK artifact on [Actions](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/actions) (build page → Artifacts → `rikkahub-plus-fresh`; sign-in required) |
| **In-app update** | Settings → About → Check for updates (GitHub Releases API, with a mainland-China-reachable mirror fallback) |

> Only a single `arm64-v8a` APK is published, supporting Android 8.0 (API 26) and above.

---

## ✅ Relationship to upstream

- **Everything preserved**: Material You theming, multi-provider support, streaming, conversation forking & regeneration, message edit / delete / translate, full-text search (jieba), favorites, image generation, TTS / ASR, MCP, workspace sandbox (multi-tab terminal + shell compatibility mode), backup (S3 / WebDAV), web chat endpoint, and chat export all work as before
- **Already merged with upstream**: `rikkahub/rikkahub` master **v2.5.4** (2026-09, includes Arabic i18n, RTL icon adaptation, "restore missing assistants from chat history" recovery, and fixes for MCP empty-header errors #1949, OpenAI Responses `item_id` #1948, favorites swipe-to-delete, thought-step icon black background #1946, code-block line-number copying, and Google provider `propertyNames` filtering)
- **Versus the intermediate mingli2 branch**: beyond tavern enhancements, this branch adds prompt prefix caching, semantic memory RAG & rolling compression, Jev decisioning, proxy-station compatibility & anti-empty-reply, Doubao voice, and privacy hardening
- **New since v2.5.4**: the academic system moved out of the app into a plugin, the calories & protein plugin, session-aware HTTP and image decoding in the plugin sandbox, detail-page data cards, the update check moving to the GitHub Releases API, and Jev decisioning
- **Merging upstream**: see the conflict handbook in [DIVERGENCE.md](DIVERGENCE.md)

---

## 🤝 Contributions welcome

Contributions are always welcome! Bug reports, suggestions, and code are all appreciated:

- Bug reports / suggestions: [Issues](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/issues)
- Code: just Fork + PR — no permission needed

---

## 🙏 Credits

This project stands on the shoulders of others:

| Project | Relationship | License |
|---|---|---|
| [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) | **Original upstream** — all base functionality comes from it | AGPL-3.0 |
| [heikeyangle-code/rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus) | **Direct upstream (intermediate fork)** — author of the tavern system, macro engine, slash commands, group chats | AGPL-3.0 |
| [sue1231513/orangechat](https://github.com/sue1231513/orangechat) | Same-origin fork — this project introduced **couples space / life hub / three-layer memory / chat appearance customization / QuickJS plugin system** from it and its downstream | AGPL-3.0 |
| [lingwangshu018/Tumin](https://github.com/lingwangshu018/Tumin) | Downstream of orangechat — see above | AGPL-3.0 |
| [ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent) | Same-origin fork — reference for some device-toolbox implementations | AGPL-3.0 |
| [YaeNovin/Rikkahub-Revised](https://github.com/YaeNovin/Rikkahub-Revised) | Same-origin fork — this project ported **semantic memory RAG** and **rolling context compression** from it | AGPL-3.0 |
| [SillyTavern/SillyTavern](https://github.com/SillyTavern/SillyTavern) | The compatibility target; the **semantics and format specs** of cards / lorebooks / macros / slash commands follow its official implementation (AGPL-3.0). No code was copied from it | AGPL-3.0 |

This repository and its upstreams are all **AGPL-3.0** licensed; this fork continues under the same license. Copyright of each upstream project belongs to its authors — thank you for open-sourcing.


> 中文介绍：[README_CN.md](README_CN.md) —— 安卓上的 AI 聊天客户端，兼为 SillyTavern（酒馆）安卓兼容端：角色卡 / 世界书 / 预设 / 正则 / 美化主题按官方语义一键导入，无需 Termux 或 Node.js。搜「安卓 酒馆」「SillyTavern 安卓」即可找到本项目。
---

<div align="center">

If this fork is useful to you, please leave a ⭐ Star ✨

</div>
