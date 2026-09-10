# RikkaHub Plus

[**English**](README_EN.md) | [**简体中文**](README.md)

> A deeply customized fork of [RikkaHub](https://github.com/rikkahub/rikkahub), already merged with the latest upstream (**v2.5.1**).
> Every upstream capability is preserved as-is; on top of it this fork strengthens six areas: **⚡ prompt prefix caching**, **🧠 memory & long conversations**, **🍺 SillyTavern compatibility**, **🗼 proxy-station compatibility**, **📱 on-device augmentation**, and **🛡 privacy & stability**.
> Per-file differences and the upstream merge workflow live in [DIVERGENCE.md](DIVERGENCE.md).

---

## 📌 What it is

An AI chat client that runs on your phone (Kotlin + Jetpack Compose + Material You):

- **Prompt prefix caching**: drawing on the DeepSeek Harness prefix-stability design — can significantly reduce long-conversation token costs
- **Memory & long conversations**: semantic memory RAG + three-layer memory + rolling context compression — long chats no longer forget or blow the context window
- **Multi-provider**: OpenAI / Claude / Gemini / DeepSeek — any OpenAI-, Anthropic-, or Google-compatible API (with a built-in OrcaRouter aggregation gateway, disabled by default)
- **Proxy-station compatibility**: fixes Gemini-via-OpenAI-proxy pathologies — body swallowed by reasoning_content, truncation, "response" prefix artifacts — with one switch
- **Anti-empty-reply**: auto-perturb-and-retry for Gemini's classic empty replies; system prompt moved into the conversation flow (world-book injection positions untouched)
- **Doubao voice**: speech synthesis 2.0 (Doubao TTS) + Volcengine ASR, one Agent Plan API key drives both — and powers in-app voice/video calls
- **Deep SillyTavern compatibility**: character cards, lorebooks, presets, regex scripts, quick replies (QR), HTML cards, multiple greetings, **beautification theme import** — all imported/exported losslessly with official semantics
- **Plugin system**: QuickJS-sandboxed plugins, one-tap ZIP import, AI can call plugin tools directly
- **Device toolbox**: 30 phone tools (torch, volume, SMS, contacts, location, …) behind a token-saving lazy-discovery meta-tool
- **WeChat / QQ Bot**: connect an assistant to WeChat or QQ and keep chatting anywhere; AI proactive messaging supported
- **Programmable prompts**: Macro Engine 2.0, 20+ slash commands, personas, author's note, group chats
- **Privacy hardening**: sanitized request logging, tool-approval boundaries, global security settings, telemetry off by default

---

## 🏮 HuaDeng Settings (fork-exclusive)

Settings → **HuaDeng Settings** collects this fork's compatibility & helper features on one page. Global switches apply to all assistants (per-assistant switches can override).

### 1. Proxy Fix (中转站兼容)

Three classic pathologies when Gemini is accessed through OpenAI-compatible proxy stations (newapi etc.), fixed automatically:

- **Body swallowed by reasoning_content**: some proxies put the actual reply into the reasoning field, leaving artifacts in `content` — after the stream ends, if the body is empty while reasoning has substance, the reasoning is promoted to the body (no false positives during the thinking phase; normal long-thinking + short-answer replies are untouched)
- **"response" prefix artifacts**: stray `response` / `Response:` leftovers at the start of the body — stripped on both streaming and non-streaming paths, even when the prefix is split across multiple deltas; boundary checks avoid mangling English words like "responses"
- **Truncation**: promotion + stripping logic presents the reply in full instead of "answer in the thinking block, half a reply in the body"

Both streaming and non-streaming paths covered; off by default, enable in HuaDeng Settings or per assistant.

### 2. Anti-Empty-Reply (global)

- **System prompt into the conversation flow**: SYSTEM messages are converted in place to user turns (with a model-acknowledgment turn after the first); world-book / persona / rolling-summary injection positions and content stay untouched — bypassing Gemini's safety blocking of systemInstruction
- **Auto-perturb retry on empty replies**: when a reply arrives with no text and no tool calls, the last user message is perturbed (add/remove periods, add space — 4 rotating variants) and retried up to 3 times
- The per-assistant switch and the global switch work in OR

### 3. YNUFE Academic System (云南财经大学)

The full YNUFE (StrongZhi) account management is embedded in HuaDeng Settings:

- Student ID / password stored obfuscated on-device; the AI logs in automatically when querying timetable / grades / exams / notices / empty classrooms
- Built-in OCR for captchas, with a manual-input fallback showing the image
- Session status display and one-tap credential clearing

---

## 🔊 Doubao Voice (Volcengine Agent Plan)

- **Doubao TTS (new)**: Doubao speech-synthesis large model 2.0
  - `seed-tts-2.0` resource with 16 field-tested voice presets (Cancan 2.0 — the Doubao-app default — Tianmei Taozi, Kuaile Xiaodong, …), or type any voice ID manually
  - Speech-rate control, audio format (mp3/wav/pcm/ogg/opus) and sample rate selectable
  - Fully parses Volcengine's concatenated-JSON chunked streaming responses; long-audio synthesis verified
  - The Agent Plan dedicated endpoint is built in as the default; standard-console users can switch back to the official path
- **Volcengine ASR (fixed)**: Agent Plan `ark-xxx` keys are only valid on the dedicated `/api/v3/plan/` path — the default WebSocket URL now points there; standard-console users can change it back in settings
- One Agent Plan API key drives both voice input and voice output

> The upstream 2.5.1 voice mode (queued messages, voice replies, tool-approval interruption guards) is included as well.

---

## 📱 On-Device Augmentation

### Device toolbox

Enable "Device toolbox" on an assistant and the AI can call 30 phone system tools: torch, vibrate, volume/brightness (read+write), toast, battery, storage, Wi-Fi / audio / telephony / sensor info, share, wallpaper, notifications, alarm/timer, music control, SMS reading, contacts, call log, location, app launching, media scanning, file download, open file, and more.

To save tokens this uses a **lazy-discovery meta-tool**: only one `device_toolbox` slot is registered in the context. The AI first calls `action=list` to fetch the tool catalog (parameter schemas + granted-permission status), then `action=run` to invoke a specific tool. State-changing / privacy-sensitive tools require approval before execution; pure readers are approval-free.

### WeChat Bot / QQ Bot

Connect an existing assistant to a messaging channel (AI, memories, and tools are all reused from that assistant):

- **WeChat Bot**: scan a QR code to log in with your own WeChat account (iLink protocol), HTTP long-polling receives messages → auto reply; expired tokens stop the service with a notification
- **QQ Bot**: official QQ Open Platform API — just enter AppID + AppSecret; WebSocket gateway for real-time send/receive with automatic token refresh
- Both are off by default with a privacy risk-confirmation dialog before enabling

### AI proactive messaging

- AlarmManager exact alarms + WorkManager fallback dual-channel; the assistant reaches out at random intervals (configurable range)
- Generation injects context (time since last chat, current time, battery); politely skips a trigger while a generation is already running
- Off by default

### Voice / video calls

One tap on the chat top bar enters the call screen (**shown only when both TTS and ASR are configured**): local voice-activity detection → ASR incremental transcription → auto-send → streaming TTS of the reply, interruptible at any time; on hang-up the call is folded into an archive card in the conversation. Works out of the box with Doubao TTS / Volcengine ASR.

### Security settings

Settings → Security: global tool-call approval policy — **force-confirm all tool calls** (confirm before every execution) or **auto-approve all tool calls** (skip approval; use with caution). The two are mutually exclusive.

---

## 🧩 Plugin System (from orangechat/Tumin)

QuickJS-sandboxed plugins: a plugin is a ZIP package (`manifest.json` + `main.js`); import it in Settings → Plugins (two-stage preview confirmation + SHA-256 integrity check + Zip-Slip protection).

- Tools declared in the manifest become AI-callable tools (unified `plugin_` prefix, approval required to execute)
- The JS sandbox is single-threaded with a 30s timeout, `fetch` domain allowlist (fail-closed), and per-plugin key-value storage
- Folders, enable/disable, and per-plugin config forms (text / password / boolean / select / model picker) are supported
- See `docs/plugins/example/weather/` for a sample plugin and [docs/PLUGINS_GUIDE.md](docs/PLUGINS_GUIDE.md) for the development guide

---

## 🎨 Chat Appearance & Themes

- **Color overrides**: 7 custom colors (primary, global text, user/AI/thinking bubbles, chat background, input field, ARGB)
- **Bubble beautification**: user/AI bubble background images + corner radius + theme-color overlay, drawer background image and **chat background image** (tinted with the chat background color)
- **SillyTavern theme import**: one-tap import of SillyTavern beautification theme JSONs, bulk-verified against 500+ real themes (537/537 parse successfully):
  - **Layered color compositing**: theme tints (`blur_tint` → `chat_tint` → message bubble tints → background image) are composited source-over into opaque approximations following SillyTavern's render stack, so transparent-bubble themes no longer collapse into flat color blocks; 8-digit hex parsed per CSS spec as `#RRGGBBAA`
  - **Background image**: `background-image` on `#bg1` / `body` / `#chat` detected from `custom_css` (URLs auto-downloaded, data URIs decoded) and shown as the chat background
  - **Bubble styling**: `border-radius` on `.mes` / `#chat` maps to bubble corner radius; `chat_display=1` (bubble mode) enables assistant bubbles automatically
  - Also: `main_text_color` → global text, `quote/italics_text_color` → quote/italics colors (opaque only), `font_scale` → font scale
- Preset palettes + HCT custom themes + dynamic color remain unchanged

---

## ⚡ Prompt Prefix Cache

Major providers (DeepSeek / Kimi / Claude, etc.) offer automatic prefix caching: if a request's prefix is byte-identical to the previous one, it's a cache hit, and the cached portion is billed far below the normal rate. But in chat, a lot of content **changes every turn** (timestamps, recent chats, memories, random numbers, rolling summaries) — once the prefix diverges, the whole cache is dead.

Drawing on the DeepSeek Harness prefix-stability design, this fork targets those common divergence points:

- **Frozen anchors for dynamic context**: when injected dynamic content (recent chats, memory references, …) changes, the old block stays **frozen in place** and the new block is appended at the tail — dynamic content no longer drifts backward with history, so divergence only happens near the tail
- **Per-message-stable random macros**: `{{random}}` / `{{pick}}` and other random macros resolve deterministically per message — history no longer re-rolls its dice every turn
- **Append-style rolling summaries**: when context compression triggers, the summary chains on append-style, keeping the token prefix stable across compressions
- **Injection-position hygiene**: full memory injection moved to the tail of the context, Recent Chats moved out of the prefix zone — no volatile content left on the hot path
- **Prefix divergence diagnostics**: a built-in diagnostic view compares consecutive requests message by message, showing the common prefix and estimated hit rate, with **character-level diff location** — you can always see exactly why the cache missed

Actual results vary by conversation shape: in steady, append-only chats the cache hit rate and long-conversation costs improve noticeably — the exact hit rate and savings depend on how often content changes and on each provider's cache pricing.

---

## 🧠 Memory & Long Conversations

- **Semantic memory RAG** (ported from [Rikkahub-Revised](https://github.com/YaeNovin/Rikkahub-Revised)): memories split into FACT / EPISODIC types, embedded with the vector model and retrieved via cosine similarity (with a lexical fallback of word terms + CJK bigrams), episodic memories get recency weighting, and results are injected into the system prompt within a budget.
- **Three-layer memory** (from orangechat/Tumin): a fixed-memory section (independently editable without touching the character card) + a recent-life stream (cross-conversation memory of the current chat; unread events from other conversations are injected automatically) + long-term memory recall via lexical-overlap scoring; the stream is summarized in the background by the compression model once over a threshold.
- **Enhanced memory_tool**: a new `list` read operation plus fact / episodic type distinction — the model can review existing memories before writing or updating; episodic memory can be toggled per assistant.
- **Memory management page**: view / edit / delete memory entries per assistant.
- **Rolling context compression**: when a conversation exceeds the threshold (auto-computed from the model's context window or set manually), a compression model rolls earlier turns into a non-destructive summary (original messages preserved, replaced only at request time), injected as a system message — long chats stay in-window without losing persona or foreshadowing.
- **Recent chats reference**: optionally inject the assistant's recent conversation list for cross-session continuity.
- **Transient-content pruning**: web-search results / images / audio / video older than two turns are dropped from requests automatically (with the message ID so the AI can retrieve the original via `read_history_message`) — token usage drops sharply on image- and search-heavy long chats.
- Memory features (memory tool / RAG retrieval / three-layer memory / cross-window life stream) are **enabled by default** — memory helps the AI know the user better and is not sacrificed to save context; new assistants get it out of the box, existing assistants keep their settings, and everything can still be toggled per assistant.
- **First-turn auto memory**: the first turn of a conversation automatically injects the most recent memories (there is no relevance query available at the start); later turns recall by relevance — the AI knows you from the very first message instead of only when a topic matches.

---

## 🍺 Tavern System (aligned rule-by-rule with official SillyTavern)

> The tavern core (character-card structure, lorebook engine, Macro Engine 2.0, slash commands, group chats) comes from the `mingli2` branch of the intermediate fork [heikeyangle-code/rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus). This branch (huadeng) **adds on top of it**: HTML card rendering (expanded by default + tap-to-fullscreen), multiple-greetings import, preset & regex script import, QR import, lorebook editor field completion + token-budget fallback, Vector Storage semantic entries, a prompt viewer, regex depth limits & caching, and greeting macro substitution.

### 1. Character Cards: Import → Structure → Inject → Export → Edit

- **Field coverage grows from 6 (upstream) to 20+**: example messages, alternate greetings, multilingual creator notes, post-history instructions, character version, tags, nickname, assets, group_only_greetings, creation/modification dates, embedded lorebook (character_book), and raw `extensions` JSON (including depth-prompt depth/role) — what upstream drops, this fork keeps. **Import → export round-trips without data loss.**
- **Official Chat Completion injection structure**: main prompt, standalone character-field messages, example messages split on `<START>` into real user/assistant turns, PHI appended after history, depth prompts injected at their configured depth/role.
- **V2 / V3 dual version**: V3 advanced fields (nickname, multilingual notes, source, timestamps) and ccv3 PNG cards; non-PNG cards auto-convert to PNG with macro substitution.
- **Visual character-card editor**: all fields, embedded-lorebook management, one-tap export.
- **Multiple greetings**: full `alternate_greetings` import with in-chat switching.
- **HTML cards**: SillyTavern HTML display cards render in-chat, expanded by default with tap-to-fullscreen.

### 2. Lorebooks

Aligned rule by rule with the official SillyTavern world-info.js; entry fields grow from 6 to 30+:

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

**Scan engine**: the full official checkWorldInfo state machine (INITIAL → recursion / min-activations / delay-level loop) with budget, overflow, sticky and cooldown lifecycles; cross-book groups elect a single entry per official rules (sticky → keyword score → group override → weighted random); recursive scanning with context feedback and step-by-step delay opening.

**Lorebook editor**: global settings (scan depth, token budget + absolute cap, min activations + max depth, recursion + step cap, insertion strategy, overflow alert, group scoring), full per-entry editing, drag-to-reorder, two-way sync between external and embedded lorebooks; Vector Storage semantic entries supported.

### 3. Preset & Regex Script Import

- **Preset import**: SillyTavern JSON presets imported under the official prompt-manager structure.
- **Regex script import**: Find/Replace/_ALT, OnlyFormat, macros, injection depth (minDepth/maxDepth), ordering and caching — applied at both display and prompt layers.
- **Beautification theme import**: SillyTavern theme JSONs import directly into the chat appearance (see "Chat Appearance & Themes").

### 4. Quick Replies (QR)

SillyTavern QR sets import; run from the slash popup in one tap.

### 5. Macro Engine 2.0

The complete official Macro 2.0 syntax:

- **Variables**: `{{setvar}}` `{{getvar}}`, the `{{.var}}` shorthand family, global + per-conversation persistence — cards can remember story state
- **Conditionals**: `{{if}} / {{else}}`, comparison operators, `&&` / `||`, scoped blocks, nesting
- **Random & time**: `{{pick}}` (stable within a turn), `{{roll::1d20}}`, `{{random}}`, `{{time}}`, `{{datetimeformat}}`
- **Conversation-aware**: `{{lastUserMessage}}` `{{lastCharMessage}}` `{{idleDuration}}` `{{charFirstMessage::N}}` `{{original}}` — 60+ official macros supported
- Unknown macros pass through untouched

### 6. Slash Commands

Type them in the input box; `/help` lists everything with descriptions. 20+ built-in commands:

- **Roleplay**: `/impersonate` (the AI drafts your reply in your voice), `/continue`, `/sendas`, `/sys`, `/sysgen` (AI writes narration), `/trigger` (trigger a reply without adding a message), `/message-name`, `/delname`
- **Variables & random**: `/listvar` `/setvar` `/getvar` `/addvar` `/incvar` `/decvar` `/flushvar` `/reroll-pick`
- **Character management**: `/char-update` `/char-duplicate` `/rename-char`
- **Injection**: `/inject` (position/depth/role), `/prompt`
- Skill-provided commands appear automatically in the popup

### 7. Personas & Author's Note

- Personas: official five-position injection (IN_PROMPT / TOP / BOTTOM / AT_DEPTH / NONE), per-character binding, standalone SYSTEM-message injection, one-tap disable.
- Author's note: official interval semantics (every / every N user messages), injection depth & role, master switch.

### 8. Group Chats

Multi-character conversations with independent prompts / personas / models per member; 4 speaker-selection strategies (NATURAL AI-picked / list / weighted random / manual) + 5 extended modes; auto-reply (1–10 configurable rounds & delay, interrupted by user messages); live speaker status; full persistence.

---

## 🛠 Skills & Tools

### Skills

- **Automatic triggering**: matching keywords inject SKILL.md without relying on the model.
- **Public skills directory** `/Rikkahub/skills`: add or remove via any file manager.
- **GitHub one-click install / batch download / update detection**: subdirectories and multi-skill repos supported, with source + directory-hash tracking.
- Rewritten skills pages and an enhanced `use_skill` tool.

### Tools

On top of upstream: file operations, shell, task tools, calculator, database query, Python engine (Chaquopy), web scraping, and a YNUFE academic-system query tool (timetable/grades/exams/notices/empty classrooms; account management lives in HuaDeng Settings); plus a **system-prompt assembler** (tool-selection guide / work ethics).

---

## 🛡 Privacy & Stability

### Privacy hardening

- **Sanitized request logging**: request-header allowlist, prompt / schema / binary / credential redaction, secret masking and size caps on error messages.
- **Tool approval & privacy boundaries**: clipboard / screen-time / shell require approval on every execution, file tools require approval for write operations; screen-time queries are limited in scope and detail and never expose package names.
- **Backup-restore resource budget**: entry counts and per-entry / total decompression sizes are capped to guard against malicious archives exhausting resources.
- **Daily file cleanup**: chat attachments and generated images can auto-expire by retention days (off by default).
- **Firebase telemetry off by default**: Google services and Crashlytics only activate with a build property.

### Stability

- **Foreground-service keep-alive**: generation survives app switching.
- **SSE long-connection hardening**: OkHttp 30s PING keep-alive; event-stream requests disable caching and compression so proxies no longer buffer streaming output.
- **Smooth database upgrades**: every schema change ships as an explicit migration — existing data upgrades losslessly.
- Consistent-snapshot backup import with safe startup recovery, PickVisualMedia image picking, and many fixes.

---

## ✅ Relationship to upstream

- **Everything preserved**: Material You theming, multi-provider support, streaming, conversation forking & regeneration, message edit / delete / translate, full-text search (jieba), favorites, image generation, TTS / ASR (incl. Volcengine bidirectional streaming and Doubao TTS), MCP, workspace sandbox (multi-tab terminal + shell compatibility mode), backup (S3 / WebDAV), web chat endpoint, and chat export all work as before.
- **Already merged with the latest upstream**: rikkahub/rikkahub master (**v2.5.1**); highlights of this merge: voice mode & message queue, translator shortcut, workspace shell compatibility mode & HTML/SVG preview, custom time-reminder interval, custom Response API path, DeepSeek V4.1 Flash, `ask_user` free-text replies, image-generation multi-select, and more. Pull upstream anytime via `git fetch rikkahub && git merge rikkahub/master` (conflict handbook: [DIVERGENCE.md](DIVERGENCE.md)).
- **Versus the mingli2 branch**: beyond the tavern enhancements, this branch mainly adds prompt prefix caching, semantic memory RAG & rolling context compression, proxy-station compatibility & anti-empty-reply, Doubao voice, privacy hardening, and the YNUFE academic-system tools (tavern-level deltas are noted at the top of the "Tavern System" section).
- **New in v2.5.3**: device toolbox (lazy discovery), WeChat / QQ Bots, AI proactive messaging, voice / video calls, couples space / life hub, QuickJS plugin system, three-layer memory, chat-appearance customization with SillyTavern theme import, global security settings, a clean-simple mode, transient-content pruning, and the compression-loop fix.

## 📦 Download

- **Nightly prerelease**: Actions build daily and publish to [Releases](https://github.com/MiaoWuNYA/rikkahub-plus/releases/tag/nightly) (tag `nightly`, overwritten with the latest each night).
- **Stable releases**: versioned releases (`2.5.2fixN`) on [Releases](https://github.com/MiaoWuNYA/rikkahub-plus/releases).
- **Manual builds**: every push builds an APK artifact (`rikkahub-plus-fresh`) on [Actions](https://github.com/MiaoWuNYA/rikkahub-plus/actions).

---

## 🙏 Credits

This project stands on the shoulders of others:

| Project | Relationship | License |
|---|---|---|
| [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) | **Original upstream** — all base functionality comes from it | AGPL-3.0 |
| [heikeyangle-code/rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus) | **Direct upstream (intermediate fork)** — author of the tavern system, macro engine, slash commands, group chats, and other core enhancements | AGPL-3.0 |
| [sue1231513/orangechat](https://github.com/sue1231513/orangechat) | Same-origin fork — this project introduced signature features such as the **couples space / life hub / three-layer memory / chat appearance customization / QuickJS plugin system** from it and its downstream Tumin | AGPL-3.0 |
| [lingwangshu018/Tumin](https://github.com/lingwangshu018/Tumin) | Downstream fork of orangechat — see above | AGPL-3.0 |
| [ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent) | Same-origin fork — reference for some device-toolbox tool implementations | AGPL-3.0 |
| [YaeNovin/Rikkahub-Revised](https://github.com/YaeNovin/Rikkahub-Revised) | Same-origin fork — this project ported the **semantic memory RAG** and **rolling context compression** from it | AGPL-3.0 |
| [SillyTavern/SillyTavern](https://github.com/SillyTavern/SillyTavern) | The compatibility target of the tavern system; the **semantics and format specs** of cards / lorebooks / macros / slash commands follow its official implementation (AGPL-3.0). No code was copied from it. | AGPL-3.0 |

This repository and its upstreams are all **AGPL-3.0** licensed; this fork continues under the same license. Copyright of each upstream project belongs to its authors — thank you for open-sourcing.

---

If this fork is useful to you, please leave a ⭐ Star ✨
