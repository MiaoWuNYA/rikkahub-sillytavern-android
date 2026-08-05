# RikkaHub Plus — Astrology & Tavern Enhanced

[**English**](README_EN.md) | [**简体中文**](README.md)

> A deeply customized fork of [RikkaHub](https://github.com/rikkahub/rikkahub) (a native Android LLM chat client). Every upstream capability is fully preserved.
> This page compares “what upstream has” vs. “what this fork adds on top”; for per-file differences and the merge workflow see [DIVERGENCE.md](DIVERGENCE.md).

**Highlights**: a fully upgraded Tavern character-card / lorebook system (lossless import/export, official injection structure), a brand-new astrology & divination engine (twelve systems), Macro Engine 2.0 with slash commands, automatic skill triggering, and a Python/JS dual bridge.

---

## 🍺 Tavern System (all related enhancements)

### 1. Character Cards: Import → Structure → Inject → Export → Edit

**Upstream**: JSON (V2/V3) and PNG imports exist, but only 6 fields are parsed (name / first_mes / system_prompt / description / personality / scenario) and flattened into a single system-prompt string. **No export.**

**What this fork adds:**

- **Field coverage grows from 6 to 22, all structurally preserved**: mes_example, alternate_greetings, creator_notes, post_history_instructions (PHI), character_version, tags, creator, creator_notes_multilingual, source, creation/modification_date, nickname, assets, group_only_greetings, **character_book (embedded lorebook)**, and **extensions (depth prompts, etc.)**. Upstream drops everything outside its 6 fields; this fork loses nothing.
- **Different injection model**: upstream concatenates fields into one system prompt; this fork follows the official Chat Completion structure — main prompt, standalone character-field messages, example messages parsed into real user/assistant turns, PHI appended after history, depth prompts injected at their configured depth/role.
- **Lossless export (new)**: PNG / JSON export with official field names (insertion_order, extensions.*); import → export round-trips without data loss.
- **Character card detail editor (new)**: visual editing of all 22 fields, embedded lorebook management, and an export button.

### 2. Lorebooks

**Upstream**: keyword matching → content injection, with scan depth, constant entries, priority, and injection position.

**What this fork adds:**

- **Four selective-logic modes** (AND_ANY / AND_ALL / NOT_ANY / NOT_ALL); primary keywords must hit first, official semantics
- **Cross-book groups**: only one entry per group activates — sticky wins → keyword score → override → weighted random
- **Recursive scanning**: activated content feeds further scans with accumulating context; exclude/prevent controls and numeric `delay_until_recursion` levels
- **Sticky / cooldown**: entries persist for N turns, then enter cooldown; isolated per conversation
- **Trigger probability**, **token budget with `ignore_budget` exemption**
- **Official regex keys** (`/pattern/flags` auto-detected)
- **match_\***: per-entry control over which character-card fields are scanned
- **EM Top / EM Bottom** anchors around example messages
- Full entry import/export (insertion_order, extensions.*)

### 3. Macro Engine 2.0

**Upstream**: simple placeholder substitution (`{{char}}`, `{{user}}`, etc.).

**What this fork adds** — prompts become programmable:

- **Variables**: `/setvar`, `/getvar`, `/incvar` … manage conversation variables; read them with `{{getvar::key}}` or `.key` shorthand, so one card can react to story state
- **Conditionals**: `{{if}} / {{else}} / !`, comparison operators, `&&` / `||`, with nesting
- **Random & time**: `{{pick::A|B|C}}` (stable within a turn), `{{roll::1d20}}`, `{{random}}`, time and idle-duration macros
- **Conversation-aware**: `{{lastUserMessage}}`, `{{lastCharMessage}}`, `{{idleDuration}}`, `{{charFirstMessage::N}}`, `{{original}}`, and more
- Unknown macros pass through untouched

### 4. Slash Commands

**Upstream**: none. This fork adds 22 built-in commands (type them in the input box; `/help` lists everything; parameterless commands run on tap, parameterized commands fill the input box for you):

- **Roleplay**: `/impersonate` (AI drafts your reply from your point of view), `/continue`, `/sendas`, `/sys`, `/send`
- **Generation control**: `/trigger`, `/sysgen`, `/inject`
- **State & debugging**: `/persona` (official `/persona-set` alias)
- **Character management**: `/char-get`, `/char-update`, `/char-duplicate`, `/rename-char`
- **Variables & random**: `/listvar`, `/setvar`, `/getvar`, `/addvar`, `/incvar`, `/decvar`, `/flushvar`, `/reroll-pick`
- All aligned with SillyTavern official semantics; skill-provided commands appear automatically

### 5. Personas

**Upstream**: none. This fork adds: official five-position injection (IN_PROMPT / TOP / BOTTOM / AT_DEPTH / NONE), per-character binding, standalone SYSTEM-message injection, and a disable option.

### 6. Author's Note (Director's Note)

**Upstream**: none. This fork adds: official interval semantics (1 = every user message / N = multiples), injection depth, injection role, and a master switch.

### 7. Group Chats

**Upstream**: none. This fork adds: multi-character conversations with independent prompts/personas/models per member; four selection strategies (Natural / List / Pooled with weights / Manual); auto-reply (configurable rounds & delay, interrupted by user messages); live speaker status.

---

## 🔮 Astrology & Divination (brand-new, absent upstream)

- **Twelve divination systems**: Modern Western astrology, Traditional Western astrology, Vedic (Indian) astrology, deep classical (Hellenistic/medieval) astrology, BaZi (Eight Characters), Zi Wei Dou Shu, Tarot, Lenormand, Human Design, numerology/Kabbalah, Qi Men Dun Jia (incl. Da Liu Ren), and Liu Yao (incl. Plum Blossom)
- **Timing techniques**: transits, progressions, returns, profections, Firdaria, length-of-life, Almuten, Arabic parts, midpoints, antiscia, draconic charts
- **Dual mode**: a deterministic `mingli` tool plus engine self-exploration through the JavaScript / Python bridge — one switch controls both

---

## 🚀 Other Improvements over Upstream

### Skills

**Upstream**: reads `SKILL.md` + a `use_skill` tool (model must call it).

**This fork adds**:

- **Automatic triggering**: matching skill keywords inject SKILL.md into the prompt without relying on the model
- **Public skills directory** `/Rikkahub/skills`: drop files in via the file manager
- **External-storage skills**: no longer locked inside the app-private directory
- **GitHub skill install**: install directly by repository / marketplace
- **Rewritten skills page/detail page** + enhanced `use_skill` (categories, linked_files, command hints)

### Tools

**Upstream**: time, clipboard, calendar, JavaScript, screen time, TTS, ask-user + conversation, memory, search, skills, workspace tools.

**This fork adds**: file operations, shell, task tools, calculator, database query, Python engine, web scraping, and two divination tools — plus a **Python/JS dual bridge** (read/write conversations, assistant settings, group chats; run Python/JS engines) and a system-prompt assembler (divination workflow / tool guide / work ethics).

### Stability

- Background generation keeps running: foreground service with async start, 600 ms debounce, and failure fallback — switching apps doesn't interrupt generation
- Removed upstream's unused extras (GitHub tool, sleep, knowledge base, log-debug pages — see DIVERGENCE.md; do not re-add)

---

## ✅ All Upstream Features Preserved

File-level verification: every upstream file has a counterpart here; no functionality was removed (only 4 file groups were moved/rewritten). Preserved as-is: Material You theming, multi-provider switching (OpenAI/DeepSeek/Claude/Gemini…), streaming, conversation forking & regeneration, message edit/delete/translate, full-text search (jieba), favorites, history, image generation, TTS/ASR, MCP, workspace sandbox, backup (S3/WebDAV/reminders), web server, chat export, and the log page.

## 🔗 Links

- Upstream project: [RikkaHub](https://github.com/rikkahub/rikkahub)
