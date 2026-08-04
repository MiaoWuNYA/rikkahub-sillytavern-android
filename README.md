# RikkaHub Plus — 命理 & 酒馆增强版

> 基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的深度定制分支（Android 原生 LLM 聊天客户端），上游全部能力完整保留。
> 下文按"上游有什么 / 本地在上游基础上做了什么"对照代码说明；逐文件差异与合并工作流见 [DIVERGENCE.md](DIVERGENCE.md)。

**核心亮点**：酒馆角色卡 / 世界书系统全面强化（字段无损导入导出、官方注入结构）、全新命理排盘系统（八套体系）、宏引擎 2.0 与斜杠命令、技能自动触发与 Python / JS 双桥接。

---

## 🍺 酒馆系统（全部相关强化都在这里）

### 1. 角色卡：导入 → 结构化 → 注入 → 导出 → 编辑

**上游有什么**：JSON（V2/V3）和 PNG 都能导入，但只解析 6 个字段（name / first_mes / system_prompt / description / personality / scenario），然后拼成一段 system prompt 字符串；**没有导出**。

**本地在上游基础上：**

- **导入字段从 6 个扩到 22 个，全部结构化保留**：新增 mes_example（示例对话）、alternate_greetings（备选开场白）、creator_notes（作者备注）、post_history_instructions（历史后指令 PHI）、character_version、tags、creator、creator_notes_multilingual、source、creation/modification_date、nickname、assets、group_only_greetings、**character_book（内嵌世界书）**、**extensions（深度提示等）**。上游会把除了 6 个以外的字段全丢掉，本地一个不丢。
- **注入方式不同**：上游把字段拼成大字符串塞进 system prompt；本地按官方 Chat Completion 结构拆分——主提示、角色卡字段独立消息、示例消息（按 `<START>` 分块解析成真正的 user/assistant 对话）、PHI 放历史末尾、深度提示按深度/角色注入。AI 看到的角色信息结构更清晰。
- **新增导出**：PNG / JSON 无损导出（上游没有导出功能），导出字段名对齐官方规范（insertion_order、extensions.*），导入再导出不丢东西。
- **新增角色卡详情编辑页**：22 个字段可视化编辑、内嵌世界书管理、导出按钮。

### 2. 世界书（Lorebook）

**上游有什么**：关键词包含匹配 → 注入内容，支持扫描深度、常驻条目、优先级、注入位置。

**本地在上游基础上新增/强化：**

- 主/副关键词 **四档逻辑**（AND_ANY / AND_ALL / NOT_ANY / NOT_ALL），主键必须命中，官方语义
- **跨世界书分组**：同组只激活一条，粘性优先 → 关键词评分 → 覆盖优先 → 加权随机
- **递归扫描**：用已激活条目的内容继续扫，缓冲逐层累积，支持 exclude/prevent 控制、delay_until_recursion 数字层级逐级开放
- **粘性 / 冷却**：激活后保留 N 轮、到期自动进冷却，按对话隔离不串
- **触发概率**、**token 预算 + ignore_budget 豁免**
- **官方正则键**（`/pattern/flags` 自动识别）
- **match_***：逐条控制是否扫描角色卡字段（描述/性格/场景/作者备注等）
- **示例消息前后锚点**（EM Top/Bottom）注入
- 条目导入导出字段完整（insertion_order、extensions.*）

### 3. 宏引擎 2.0

**上游有什么**：简单的占位符替换（`{{char}}`、`{{user}}` 之类）。

**本地在上游基础上**：把提示词当"程序"写——

- **变量系统**：`/setvar`、`/getvar`、`/incvar` 等命令管理对话变量，宏里用 `{{getvar::key}}` 或 `.key` 简写读取，同一张卡能随剧情状态自动切换说法
- **条件逻辑**：`{{if}} / {{else}} / !`、比较运算符、`&&` / `||`，支持分支与嵌套
- **随机与时间**：`{{pick::A|B|C}}`（同轮稳定随机）、`{{roll::1d20}}`、`{{random}}`、时间与间隔宏
- **对话感知**：`{{lastUserMessage}}`、`{{lastCharMessage}}`、`{{idleDuration}}`、`{{charFirstMessage::N}}`、`{{original}}` 等等
- 未知宏原样保留，不会破坏模板

### 4. 斜杠命令

