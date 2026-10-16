# DIVERGENCE — 本地分支与上游 rikkahub 的差异地图

> 用途：合并上游（`git fetch upstream && git merge upstream/master`）时的冲突处理手册。
> 维护：每次合入上游后更新本节“状态”；每次改动核心文件后更新对应条目。

## 0. 当前状态

| 项 | 值 |
|---|---|
| 本地分支 | `huadeng` |
| 上游 | `rikkahub/master`（github.com/rikkahub/rikkahub，最上游） |
| 最近合入 | `rikkahub/master`（2026-10-16 合入 3 提交：data 包重组拆出 skills/合并 remote、OpenAI 流式 null 行容错、Wan 系列图标匹配） |
| 本次冲突 | 6 个文件，全部人工解决；无数据库迁移 |
| 领先提交数 | 约 1920+ |
| 上游文件被本地修改 | 约 100 个（含重命名） |
| 本地独有文件 | 约 50 个 |

### 本次（2026-10-16）合并的固定约定

- **`data` 包重组（上游 fffa6e82e）已全量接受**：`data.files.Skill*` → `data.skills`、`data.files.RemoteFileStore`/`data.network.*` → `data.remote`、`data.api.*` → `data.remote`、`data.favorite.*` → `data.repository`、`data.export.ExportHooks` → `ui.hooks`、`data.provider.WorkspaceDocumentsProvider` → `service`。
- **本地独有的技能域文件必须跟着上游走**：`data/files/PluginManifest.kt`、`data/files/SkillRegistry.kt` 同属技能域，已一并迁到 `data/skills/`。`SkillManager` 在 `data.skills` 内同包裸调用 `parsePluginManifest`/`listCommands`/`parseMcpJson`，留旧包会编译失败。新增技能相关文件一律放 `data.skills/`。
- **`.data.files` 现在只剩 `FilesManager`/`FileUtils`/`MediaCreationFiles`**：`FileFolders` 仍在此包，`data.skills` 反向 import 它，不要把它一起搬走。
- **`ui/pages/extensions/skills/*` 保持删除**：本地已把技能页重写到 `ui/pages/extensions/`（`SkillsPage`/`SkillsVM`/`SkillDetailPage`/`SkillDetailVM`），上游对这些文件的改动不回收。本期上游只改了包名 import，同样丢弃。
- **上游 `OpenAI` 流式容错（240deb5fe）已收**：`ChatCompletionsStreamDecoder` 改用 `me.rerere.common.http.jsonObjectOrNull`/`jsonArrayOrNull`，本地同步改用同一套扩展，不要再引入 `kotlinx.serialization.json.jsonObject` 强转（中转混入 `data: null` 会崩）。

