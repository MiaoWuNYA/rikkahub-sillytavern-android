# RikkaHub Plus — Tavern Enhanced · Divination Engine

[**English**](README_EN.md) | [**简体中文**](README.md)

> A deeply customized fork of [RikkaHub](https://github.com/rikkahub/rikkahub): a native Android LLM chat client.
> Every upstream capability is preserved as-is; on top of it this fork adds a **full SillyTavern-compatible layer** and a **twelve-system divination engine**.
> Per-file differences and the upstream merge workflow live in [DIVERGENCE.md](DIVERGENCE.md).

---

## 📌 What it is

An AI chat client that runs on your phone (Kotlin + Jetpack Compose + Material You):

- **Multi-provider**: OpenAI / Claude / Gemini / DeepSeek — any OpenAI-, Anthropic-, or Google-compatible API
- **Tavern-compatible**: deep SillyTavern character-card / lorebook support with lossless import/export and the official injection structure
- **Divination**: twelve deterministic charting systems (BaZi, Zi Wei, Tarot, Western & Vedic astrology, Qi Men, Liu Yao…), interpreted by the AI against authoritative templates
- **Programmable prompts**: Macro Engine 2.0, 22 slash commands, group chats, automatic skill triggering, and a Python/JS dual bridge

---

## ⚔️ What makes it unique vs. upstream

| | Upstream RikkaHub | **RikkaHub Plus** |
|---|---|---|
| 🎴 Character cards | 6 fields parsed, no export | **22 fields, lossless**: official injection structure, PNG / JSON export, visual editor |
| 📚 Lorebooks | Basic keyword matching | **Full official semantics**: four keyword modes, cross-book groups, recursive scanning, sticky / cooldown, token budget |
| 🧩 Macros | Simple placeholders | **Macro Engine 2.0**: variables, conditionals, random & time, conversation-aware — prompts become programmable |
| ⌨️ Slash commands | None | **21 built-in commands**: `/impersonate` `/continue` `/sysgen` `/reroll-pick` … |
| 👥 Personas / Author's Note / Group chats | None | **Three brand-new systems**, aligned with official SillyTavern semantics |
| 🔮 Divination | None | **Twelve charting systems** + 14 mandatory interpretation templates, Python / JS dual-engine cross-validation |
| 🚀 Skills | Loaded only if the model calls them | **Automatic triggering**, public directory, GitHub one-click install / batch download / update detection |
| ⚡ Generation | Prone to interruption in background | **Foreground-service keep-alive** — generation survives app switching |

In one sentence: **upstream is a base chat client; this is a complete toolbox for AI roleplay and divination.**

---

## 🍺 Tavern System (the fork's main battleground)

> Scale, verified file by file: card import `AssistantImporter.kt` — upstream 183 lines → fully rewritten here for V2/V3; lorebook engine `PromptInjectionTransformer.kt` — upstream 269 lines → 860 here; lorebook/injection editor `PromptPage.kt` — 2485 lines here; character-card editor `TavernCharacterCard.kt` — 1942 lines here; exporter `CardExporter.kt` — 313 lines here.

### 1. Character Cards: Import → Structure → Inject → Export → Edit

**Upstream**: JSON (V2/V3) and PNG imports exist, but only 6 fields are parsed (name / first_mes / system_prompt / description / personality / scenario) and flattened into a single system-prompt string ("You are roleplaying as X + ## Description + ## Personality + ## Scenario"). Everything else is dropped. **No export, no editor.**

**This fork:**

- **Field coverage grows from 6 to 20+**: example messages (mes_example), alternate greetings, creator notes (creator_notes / creator_notes_multilingual), post-history instructions (PHI), character version, tags, nickname, assets, group_only_greetings, creation / modification dates, `character_book` (embedded lorebook), and `extensions` (depth prompts, including their depth and role). What upstream drops, this fork keeps — even the raw extensions structure, so import → export round-trips without data loss.
- **Official Chat Completion injection structure**: main prompt, standalone character-field messages, example messages split on `<START>` into real user/assistant turns, PHI appended after history, depth prompts injected at their configured depth/role.
- **Lossless export (new)**: PNG / JSON via `CardExporter.kt`, with official field names (`insertion_order`, `extensions.*`); import → export round-trips without data loss.
- **Character detail editor (new)**: visual editing of all 22 fields, embedded-lorebook management, and an export button — one screen handles the whole card.

### 2. Lorebooks

**Upstream**: a 5-field model (id / name / description / enabled / entries) with keyword matching → content injection, scan depth, constant entries, priority, and injection position.

**This fork** — aligned rule by rule with the official SillyTavern world-info.js semantics; entry fields grow from 6 to 30+:

| Capability | Local field | Official counterpart |
|---|---|---|
| Four selective-logic modes | `selective` + `selectiveLogic` | `selective` + `selective_logic` (and_any / and_all / not_any / not_all) |
| Whole-word / regex / case | `matchWholeWords` / `useRegex` / `caseSensitive` | `match_whole_words` / `key_regex` / `key_case_sensitive` |
| Per-entry scan depth | `scanDepth` (overrides the global default) | `scan_depth` |
| Constant activation | `constantActive` | `constant` |
| Cross-book groups | `group` / `groupWeight` / `groupOverride` | `group` (comma-separated) / `group_weight` / `group_override` |
| Trigger probability | `probability` / `useProbability` | `probability` / `use_probability` |
| Sticky / cooldown | `sticky` / `cooldown` | `sticky` / `cooldown` |
| Delayed activation | `delay` | `extensions.delay` |
| Recursion controls | `excludeRecursion` / `preventRecursion` | `extensions.exclude_recursion` / `prevent_recursion` |
| Delay until recursion | `delayUntilRecursion` (true or numeric levels) | `extensions.delay_until_recursion` |
| Budget exemption | `ignoreBudget` | `extensions.ignore_budget` |
| Character-field matching | `match_*` ×6 (persona / description / personality / depth prompt / scenario / creator notes) | `extensions.match_*` |
| Display order / generation filter | `displayIndex` / `displayPosition` / `triggers` | `display_index` / `display_position` / `triggers` |

**Scan engine** (`PromptInjectionTransformer.kt`, 269 → 860 lines):

- **Official checkWorldInfo state machine**: INITIAL → RECURSION / MIN_ACTIVATIONS / delay-level loop, with the full budget, overflow, sticky, and cooldown lifecycle
- **Cross-book group selection**: only one entry per group activates — sticky wins → keyword score (use_group_scoring) → group_override → weighted random
- **Recursive scanning**: activated content feeds further scans with accumulating context; exclude / prevent controls and numeric `delay_until_recursion` levels opened step by step
- **Token budget**: budget = global budget % × context tokens; scanning stops on overflow (with an optional alert), `ignore_budget` entries exempt
- **Official serialization compatibility**: `selective_logic` and friends serialize under their official enum names (and_any…), so entries interoperate with Tavern imports/exports

**Editor** (`PromptPage.kt`, 2485 lines):

- **Global settings panel**: scan depth, token budget (+ absolute cap), minimum activations (+ max depth), recursive scanning (+ max recursion steps), insertion strategy (character-first / global-first / uniform), overflow alert, group scoring — all mapped to the official settings
- **Entry editor**: keywords (primary / secondary, four logic modes, regex, whole-word), injection position / depth / role, probability, sticky / cooldown / delay, groups & weights, recursion controls, match_*, budget exemption
- **Drag-to-reorder**, plus two-way sync between external and embedded (character-book) lorebooks

### 3. Macro Engine 2.0

**Upstream**: `PlaceholderTransformer.kt` (162 lines) — a key-value table of `{{char}}` / `{{user}}` / `{{time}}`, string replacement, only ~6 placeholders.

**This fork** (`MacroEngine.kt`, 965 lines) — prompts become programs:

- **Variables**: `/setvar` `/getvar` `/incvar` … manage conversation variables, read with `{{getvar::key}}` or the `.key` shorthand — one card can react to story state
- **Conditionals**: `{{if}} / {{else}} / !`, comparison operators, `&&` / `||`, with nesting
- **Random & time**: `{{pick::A|B|C}}` (stable within a turn), `{{roll::1d20}}`, `{{random}}`, `{{time}}`, `{{trim}}`, `{{comment}}`
- **Conversation-aware**: `{{lastUserMessage}}`, `{{lastCharMessage}}`, `{{idleDuration}}`, `{{charFirstMessage::N}}`, `{{original}}`
- Unknown macros pass through untouched

### 4. Slash Commands (absent upstream)

Type them in the input box and hit send; `/help` lists everything with descriptions. Parameterless commands run on tap; parameterized ones fill the input box for completion. 21 built-in commands:

- **Roleplay**: `/impersonate` (the AI drafts your reply from your point of view), `/continue` (continue at the end of the last reply), `/sendas`, `/sys`, `/send`
- **Generation control**: `/trigger` (trigger a reply without adding a message), `/sysgen` (AI writes system narration), `/gen`
- **Character management**: `/char-update`, `/char-duplicate`, `/rename-char`
- **Variables & random**: `/listvar` `/setvar` `/getvar` `/addvar` `/incvar` `/decvar` `/flushvar` `/reroll-pick` (re-roll the stable `{{pick}}` random)
- **Persona**: `/persona` (alias of official `/persona-set`)
- All aligned with official SillyTavern semantics; skill-provided commands appear automatically

### 5. Personas (absent upstream)

Official five-position injection (IN_PROMPT / TOP / BOTTOM / AT_DEPTH / NONE), per-character binding, standalone SYSTEM-message injection, and a disable option.

### 6. Author's Note (Director's Note) (absent upstream)

Official interval semantics (1 = every user message / N = multiples), injection depth, injection role, and a master switch.

### 7. Group Chats (absent upstream)

Multi-character conversations with independent prompts / personas / models per member; four speaker-selection strategies (Natural / List / Weighted random / Manual); auto-reply (configurable rounds & delay, interrupted by user messages); live speaker status.

---

## 🔮 Astrology & Divination (brand-new, absent upstream)

A complete deterministic charting system plus engine self-exploration. **One master switch controls both tools** (`mingli` deterministic charts + `mingli_guide` interpretation templates); Chinese, English, and alias system names are all recognized.

### Twelve divination systems

| System | Actual engine | Highlights |
|---|---|---|
| Tarot (Rider–Waite) | Arcanite (Python deck) + Elemental Dignity engine + Kaabalah (JS) | Multiple spreads, elemental strength & dignity analysis, Kabbalah correspondence / Tree of Life |
| Lenormand | Arcanite (Python) + LenormandFate (Python) | Multiple spreads, positional semantics, fate reading |
| BaZi (Four Pillars) | lunar_python + bazi_china (Python) | Ten Gods, monthly order, stems/branches, zodiac, Rahu, luck & yearly flows |
| Zi Wei Dou Shu | iztro (JS, default) + optional Ni Haixia (JS) / pure Python | Three-square/four-direction palaces, decadal, yearly/monthly/daily/hourly flows, small limit; multi-engine cross-check |
| Modern Western astrology | Caelus (JS, full ephemeris) | chart / derived / events / eclipses / Firdaria / profections / directions / ACG |
| Traditional Western astrology | PySwissEph + FlatLib (Python) | Classical dignities, Almuten, ruler / exalt, combustion, horary, antiscia, fixed-star conjunctions |
| Vedic (Indian) astrology | PyJHora (Python) | Vimsottari dasa, Ashtakavarga, Tajaka annual, Raja Yoga / Dosha |
| Deep classical astrology | stellium (Python, SwissEph-backed) | Firdaria, profections, length-of-life, Almuten, draconic, Arabic parts, midpoints, antiscia |
| Human Design | NatalEngine (JS) | Type / authority / centers / channels / gates / incarnation cross / Profile, gene keys, transits |
| Numerology / Kabbalah | Kaabalah (JS) | Six core numbers, personal year / challenges, Fibonacci, Gematria (forward / reverse), Ifa Odu |
| Qi Men Dun Jia (incl. Da Liu Ren) | QiMen TS (JS) + LiuRen TS (JS) | Day / hour charts, QMA spells; Da Liu Ren as its own engine |
| Liu Yao (incl. Plum Blossom) | ichingshifa (Python, Yarrow-stalk) + iching-shifa (JS) | **Dual-engine cross-check**: both engines interpret the same line values; primary / transformed hexagrams, moving lines |

> Engine facts verified against `app/src/main/python/routes/` source (2026-08).

### Architecture highlights

- **Single entry point**: `mingli_router` maps Chinese names, English names, and aliases (紫微 / ziwei / 紫微斗数 / 紫薇) — twelve systems behind one tool
- **Dual-engine bridge**: Python engines (lunar_python, bazi_china, Arcanite, PyJHora, ichingshifa, PySwissEph+FlatLib, stellium, pure-Python Zi Wei) plus prebuilt QuickJS engines (caelus / iztro / natalengine / kaabalah / qimen / liuren / iching-shifa / ziwei-nihai / lunar / astronomy / horoscope / taixuan / node-jhora) share one calling chain
- **Cross-validation**: Liu Yao runs Python + JS side by side, Zi Wei offers three selectable engines, Tarot cross-references Kabbalah
- **Deterministic structured output**: every chart returns unified JSON (houses, stars, aspects, timing techniques) and the AI is told to use every field
- **Mandatory interpretation templates**: `mingli_guide(system=<name>)` loads one of 14 authoritative Markdown templates in `assets/mingli/` and must follow them rule by rule — skipping one counts as a violation. The workflow (chart → load template → structure reply per template) is baked into the system-prompt assembler
- **Engine self-exploration**: when more depth is needed, `eval_javascript` (dig deeper with already-loaded engines) or `execute_python` (lunar calendar, custom calculations) can continue without duplicating chart code

**Timing techniques**: transits, secondary & primary directions, solar/lunar returns, annual profections, Firdaria, length-of-life, Almuten Figuris, Arabic parts, midpoints, antiscia, draconic charts, plus decadal / yearly / monthly / daily / hourly progressions.

---

## 🛠 Skills & Tools

### Skills

**Upstream**: reads `SKILL.md` + a `use_skill` tool — the model must call it. Skills live in the app-private directory; no install / update / discovery mechanism.

**This fork:**

- **Automatic triggering**: matching skill keywords inject SKILL.md into the prompt without relying on the model
- **Public skills directory** `/Rikkahub/skills`: drop files in via the file manager; skills moved to external storage so you can add or remove them freely
- **GitHub one-click install**: accepts `github.com/owner/repo` or `github.com/owner/repo/tree/branch/path` links; supports subdirectories and multi-skill repos
- **Batch download**: imports every skill in a repository at once (GitHub recursive tree API + concurrent downloads with a semaphore)
- **Update detection**: records the repo source and a whole-directory hash (`skillShas`) on install; one-click update checks per skill or all skills
- **Install-source tracking**: shows local / updatable / same-source status to avoid duplicate installs; **skill registry**: install directly from built-in entries
- **Rewritten skills page / detail page** + enhanced `use_skill` (categories, linked_files, command hints, live refresh)

### Tools

**Upstream**: time, clipboard, calendar, JavaScript, screen time, TTS, ask-user + conversation, memory, search, skills, workspace.

**This fork adds**: file operations, shell, task tools, calculator, database query, Python engine, web scraping, and two divination tools — plus a **Python / JS dual bridge** (read/write conversations, assistant settings, group chats; run Python / JS engines) and a **system-prompt assembler** (divination workflow / tool guide / work ethics).

### Stability

- **Background keep-alive**: foreground service with async start, 600 ms debounce, and failure fallback — switching apps doesn't interrupt generation
- Removed upstream's unused extras (GitHub tool, sleep, knowledge base, log-debug pages — see [DIVERGENCE.md](DIVERGENCE.md) §5; do not re-add)

---

## ✅ Relationship to upstream

- **Everything preserved**: file-level verification — every upstream file has a counterpart here, no functionality removed (only 4 file groups moved / rewritten). Material You theming, multi-provider support, streaming, conversation forking & regeneration, message edit / delete / translate, full-text search (jieba), favorites, image generation, TTS / ASR, MCP, workspace sandbox, backup (S3 / WebDAV / reminders), web server, and chat export all work as before.
- **Scale of divergence**: ~626 files changed, +66,000 lines, 1900+ commits ahead of upstream; upstream updates can be pulled in anytime via `git fetch upstream && git merge upstream/master` (conflict handbook: [DIVERGENCE.md](DIVERGENCE.md)).

## 🔗 Links

- Upstream project: [RikkaHub](https://github.com/rikkahub/rikkahub)
- Divergence & merge handbook: [DIVERGENCE.md](DIVERGENCE.md)

---

If this fork is useful to you, please leave a ⭐ Star — it keeps the project alive ✨