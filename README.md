# RikkaHub Plus — 酒馆增强 · 命理排盘

[**简体中文**](README.md) | [**English**](README_EN.md)

> 基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的深度定制分支：一个原生 Android LLM 聊天客户端。
> 上游全部功能原样保留，在此之上新增 **SillyTavern 酒馆兼容层** 与 **十二套命理排盘体系**。
> 逐文件差异与上游合并工作流见 [DIVERGENCE.md](DIVERGENCE.md)。

---

## 📌 它是什么

一个跑在手机上的 AI 聊天客户端（Kotlin + Jetpack Compose + Material You）：

- **多提供商**：OpenAI / Claude / Gemini / DeepSeek 等任意 OpenAI、Anthropic、Google 兼容 API
- **酒馆兼容**：SillyTavern 角色卡 / 世界书深度兼容，字段无损导入导出，官方注入结构
- **命理系统**：十二套确定性排盘引擎（八字 / 紫微 / 塔罗 / 西洋占星 / 吠陀 / 奇门 / 六爻…），AI 按权威模板逐条解读
- **可编程提示词**：宏引擎 2.0、22 个斜杠命令、群聊、技能自动触发、Python / JS 双引擎桥接

---

## ⚔️ 和上游比，独特在哪

| | 上游 RikkaHub | **RikkaHub Plus** |
|---|---|---|
| 🎴 角色卡 | 解析 6 个字段，无导出 | **22 字段无损**：官方注入结构、PNG / JSON 导出、可视化编辑 |
| 📚 世界书 | 基础关键词匹配 | **官方全套语义**：四档关键词、跨书分组、递归扫描、粘性 / 冷却、token 预算 |
| 🧩 宏 | 简单占位符 | **宏引擎 2.0**：变量、条件、随机、对话感知，提示词可编程 |
| ⌨️ 斜杠命令 | 无 | **22 个内置命令**：`/impersonate` `/continue` `/sysgen` `/reroll-pick` … |
| 👥 人设 / 导演备注 / 群聊 | 无 | **三个全新系统**，全部对齐酒馆官方语义 |
| 🔮 命理 | 无 | **十二套排盘体系** + 14 份强制解读模板，Python / JS 双引擎交叉验证 |
| 🚀 技能 | 模型手动调用才加载 | **自动触发**、公共目录、GitHub 一键安装 / 批量下载 / 更新检测 |
| ⚡ 生成 | 切后台易中断 | **前台服务保活**，后台生成不打断 |

一句话：**上游是基础聊天客户端，这是给 AI 角色扮演和命理爱好者准备的完整工具箱。**

---

## 🍺 酒馆系统

### 角色卡：导入 → 结构化 → 注入 → 导出 → 编辑

**上游**：JSON（V2/V3）与 PNG 可导入，但只解析 6 个字段（name / first_mes / system_prompt / description / personality / scenario），拼成一段 system prompt 字符串；**没有导出**。

**本分支**：

- **22 个字段全部结构化保留**：示例对话、备选开场白、作者备注、历史后指令（PHI）、版本、标签、昵称、素材、`character_book`（内嵌世界书）、`extensions`（深度提示）等。上游会丢掉的，这里一个不丢。
- **官方 Chat Completion 注入结构**：主提示、角色卡字段独立消息、示例消息按 `<START>` 解析成真正的 user/assistant 对话、PHI 放历史末尾、深度提示按深度 / 角色注入。
- **无损导出（新增）**：PNG / JSON，字段名对齐官方规范（`insertion_order`、`extensions.*`），导入再导出不丢东西。
- **角色卡详情编辑页（新增）**：22 个字段可视化编辑 + 内嵌世界书管理 + 导出按钮。

### 世界书（Lorebook）

**上游**：关键词包含匹配 → 注入内容，支持扫描深度、常驻条目、优先级、注入位置。

**本分支新增**：

- 主 / 副关键词**四档逻辑**（AND_ANY / AND_ALL / NOT_ANY / NOT_ALL），官方语义
- **跨世界书分组**：同组只激活一条——粘性优先 → 关键词评分 → 覆盖优先 → 加权随机
- **递归扫描**：已激活内容继续扫，缓冲逐层累积；exclude / prevent 控制、`delay_until_recursion` 数字层级逐级开放
- **粘性 / 冷却**：激活后保留 N 轮、到期自动冷却，按对话隔离
- **触发概率**、**token 预算 + `ignore_budget` 豁免**、**官方正则键**（`/pattern/flags`）
- **`match_*`**：逐条控制扫描角色卡字段；**示例消息前后锚点**（EM Top / Bottom）
- 条目导入导出字段完整