- **依赖**：上游删掉了 `sonner` 与 `lucide`。本地全量迁移到上游自研 `me.rerere.ui.toast.Toaster`（`ToasterState` / `ToastType` / `rememberToasterState`），`sonner` 不再引入；`lucide` 本地仍有 5 个页面在用，故在 `gradle/libs.versions.toml` 与 `app/build.gradle.kts` 保留 `lucide-icons`。
- **数据库**：上游把这轮新表算成 v30 且用 `AutoMigration(20→30)`，本地 20→31 是手写迁移链，**不要**把上游的 `AutoMigration(20,21)…(29,30)` 加进 `autoMigrations`（会与 `DataSourceModule` 的 `addMigrations` 重复定义）。上游新增表一律在后接一个手写迁移；本次即 `Migration_31_32`。
- **`TTSProviderSetting.Volcengine` 本地已改名 `Doubao`**：上游文件里出现 `TTSProviderSetting.Volcengine` 的地方取上游版本后必须改回 `Doubao`（`SettingSpeechPage.kt`、`TTSProviderConfigure.kt`）。`ASRProviderSetting.Volcengine` 未改名，保持原样。
- **`PagerTab` 接口**：上游把 `label` 改成 `@Composable get(): String`。本地新增的 Tab 枚举要用 `@param:StringRes private val labelRes: Int` + `override val label: String @Composable get() = stringResource(labelRes)`。
- **不要丢功能**：上一轮合并曾把「工作区长按多选导出」丢掉（`WorkspaceDetailPage.kt` + `WorkspaceDetailVM.kt` 成对回退）。此后凡 `WorkspaceDetail*` 冲突，`theirs` 侧带 `prepareBatchExport`/`exportFilesToDirectory`/`exportResult` 一律收下。
- **界面优先上游**：页面骨架、顶栏、组件风格以上游为准；本地只在其中保留自己的入口与字段。`TranslatorPage.kt`、`WorkspaceDetailPage.kt` 直接取上游版本。
- **本地弃用/重写的上游页**：`ui/pages/extensions/skills/*`（本地重写在 `ui/pages/extensions/`）、`ui/pages/imggen/ImgGenPage.kt`（本地弃用，生图走媒体创作）保持删除，上游对它们的改动不回收。
- **显示设置的缩放**：本地在 `DisplaySetting` 里加了 `codeBlockFontSizeRatio`（默认 1.0），代码块字号独立于正文字号。原生渲染在 `HighlightCodeBlock` 的 `textStyle` 收口，WebView 侧靠 `WebChatSurface.buildAppearanceJson` 下发 `codeFontScale` → `app.js` 的 `--code-font-scale` → `index.html` 的 `calc()`，**两边必须同时改**。
- **`DisplaySetting` 加字段安全**：整份设置以 JSON 落盘，新字段给默认值即可，老数据反序列化缺字段走默认，不需要 dataStore 迁移或注册表改动。
- **测试**：CI 只跑 `assembleRelease`，不跑 `test`。`app/src/test/.../capability/` 下 `ChubCardImportTest`/`ChunshuiImportTest`/`FengyueImportTest`/`JsonNullCardTest` 依赖本机把样本卡片放在仓库根目录（实际在 `酒馆500+美化/`），标准检出下必然 NPE，与合并无关。`speech` 的 `VolcengineTTSProviderTest` 引用的是本地改名前的 API，已删除（实现重写后那些 `*VolcengineTTS*` 内部符号已不存在）。

## 1. 合并工作流（推荐）

1. **频繁合**：上游每次更新就 `git fetch upstream`，需要时 `git merge upstream/master`。不要攒几百个提交再合。
2. 2026-08-13 合入注意事项：AI 流式接口已改为 `StreamChunkHandler` / `handleTextGenerationResult`（旧 `handleMessageChunk`/`MessageChunk` 已删除）；搜索改为 `SearchMode`（OFF/LOCAL/BUILT_IN）；keep 规则迁移到 `app/src/main/keepRules/rikkahub.keep`（本地 Chaquopy/Compose 规则已并入，不要再改 `app/proguard-rules.pro`）；应用版本与上游一致（2.4.6/173）；更新检查已切换到本仓库 `update.json`（`raw.githubusercontent.com/MiaoWuNYA/rikkahub-sillytavern-android/huadeng/update.json`），不再指向官方服务器。以后发版：改 `update.json`（版本号+下载链接）+ 发布 GitHub Release，用户端即可收到更新。。
2. 冲突按本文件分类处理：
   - 本地**独有文件**（第 3 节）→ 永不冲突，忽略。
   - **核心生成链**（第 2 节 A 组）→ 人工逐块审，两边语义都要保留。
   - **酒馆/工具/群聊**（本地方向，上游没有）→ 冲突时以上游文件为基底，把本地功能重新叠上去。
   - **可回退项**（第 5 节）→ 尽量保持上游原样。
3. 合并后本地检查（不本地编译）：`git diff --check`；推送后靠 CI 验证。
4. 若上游新增了本地没有的功能，且不与本地冲突 → 直接收下，并补进本文件。

## 2. 上游文件被本地修改（按合并风险分组）

### A. 核心生成链（最高风险，冲突需人工审）

