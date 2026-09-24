# DIVERGENCE — Map of local-branch divergence from upstream rikkahub

> Purpose: conflict-handling handbook for merging upstream (`git fetch upstream && git merge upstream/master`).
> Maintenance: update the "Status" section after each upstream merge; update entries after changing core files.

## 0. Current status

| Item | Value |
|---|---|
| Local branch | `huadeng` |
| Upstream | `rikkahub/master` (github.com/rikkahub/rikkahub, topmost upstream) |
| Last merge | `rikkahub/master` (merged 2026-09-03, incl. Settings refactor / Provider interface extensions / voice-video generation / oauth / workspace) |
| Conflicts last time | 17, all resolved manually (incl. GenerationHandler generation chain / ChatService / PreferencesStore / RouteActivity) |
| Commits ahead | ~1920+ |
| Upstream files modified locally | ~100 (incl. renames) |
| Local-only files | ~50 |

## 1. Merge workflow (recommended)

1. **Merge often**: run `git fetch upstream` on every upstream update and `git merge upstream/master` when needed. Do not let hundreds of commits pile up.
2. Notes from the 2026-08-13 merge: the AI streaming interface is now `StreamChunkHandler` / `handleTextGenerationResult` (old `handleMessageChunk`/`MessageChunk` removed); search is now `SearchMode` (OFF/LOCAL/BUILT_IN); keep rules migrated to `app/src/main/keepRules/rikkahub.keep` (local Chaquopy/Compose rules merged in — do not edit `app/proguard-rules.pro` anymore); app version tracks upstream (2.4.6/173); the update check points at this repo's `update.json` (`raw.githubusercontent.com/MiaoWuNYA/rikkahub-sillytavern-android/huadeng/update.json`), not the official server. For releases: update `update.json` (version + download link) + publish a GitHub Release, and clients pick it up.
2. Resolve conflicts by category:
   - Local-**only files** (section 3) → never conflict, ignore.
   - **Core generation chain** (section 2 group A) → manual review block by block; preserve both sides' semantics.
   - **Tavern/tools/group chats** (local direction, absent upstream) → on conflict, take the upstream file as base and re-apply local features on top.
   - **Reverted items** (section 5) → keep upstream as-is where possible.
3. Post-merge local checks (no local build): `git diff --check`; rely on CI after push.
4. If upstream adds features the local branch lacks without conflict → accept them and record here.

## 2. Upstream files modified locally (grouped by merge risk)

### A. Core generation chain (highest risk, manual review on conflict)

| File | Delta | What changed locally | Merge advice |
|---|---|---|---|
| `service/ChatService.kt` | +509/-55 | tool building, foreground service, group-chat generation, slash injection, send chain | merge upstream first, then re-apply local logic |
| `data/ai/GenerationHandler.kt` | +638/-185 | system-prompt assembly, transformer chain, prebuilt system | same |
| `data/ai/transformers/PromptInjectionTransformer.kt` | +540/-11 | official lorebook alignment (selective logic / groups / recursion / sticky / budget) | local logic mirrors official tavern; preserve it |
| `data/ai/transformers/PlaceholderTransformer.kt` | +358/-6 | macro engine 2.0 wiring, `{{original}}` etc. fixes | keep local |
| `data/ai/transformers/Transformer.kt` | +36/-2 | TransformerContext extra fields | keep |
| `data/model/Assistant.kt` | +382/-22 | tools/skills/group-chat/tavern/macro/memory/rolling-compression fields | keep local fields |
| `data/model/Conversation.kt` | +3 | minor | low risk |
| `data/datastore/PreferencesStore.kt` | +105/-1 | local settings (group chat / tavern / tools / compression etc.) | keep local settings |
| `data/ai/GenerationPrompts.kt` | +33 | local prompts | keep |
| `ai/ui/Message.kt` | +184 | message-model extensions (UIMessagePart/Annotation merged here) | conflicts easily when upstream touches it too |
| `ai/registry/ModelRegistry.kt` | ±20 | local model registry | low risk |

