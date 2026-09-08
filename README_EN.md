# RikkaHub Plus

[**English**](README_EN.md) | [**简体中文**](README.md)

> A deeply customized fork of [RikkaHub](https://github.com/rikkahub/rikkahub), already merged with the latest upstream (v2.5.0).
> Every upstream capability is preserved as-is; on top of it this fork strengthens four areas: **⚡ prompt prefix caching**, **🧠 memory & long conversations**, **🍺 SillyTavern compatibility**, and **🛡 privacy & stability**.
> Per-file differences and the upstream merge workflow live in [DIVERGENCE.md](DIVERGENCE.md).

---

## 📌 What it is

An AI chat client that runs on your phone (Kotlin + Jetpack Compose + Material You):

- **Prompt prefix caching**: modeled on the DeepSeek Harness prefix-stability design — long-conversation token costs can drop by up to 90%
- **Memory & long conversations**: semantic memory RAG + rolling context compression — long chats no longer forget or blow the context window
- **Multi-provider**: OpenAI / Claude / Gemini / DeepSeek — any OpenAI-, Anthropic-, or Google-compatible API (with a built-in OrcaRouter aggregation gateway, disabled by default)
- **Deep SillyTavern compatibility**: character cards, lorebooks, presets, regex scripts, quick replies (QR), HTML cards, multiple greetings — all imported/exported losslessly with official semantics
- **Programmable prompts**: Macro Engine 2.0, 20+ slash commands, personas, author's note, group chats
- **Privacy hardening**: sanitized request logging, tool-approval boundaries, telemetry off by default

---

## ⚡ Prompt Prefix Cache

Major providers (DeepSeek / Kimi / Claude, etc.) offer automatic prefix caching: if a request's prefix is byte-identical to the previous one, it's a cache hit and tokens cost roughly 1/10. But in chat, a lot of content **changes every turn** (timestamps, recent chats, memories, random numbers, rolling summaries) — once the prefix diverges, the whole cache is dead.

Modeled on the DeepSeek Harness prefix-stability design, this fork systematically eliminates those divergence points:

- **Frozen anchors for dynamic context**: when injected dynamic content (recent chats, memory references, …) changes, the old block stays **frozen in place** and the new block is appended at the tail — dynamic content no longer drifts backward with history, so divergence only happens near the tail
- **Per-message-stable random macros**: `{{random}}` / `{{pick}}` and other random macros resolve deterministically per message — history no longer re-rolls its dice every turn
- **Append-style rolling summaries**: when context compression triggers, the summary chains on append-style, keeping the token prefix stable across compressions
- **Injection-position hygiene**: full memory injection moved to the tail of the context, Recent Chats moved out of the prefix zone — no volatile content left on the hot path
- **Prefix divergence diagnostics**: a built-in diagnostic view compares consecutive requests message by message, showing the common prefix and estimated hit rate, with **character-level diff location** — you can always see exactly why the cache missed

Result: 90%+ hit rates in steady chat, long-conversation token costs close to one-tenth.

---

## 🧠 Memory & Long Conversations (ported from [Rikkahub-Revised](https://github.com/YaeNovin/Rikkahub-Revised))

- **Semantic memory RAG**: memories split into FACT / EPISODIC types, embedded with the vector model and retrieved via cosine similarity (with a lexical fallback of word terms + CJK bigrams), episodic memories get recency weighting, and results are injected into the system prompt within a budget.
- **Enhanced memory_tool**: a new `list` read operation plus fact / episodic type distinction — the model can review existing memories before writing or updating; episodic memory can be toggled per assistant.
- **Memory management page**: view / edit / delete memory entries per assistant.
- **Rolling context compression**: when a conversation exceeds the threshold (auto-computed from the model's context window or set manually), a compression model rolls earlier turns into a non-destructive summary (original messages preserved, replaced only at request time), injected as a system message — long chats stay in-window without losing persona or foreshadowing.
- **Recent chats reference**: optionally inject the assistant's recent conversation list for cross-session continuity.
- All per-assistant switches, off by default; existing behavior unchanged.

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

On top of upstream: file operations, shell, task tools, calculator, database query, Python engine (Chaquopy), web scraping, and a YNUFE academic-system query tool (timetable/grades/exams/notices/empty classrooms, with its own account settings page); plus a **system-prompt assembler** (tool-selection guide / work ethics).

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
- Consistent-snapshot backup import with safe startup recovery, PickVisualMedia image picking, and many fixes.

---

## ✅ Relationship to upstream

- **Everything preserved**: Material You theming, multi-provider support, streaming, conversation forking & regeneration, message edit / delete / translate, full-text search (jieba), favorites, image generation, TTS / ASR (incl. Volcengine bidirectional streaming), MCP, workspace sandbox (multi-tab terminal), backup (S3 / WebDAV), web chat endpoint, and chat export all work as before.
- **Already merged with the latest upstream**: rikkahub/rikkahub master (v2.5.0); pull upstream anytime via `git fetch rikkahub && git merge rikkahub/master` (conflict handbook: [DIVERGENCE.md](DIVERGENCE.md)).
- **Versus the mingli2 branch**: beyond the tavern enhancements, this branch mainly adds prompt prefix caching, semantic memory RAG & rolling context compression, privacy hardening, and the YNUFE academic-system tools (tavern-level deltas are noted at the top of the "Tavern System" section).

## 📦 Download

- **Nightly prerelease**: Actions build daily and publish to [Releases](https://github.com/MiaoWuNYA/rikkahub-plus/releases/tag/nightly) (tag `nightly`, overwritten with the latest each night).
- **Manual builds**: every push builds an APK artifact (`rikkahub-plus-fresh`) on [Actions](https://github.com/MiaoWuNYA/rikkahub-plus/actions).

---

## 🙏 Credits

This project stands on the shoulders of others:

| Project | Relationship | License |
|---|---|---|
| [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) | **Original upstream** — all base functionality comes from it | AGPL-3.0 |
| [heikeyangle-code/rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus) | **Direct upstream (intermediate fork)** — author of the tavern system, macro engine, slash commands, group chats, and other core enhancements | AGPL-3.0 |
| [YaeNovin/Rikkahub-Revised](https://github.com/YaeNovin/Rikkahub-Revised) | Same-origin fork — this project ported the **semantic memory RAG** and **rolling context compression** from it | AGPL-3.0 |
| [SillyTavern/SillyTavern](https://github.com/SillyTavern/SillyTavern) | The compatibility target of the tavern system; the **semantics and format specs** of cards / lorebooks / macros / slash commands follow its official implementation (AGPL-3.0). No code was copied from it. | AGPL-3.0 |

This repository and its upstreams are all **AGPL-3.0** licensed; this fork continues under the same license. Copyright of each upstream project belongs to its authors — thank you for open-sourcing.

---

If this fork is useful to you, please leave a ⭐ Star ✨