| 文件 | 改动量 | 本地改了什么 | 合并建议 |
|---|---|---|---|
| `service/ChatService.kt` | +509/-55 | 工具构建、前台服务、群聊生成、斜杠注入、发送链路 | 上游更新先合，再叠本地逻辑 |
| `data/ai/GenerationHandler.kt` | +638/-185 | 系统提示组装、transform 链、预构建 system | 同上 |
| `data/ai/transformers/PromptInjectionTransformer.kt` | +540/-11 | 世界书官方对齐（选择性逻辑/分组/递归/粘性/预算） | 本地逻辑已对照酒馆官方，合时保留 |
| `data/ai/transformers/PlaceholderTransformer.kt` | +358/-6 | 宏引擎 2.0 接入、`{{original}}` 等修正 | 保留本地 |
| `data/ai/transformers/Transformer.kt` | +36/-2 | TransformerContext 扩展字段 | 保留 |
| `data/model/Assistant.kt` | +382/-22 | 工具/技能/群聊/酒馆/宏/记忆/滚动压缩 字段 | 合时保留本地字段 |
| `data/model/Conversation.kt` | +3 | 小改 | 低风险 |
| `data/datastore/PreferencesStore.kt` | +105/-1 | 本地设置（群聊/酒馆/工具/压缩等） | 保留本地设置项 |
| `data/ai/GenerationPrompts.kt` | +33 | 本地提示词 | 保留 |
| `ai/ui/Message.kt` | +184 | 消息模型扩展（UIMessagePart/Annotation 合并进此文件） | 上游也改此文件时容易冲突 |
| `ai/registry/ModelRegistry.kt` | ±20 | 本地模型注册 | 低风险 |

### B. 酒馆兼容（本地独有方向，上游没有对应功能）

| 文件 | 改动量 | 说明 |
|---|---|---|
| `ui/pages/assistant/detail/TavernCharacterCard.kt` | +1796 | 角色卡详情/内嵌世界书编辑器（本地新增） |
| `ui/pages/assistant/detail/AssistantImporter.kt` | +778/-183 | 角色卡导入解析（V2/V3、世界书、PHI、深度提示） |
| `ui/pages/extensions/PromptPage.kt` | +1169/-55 | 世界书/提示注入编辑页 |
| `data/model/TavernCard.kt` | +118 | 角色卡数据模型 |
| `utils/CardExporter.kt` | +317 | 角色卡导出（PNG/JSON） |
| `data/ai/transformers/AuthorsNoteTransformer.kt` / `ui/pages/setting/AuthorsNotePage.kt` | +84 / +413 | 导演备注（官方语义） |
| `data/model/Persona.kt` / `ui/pages/setting/PersonaPage.kt` | +28 / +680 | 人设 |
| `data/model/AuthorNotePosition.kt` / `GenerationType.kt` | +44 / +29 | 枚举 |
| `data/ai/transformers/MacroEngine.kt` | +879 | 宏引擎 2.0 |
| `ui/components/ai/SlashCommands.kt` / `MacroVarSlashOps.kt` | +257 / +92 | 斜杠命令 |
| `ui/pages/extensions/PromptVM.kt` | +77 | 世界书双向同步 |
| `ui/pages/chat/WebCardShared.kt` + `assets/webui/` | 新文件 | WebView 消息区模式：单 WebView 承载消息区（MD3 页面 + 卡片 iframe 桥 + 宏），导入角色卡时自动识别 |
| `ui/pages/assistant/detail/AssistantImporter.kt`（导入后钩子） | — | 命中前端卡时为助手置位并提示已自动切换 |
| `ui/pages/setting/SettingHuaDengCompatPage.kt` / `SettingTtsPlaybackPage.kt` | 新文件 | 华灯「兼容与辅助」与 TTS 朗读设置各自拆成独立页 |

### C. 群聊（本地独有）

`data/model/GroupChat.kt`(+52)、`GroupSpeakerSelector.kt`(+148)、`ui/pages/chat/GroupChatPage.kt`(+1167)、`GroupChatListPage.kt`(+242)

### D. 工具与 Agent（本地独有）