### 宏引擎 2.0

**上游**：简单占位符替换（`{{char}}`、`{{user}}`）。

**本分支**——提示词变成程序：

- **变量系统**：`/setvar` `/getvar` `/incvar` … 管理对话变量，宏里用 `{{getvar::key}}` 或 `.key` 简写读取，一张卡随剧情状态自动切换说法
- **条件逻辑**：`{{if}} / {{else}} / !`、比较运算符、`&&` / `||`，支持嵌套
- **随机与时间**：`{{pick::A|B|C}}`（同轮稳定随机）、`{{roll::1d20}}`、`{{random}}`、时间与间隔宏
- **对话感知**：`{{lastUserMessage}}`、`{{lastCharMessage}}`、`{{idleDuration}}`、`{{charFirstMessage::N}}`、`{{original}}`
- 未知宏原样保留，不破坏模板

### 斜杠命令（上游没有）

输入框直接输入即执行，`/help` 随时查看全部命令；无参数命令点击直接执行，带参数命令点击自动填入补全。

- **角色扮演**：`/impersonate`（AI 以你的视角拟话）、`/continue`、`/sendas`、`/sys`、`/send`
- **操控生成**：`/trigger`、`/sysgen`（AI 写系统旁白）、`/inject`（注入提示词不污染聊天记录）
- **角色卡管理**：`/char-get` `/char-update` `/char-duplicate` `/rename-char`
- **变量与随机**：`/listvar` `/setvar` `/getvar` `/addvar` `/incvar` `/decvar` `/flushvar` `/reroll-pick`
- 语义对照酒馆官方实现；技能目录里的命令随技能自动出现

### 人设 / 导演备注 / 群聊（上游没有）

- **人设**：官方五档注入位置（IN_PROMPT / TOP / BOTTOM / AT_DEPTH / NONE）、按角色绑定、独立 SYSTEM 消息注入
- **导演备注（Author's Note）**：官方间隔语义（1=每次 / N=用户消息倍数）、注入深度、注入角色、总开关
- **群聊**：多人角色共同对话，每角色独立提示词 / 人设 / 模型；四种选人策略（自然 / 列表 / 带权重随机 / 手动）；自动接话（轮数、延迟可配，用户发言即打断）

---

## 🔮 命理系统（全新，上游没有）

一套完整的确定性排盘系统 + 引擎自探索能力。**一个开关同时控制两个工具**（`mingli` 确定性排盘 + `mingli_guide` 解读模板），中文、英文体系名与别名均可识别。

### 十二套排盘体系

| 体系 | 实际引擎 | 亮点 |
|---|---|---|
| 塔罗（韦特） | Arcanite（Python 牌库）+ 元素尊贵引擎 + Kaabalah（JS） | 多牌阵、元素强弱与相位分析、卡巴拉对应 / 生命之树 |
| 雷诺曼 | Arcanite（Python）+ LenormandFate（Python） | 多牌阵、位置语义、命运连读 |
| 八字（四柱） | lunar_python + bazi_china（Python） | 十神、月令、干支、生肖、罗睺、大运流年 |
| 紫微斗数 | iztro（JS，默认）+ 可选倪海夏（JS）/ 纯 Python | 三方四正、大限、流年/月/日/时、小限，多引擎对照 |
| 现代西洋占星 | Caelus（JS，全量星历） | chart / derived / events / eclipses / Firdaria / profections / directions / ACG |
| 传统西洋占星 | PySwissEph + FlatLib（Python） | 古典尊贵、Almuten、ruler / exalt、焦伤、卜卦、映点、恒星合相 |
| 吠陀（印度占星） | PyJHora（Python） | Vimsottari 大运、Ashtakavarga、Tajaka 年运、Raja Yoga / Dosha |
| 深度古典占星 | stellium（Python，SwissEph 底层） | Firdaria、小限、寿元、Almuten、龙首盘、阿拉伯点、中点、映点 |
| 人类图 | NatalEngine（JS） | 类型 / 权威 / 中心 / 通道 / 闸门 / 轮回交叉 / Profile、基因钥匙、行运 |
| 灵数卡巴拉 | Kaabalah（JS） | 灵数六核心、个人年 / 挑战、斐波那契、Gematria 正反查、Ifa Odu |
| 奇门遁甲（含大六壬） | QiMen TS（JS）+ LiuRen TS（JS） | 日家 / 时家、法术（QMA），大六壬独立引擎 |
| 六爻（含梅花易数） | ichingshifa（Python，大衍筮法）+ iching-shifa（JS） | **双引擎对照**：同一爻值各自出解读，本卦 / 变卦 / 动爻 |