**上游没有**。本地新增（输入框直接输入即执行，`/help` 随时查看全部命令与说明；无参数命令点击直接执行，带参数命令点击自动填入输入框补参数后发送）：

- **角色扮演**：`/impersonate` 生成你的发言草稿填入输入框（AI 以你的视角拟话，可加补充说明，确认后发送）、`/continue` 在原回复末尾继续生成、`/sendas` / `/sys` 直接插入助手 / 系统消息（不触发生成）
- **操控生成**：`/trigger` 不新增消息直接触发回复、`/sysgen` 让 AI 写系统旁白、`/inject` 注入提示词而不污染聊天记录
- **状态与调试**：`/persona` 切换人设、`/prompt` 预览实际发送给 AI 的完整提示词
- **角色卡管理**：`/char-get`、`/char-update`、`/char-duplicate`、`/rename-char`
- **变量与随机**：`/listvar`、`/setvar`、`/getvar`、`/addvar`、`/incvar`、`/decvar`、`/flushvar`、`/reroll-pick`（重新掷 `{{pick}}` 稳定随机）
- 等等，共 22 个内置命令，语义对照酒馆官方实现；技能目录里的命令会随技能自动出现

### 5. 人设（Persona）

**上游没有**。本地新增：官方五档注入位置（IN_PROMPT / TOP / BOTTOM / AT_DEPTH / NONE）、按角色绑定、独立 SYSTEM 消息注入、禁用即不注入。

### 6. 导演备注（Author's Note）

**上游没有**。本地新增：官方间隔语义（1=每次 / N=用户消息倍数）、注入深度、注入角色、总开关。

### 7. 群聊

**上游没有**。本地新增：多人角色共同对话，每角色独立提示词/人设/模型；四种选人策略（自然/名单/加权/手动）；自动接话（轮数、延迟可配，用户发言即打断）；发言人状态提示。

---

## 🔮 命理系统（全新，上游没有）

- **排盘引擎**：现代西洋占星、传统西洋占星、印度占星（吠陀）、深度古典占星、八字、紫微斗数、塔罗、雷诺曼
- **时间技法**：行运、推运、返照、小限、Firdaria、寿元、Almuten、阿拉伯点、中点、映点、龙首盘
- **双模式**：确定性排盘工具（`mingli`）+ 引擎自探索（JavaScript / Python 桥接），一个开关同时控制两个命理工具

---

## 🚀 其他比上游强的地方

### 技能系统

**上游有什么**：读 `SKILL.md` + `use_skill` 工具（模型主动调用才能加载）。

**本地在上游基础上：**

- **新增自动触发**：命中技能关键词时自动把 SKILL.md 注入提示词，不依赖模型自觉
- **新增公共技能目录** `/Rikkahub/skills`：文件管理器直接放进去就能识别
- **技能目录改用外部存储**：不再锁在应用私有目录里
- **新增 GitHub 技能安装**：按仓库/marketplace 直接装
- **技能页/详情页重写** + `use_skill` 工具增强（按分类组织、linked_files、命令提示）

### 工具集

**上游有什么**：时间、剪贴板、日历、JavaScript、屏幕时间、TTS、提问 + 对话、记忆、搜索、技能、工作区工具。

**本地在上游基础上新增**：文件操作、Shell 命令、任务工具、计算器、数据库查询、Python 引擎、网页抓取、命理 ×2；并新增 **Python/JS 双桥接**（AI 可读写对话/助手设置/群聊、运行 Python/JS 引擎）、系统提示组装器（命理工作流/工具指引/工作伦理）。

### 稳定性

- 后台生成保活：前台服务异步启动 + 600ms 防抖 + 失败兜底，切后台不打断生成
- 已清理上游没有的冗余：GitHub 工具、sleep、知识库、日志调试页（见 DIVERGENCE.md，勿加回）

---

## ✅ 上游功能全部保留

文件级核对：上游每个文件本地都有对应，无功能删除（仅 4 组文件移动/重写）。原样保留：Material You 主题、多提供商切换（OpenAI/DeepSeek/Claude/Gemini…）、流式输出、对话分支与重新生成、消息编辑/删除/翻译、全文搜索（jieba）、收藏、历史、图片生成、TTS/ASR、MCP、工作区沙箱、备份（S3/WebDAV/提醒）、Web 服务端、聊天导出、日志页。

## 🔗 相关链接

- 上游项目：[RikkaHub](https://github.com/rikkahub/rikkahub)