`data/ai/tools/LocalTools.kt`(+489，从 `tools/local/` 移动)、`FileTools.kt`(+430)、`TaskTools.kt`(+430)、`DatabaseQueryTool.kt`(+326)、`ShellTools.kt`(+81)、`PythonTools.kt`(+152)、`CalculatorTool.kt`(+127)、`WebFetchTool.kt`(+111)、`tools/local/MingliTool.kt`(+138)、`MingliGuideTool.kt`(+111)、`data/ai/python/PythonBridge.kt`(+235)、`JsBridge.kt`(+37)、`data/ai/prompts/SystemPromptAssembler.kt`(+134)、`data/ai/transformers/SkillAutoTriggerTransformer.kt`(+94)、`data/files/PluginManifest.kt`(+119)、`SkillRegistry.kt`(+43)、`SkillFrontmatterParser.kt`(+21)

上游的 `data/ai/tools/SkillsTools.kt` 本地改了 +64/-35（技能工具）；`data/files/SkillManager.kt` +78/-5（**已回退缓存改动**，仅剩外部存储/公共目录两个早期差异，见第 5 节）。

### E. 数据库与迁移

| 文件 | 说明 |
|---|---|
| `data/db/AppDatabase.kt` | 版本 26；本地实体增减（知识库实体已删）、DAO 增删 |
| `data/db/migrations/Migration_20_21.kt` ~ `25_26.kt` | 本地新增/修改；其中 20_21/22_23 曾建知识库表，25_26 删除。**迁移链不可删**，否则升级崩溃 |
| `data/db/dao/MessageNodeDAO.kt` | +3，小改 |
| `data/sync/UpstreamCompat.kt` | 上游兼容导出：pin 的是官方发布版 2.5.6 的 schema（v25，`app/schemas/.../25.json` 的版本号与 identityHash）；官方发新版后同步更新这里 |

### F. 路由 / DI / Web

`RouteActivity.kt` ±1501（本地页面入口最多，冲突高）、`RikkaHubApp.kt` +36/-49、`di/AppModule.kt`、`DataSourceModule.kt`、`RepositoryModule.kt`、`ViewModelModule.kt`。
Web 相关只有小改（`web/routes/ConversationRoutes.kt` +47/-25、`SettingsRoutes.kt` +15、`WebApiModule.kt` +12、`FolderRoutes.kt` +6、`WebServerManager.kt` +4）——**约定：尽量不动 web**。

### G. 其余 UI/工具

`ui/components/ai/ChatInput.kt`(+455)、`ui/pages/chat/ChatPage.kt`(+312/-79)、`ChatDrawer.kt`(+173/-189)、`ChatDrawerVM.kt`、`ChatVM.kt`(+54)、`ui/components/message/ChatMessageTools.kt`(+774)、`ChatMessage.kt`、`ChatMessageActions.kt`、`AssistantDetailPage.kt`(+519)、`AssistantDetailVM.kt`、`AssistantLocalToolPage.kt`(+169/-7)、`AssistantBasicPage.kt`、`AssistantVM.kt`、`AssistantPage.kt`、`SettingPage.kt`(+183)、`SettingPreferencesUIPage.kt`(+326)、`ui/pages/chat/Export.kt`、`utils/ImageUtils.kt`、`ContextUtil.kt`、`CrashHandler.kt`、`service/ChatNotificationManager.kt`、`ui/components/richtext/Markdown.kt`、`MarkdownNew.kt`、`FilesPicker.kt`、`ChatList.kt`、`data/repository/ConversationRepository.kt`(+32/-50)、`FolderRepository.kt`、`data/export/ExportSerializer.kt`(+85/-8)

## 3. 本地独有文件（永不参与上游冲突）