> 引擎信息逐个对照 `app/src/main/python/routes/` 源码确认（2026-08）。

### 架构亮点

- **统一入口**：`mingli_router` 一张路由表，中文名 / 英文名 / 别名全可识别（"紫微" / "ziwei" / "紫微斗数" / "紫薇"）
- **双引擎桥接**：Python 引擎（lunar_python、bazi_china、Arcanite、PyJHora、ichingshifa、PySwissEph+FlatLib、stellium、纯 Python 紫微）+ QuickJS 预编译引擎（caelus / iztro / natalengine / kaabalah / qimen / liuren / iching-shifa / ziwei-nihai / lunar / astronomy / horoscope / taixuan / node-jhora），跨语言共用一条调用链
- **交叉验证**：六爻 Python+JS 双引擎对照、紫微三引擎可选、塔罗与卡巴拉互映
- **确定性结构化输出**：所有排盘返回统一 JSON（宫位、星曜、角度、时间技法字段齐全），AI 解读逐字段使用
- **强制解读模板**：`mingli_guide(system=体系名)` 读取 `assets/mingli/` 下 14 份权威 Markdown 模板，逐条遵守，跳过视为违规；排盘 → 读模板 → 按模板组织回复的工作流写死在系统提示词组装器里
- **引擎自探索**：数据不够时用 `eval_javascript`（已加载引擎继续深挖）或 `execute_python`（农历 / 历法 / 自定义计算），不重复写排盘代码

**时间技法**：行运、次限 / 主限推运、太阳 / 月亮返照、年度小限、Firdaria、寿元、Almuten Figuris、阿拉伯点、中点、映点、龙首盘、大限 / 流年 / 流月 / 流日 / 流时。

---

## 🛠 技能与工具

### 技能系统

**上游**：读 `SKILL.md` + `use_skill` 工具，只有模型主动调用才加载，技能锁在应用私有目录，无安装 / 更新 / 发现机制。

**本分支**：

- **自动触发**：命中技能关键词时自动把 SKILL.md 注入提示词，不依赖模型自觉
- **公共技能目录** `/Rikkahub/skills`：文件管理器直接放入即可识别；技能改用外部存储，可随时增删
- **GitHub 一键安装**：`github.com/owner/repo` 或 `github.com/owner/repo/tree/branch/路径` 链接，支持子目录与多技能仓库
- **批量下载**：一次导入整个仓库所有技能（GitHub 递归目录树 + 并发下载，信号量限流）
- **更新检测**：安装时记录仓库源与整目录哈希（`skillShas`），一键检查单个 / 全部技能更新
- **安装源记录**：识别"本地已有 / 可更新 / 同源"状态，避免重复安装；**技能注册表**：从内置 registry 直接安装
- **技能页 / 详情页重写** + `use_skill` 工具增强（按分类组织、linked_files、命令提示、实时刷新）

### 工具集

**上游**：时间、剪贴板、日历、JavaScript、屏幕时间、TTS、提问 + 对话、记忆、搜索、技能、工作区。

**本分支新增**：文件操作、Shell、任务、计算器、数据库查询、Python 引擎、网页抓取、命理 ×2；并新增 **Python / JS 双桥接**（AI 可读写对话 / 助手设置 / 群聊、运行 Python / JS 引擎）与**系统提示组装器**（命理工作流 / 工具指引 / 工作伦理）。

### 稳定性

- **后台生成保活**：前台服务异步启动 + 600ms 防抖 + 失败兜底，切后台不打断生成
- 已清理上游冗余（GitHub 工具、sleep、知识库、日志调试页——见 [DIVERGENCE.md](DIVERGENCE.md) §5，勿加回）

---

## ✅ 与上游的关系

- **全部保留**：文件级核对，上游每个文件本地都有对应，无功能删除（仅 4 组文件移动 / 重写）。Material You 主题、多提供商、流式输出、对话分支与重新生成、消息编辑 / 删除 / 翻译、全文搜索（jieba）、收藏、图片生成、TTS / ASR、MCP、工作区沙箱、备份（S3 / WebDAV / 提醒）、Web 服务端、聊天导出均原样可用。
- **差异规模**：约 626 个文件改动，+66,000 行，领先上游 1900+ 提交；上游新功能可随时 `git fetch upstream && git merge upstream/master` 合入（冲突处理手册见 [DIVERGENCE.md](DIVERGENCE.md)）。

## 🔗 相关链接

- 上游项目：[RikkaHub](https://github.com/rikkahub/rikkahub)
- 差异与合并手册：[DIVERGENCE.md](DIVERGENCE.md)

---

如果这个分支对你有用，请点个 ⭐ Star 支持一下 ✨