### B. Tavern compatibility (local-only direction, no upstream counterpart)

| File | Delta | Notes |
|---|---|---|
| `ui/pages/assistant/detail/TavernCharacterCard.kt` | +1796 | character-card detail / embedded lorebook editor (new) |
| `ui/pages/assistant/detail/AssistantImporter.kt` | +778/-183 | card import parsing (V2/V3, lorebook, PHI, depth prompts) |
| `ui/pages/extensions/PromptPage.kt` | +1169/-55 | lorebook / prompt-injection editor |
| `data/model/TavernCard.kt` | +118 | card data model |
| `utils/CardExporter.kt` | +317 | card export (PNG/JSON) |
| `data/ai/transformers/AuthorsNoteTransformer.kt` / `ui/pages/setting/AuthorsNotePage.kt` | +84 / +413 | author's note (official semantics) |
| `data/model/Persona.kt` / `ui/pages/setting/PersonaPage.kt` | +28 / +680 | personas |
| `data/model/AuthorNotePosition.kt` / `GenerationType.kt` | +44 / +29 | enums |
| `data/ai/transformers/MacroEngine.kt` | +879 | macro engine 2.0 |
| `ui/components/ai/SlashCommands.kt` / `MacroVarSlashOps.kt` | +257 / +92 | slash commands |
| `ui/pages/extensions/PromptVM.kt` | +77 | lorebook two-way sync |

### C. Group chats (local-only)

`data/model/GroupChat.kt`(+52), `GroupSpeakerSelector.kt`(+148), `ui/pages/chat/GroupChatPage.kt`(+1167), `GroupChatListPage.kt`(+242)

### D. Tools & Agent (local-only)

`data/ai/tools/LocalTools.kt`(+489, moved from `tools/local/`), `FileTools.kt`(+430), `TaskTools.kt`(+430), `DatabaseQueryTool.kt`(+326), `ShellTools.kt`(+81), `PythonTools.kt`(+152), `CalculatorTool.kt`(+127), `WebFetchTool.kt`(+111), `tools/local/MingliTool.kt`(+138), `MingliGuideTool.kt`(+111), `data/ai/python/PythonBridge.kt`(+235), `JsBridge.kt`(+37), `data/ai/prompts/SystemPromptAssembler.kt`(+134), `data/ai/transformers/SkillAutoTriggerTransformer.kt`(+94), `data/files/PluginManifest.kt`(+119), `SkillRegistry.kt`(+43), `SkillFrontmatterParser.kt`(+21)

Upstream's `data/ai/tools/SkillsTools.kt` changed locally +64/-35 (skill tools); `data/files/SkillManager.kt` +78/-5 (**cache changes reverted**, only two early diffs remain — external-storage skill dir + `/Rikkahub/skills` public dir, see section 5).

### E. Database & migrations

| File | Notes |
|---|---|
| `data/db/AppDatabase.kt` | version 26; local entity changes (knowledge-base entities removed), DAO changes |
| `data/db/migrations/Migration_20_21.kt` ~ `25_26.kt` | local additions/modifications; 20_21/22_23 once created knowledge-base tables, 25_26 drops them. **Never delete the migration chain**, or upgrades crash |
| `data/db/dao/MessageNodeDAO.kt` | +3, minor |

### F. Routing / DI / Web

`RouteActivity.kt` ±1501 (most local page entries, high conflict), `RikkaHubApp.kt` +36/-49, `di/AppModule.kt`, `DataSourceModule.kt`, `RepositoryModule.kt`, `ViewModelModule.kt`.
Web changes are small (`web/routes/ConversationRoutes.kt` +47/-25, `SettingsRoutes.kt` +15, `WebApiModule.kt` +12, `FolderRoutes.kt` +6, `WebServerManager.kt` +4) — **convention: avoid touching web**.

### G. Other UI/tools