约 50 个新文件，包括：
- 酒馆：`data/ai/transformers/ContextInjectorTransformer.kt`、`ui/components/ai/SlashCommands.kt`、`MacroVarSlashOps.kt`、`utils/CardExporter.kt` 等
- 群聊：`GroupChat.kt`、`GroupChatPage.kt`、`GroupChatListPage.kt`、`GroupSpeakerSelector.kt`
- 工具：`FileTools.kt`、`TaskTools.kt`、`DatabaseQueryTool.kt`、`ShellTools.kt`、`PythonTools.kt`、`CalculatorTool.kt`、`WebFetchTool.kt`、`MingliTool.kt`、`MingliGuideTool.kt`、`PythonBridge.kt`、`JsBridge.kt`、`SystemPromptAssembler.kt`、`SkillAutoTriggerTransformer.kt`
- 宏/斜杠：`MacroEngine.kt`
- WebView 消息区模式：`ui/pages/chat/WebChatSurface.kt` + `WebCardShared.kt` + `app/src/main/assets/webui/`（index.html / app.js / markdown.js / compat.js）。早期版本的 `FrontendCardChatView.kt` 与 `assets/frontend-card/` 已在 `68920fd62` 重写时删除，**不要因为看到旧文档就把它们找回来**。上游这两处都没有，冲突不适用；改外观相关设置时两处要一起改（Compose 侧 `buildAppearanceJson` 下发字段，前端 `applyAppearance` 落 CSS 变量）
- 设置拆分页：`SettingHuaDengCompatPage.kt`、`SettingTtsPlaybackPage.kt`
- 服务：`service/GenerationForegroundService.kt`
- 备份：`data/sync/UpstreamCompat.kt`（备份页「导出上游兼容备份」，对齐官方发布版 2.5.6：settings.json 只留上游顶层字段，多态对象按上游序列化名白名单过滤——本地新增的工具/子类会自动丢弃，注入位置与豆包 TTS 换成上游写法；数据库降到 v25 并改写 identityHash，按白名单删表删列、补出上游独有的 MemoryEntity）。官方发新版本后同步更新里面的版本号、identityHash 与各类白名单

合并时这些文件直接保留本地版本即可。

## 4. 上游文件被本地删除/移动

| 文件 | 处理 |
|---|---|
| `data/ai/tools/local/JavascriptTool.kt` / `LocalToolOption.kt` / `LocalTools.kt` | 移动到 `data/ai/tools/`（功能保留） |
| `ui/pages/extensions/skills/SkillsPage.kt` / `SkillsVM.kt` / `SkillDetailPage.kt` / `SkillDetailVM.kt` | 移动到 `ui/pages/extensions/`（本地重写） |
| `ai/ui/UIMessagePart.kt` / `UIMessageAnnotation.kt` | 合并进 `ai/ui/Message.kt` |
| `ui/pages/setting/SettingMcpPage.kt` | 本地移除（MCP 设置并入别处） |
| `data/ai/tools/GitHubTool.kt`、`SleepTool.kt`、知识库整套 | 已按“上游没有”删除，**不要再加回** |

## 5. 已对齐 / 已回退项（保持）

- GitHub 工具及其 UI：已删（上游没有）
- sleep 工具：已删（上游没有）
- 知识库整套：已删（上游没有；迁移链保留，25_26 清理旧表）
- SkillManager：已回退本轮缓存改动；**剩余两个早期差异**（技能目录用外部存储 + `/Rikkahub/skills` 公共目录）是技能安装依赖，默认保留
- MCP：工具名与校验对齐上游
- 文件工具：已去掉 skill 目录拼接
- 日志调试（DeveloperPage/AILogging）：已删（上游没有）

## 6. 冲突处理速查

1. `ChatService.kt` / `GenerationHandler.kt`：上游改动先收，本地功能块（工具构建、transform 链）重新叠上去。
2. `RouteActivity.kt`：各页面 entry 是追加式，冲突通常可两边都留。
3. `Assistant.kt` / `PreferencesStore.kt`：字段是追加式，上游删字段时检查本地是否在用。
4. 数据库：上游加 migration 时，注意本地版本号（当前 26）与迁移链；不要在本地重写已发布的迁移。
5. 合完：`git diff --check` + 推送 + CI；**不在本地编译**。