`ui/components/ai/ChatInput.kt`(+455), `ui/pages/chat/ChatPage.kt`(+312/-79), `ChatDrawer.kt`(+173/-189), `ChatDrawerVM.kt`, `ChatVM.kt`(+54), `ui/components/message/ChatMessageTools.kt`(+774), `ChatMessage.kt`, `ChatMessageActions.kt`, `AssistantDetailPage.kt`(+519), `AssistantDetailVM.kt`, `AssistantLocalToolPage.kt`(+169/-7), `AssistantBasicPage.kt`, `AssistantVM.kt`, `AssistantPage.kt`, `SettingPage.kt`(+183), `SettingPreferencesUIPage.kt`(+326), `ui/pages/chat/Export.kt`, `utils/ImageUtils.kt`, `ContextUtil.kt`, `CrashHandler.kt`, `service/ChatNotificationManager.kt`, `ui/components/richtext/Markdown.kt`, `MarkdownNew.kt`, `FilesPicker.kt`, `ChatList.kt`, `data/repository/ConversationRepository.kt`(+32/-50), `FolderRepository.kt`, `data/export/ExportSerializer.kt`(+85/-8)

## 3. Local-only files (never conflict with upstream)

~50 new files, including:
- Tavern: `data/ai/transformers/ContextInjectorTransformer.kt`, `ui/components/ai/SlashCommands.kt`, `MacroVarSlashOps.kt`, `utils/CardExporter.kt`, etc.
- Group chats: `GroupChat.kt`, `GroupChatPage.kt`, `GroupChatListPage.kt`, `GroupSpeakerSelector.kt`
- Tools: `FileTools.kt`, `TaskTools.kt`, `DatabaseQueryTool.kt`, `ShellTools.kt`, `PythonTools.kt`, `CalculatorTool.kt`, `WebFetchTool.kt`, `MingliTool.kt`, `MingliGuideTool.kt`, `PythonBridge.kt`, `JsBridge.kt`, `SystemPromptAssembler.kt`, `SkillAutoTriggerTransformer.kt`
- Macros/slash: `MacroEngine.kt`
- Services: `service/GenerationForegroundService.kt`

On merge, keep the local version of these files as-is.

## 4. Upstream files deleted/moved locally

| File | Handling |
|---|---|
| `data/ai/tools/local/JavascriptTool.kt` / `LocalToolOption.kt` / `LocalTools.kt` | moved to `data/ai/tools/` (functionality kept) |
| `ui/pages/extensions/skills/SkillsPage.kt` / `SkillsVM.kt` / `SkillDetailPage.kt` / `SkillDetailVM.kt` | moved to `ui/pages/extensions/` (locally rewritten) |
| `ai/ui/UIMessagePart.kt` / `UIMessageAnnotation.kt` | merged into `ai/ui/Message.kt` |
| `ui/pages/setting/SettingMcpPage.kt` | removed locally (MCP settings folded elsewhere) |
| `data/ai/tools/GitHubTool.kt`, `SleepTool.kt`, whole knowledge-base module | deleted as "upstream doesn't have it" — **do not add back** |

## 5. Aligned / reverted items (keep as-is)

- GitHub tool and its UI: deleted (upstream doesn't have it)
- sleep tool: deleted (upstream doesn't have it)
- Knowledge base module: deleted (upstream doesn't have it; migration chain kept, 25_26 drops the old tables)
- SkillManager: this round's cache changes reverted; **two early diffs remain** (skill dir on external storage + `/Rikkahub/skills` public dir) — required by skill installation, keep
- MCP: tool names and validation aligned with upstream
- File tools: skill-dir concatenation removed
- Log debugging (DeveloperPage/AILogging): deleted (upstream doesn't have it)

## 6. Conflict-resolution quick reference

1. `ChatService.kt` / `GenerationHandler.kt`: take upstream changes first, re-apply local feature blocks (tool building, transformer chain) on top.
2. `RouteActivity.kt`: page entries are append-style; conflicts can usually keep both sides.
3. `Assistant.kt` / `PreferencesStore.kt`: fields are append-style; when upstream deletes a field, check whether local code still uses it.
4. Database: when upstream adds migrations, mind the local version number and migration chain; never rewrite published migrations locally.
5. After merging: `git diff --check` + push + CI; **no local build**.
