# RikkaHub Plus — 命理 & 酒馆增强版

> 基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的深度定制分支（Android 原生 LLM 聊天客户端）。
> 上游全部能力完整保留，以下功能均描述为"在上游基础上强化了什么 / 全新新增了什么"。
> 与上游的逐文件差异与合并工作流见 [DIVERGENCE.md](DIVERGENCE.md)。

---

## 🍺 酒馆（SillyTavern）兼容

### 角色卡导入 / 导出 / 编辑

**上游基础**：支持 `chara_card_v2 / v3` 的 JSON 导入，解析 name、first_mes、system_prompt、description、personality、scenario 六个字段，拼接成一段 system prompt 字符串。

**本地在上游基础上：**

- **新增 PNG 卡导入**：解析 PNG `tEXt` 块，普通图片卡也能直接导入
- **字段从 6 个扩展到全字段结构化保留**：示例对话（mes_example，按 `<START>` 分块解析）、备选开场白、作者备注、角色版本、标签、历史后指令（PHI）、作者、多语言作者备注、来源、时间戳、昵称、assets、深度提示（depth_prompt）、**内嵌世界书（character_book）**、extensions 原始 JSON 无损保留
- **注入方式升级**：不再拼接成大字符串，改为按官方 Chat Completion 结构拆分——主提示、角色卡字段独立消息、示例消息、PHI（历史末尾后）、深度提示（按深度/角色）分别注入
- **新增无损导出**：PNG / JSON，官方字段名对齐（`insertion_order`、`extensions.*`、`before_char/after_char`），导入再导出不丢字段
- **新增角色卡详情编辑页**：字段可视化编辑、内嵌世界书管理、导出按钮

### 世界书（Lorebook / 提示注入）

**上游基础**：关键词包含匹配 → 注入对应内容，支持扫描深度、常驻条目、优先级、注入位置。

**本地在上游基础上新增 / 强化：**

| 能力 | 说明 |
|---|---|
| 主/副关键词逻辑 | AND_ANY / AND_ALL / NOT_ANY / NOT_ALL 四档，主键先行的官方语义 |
| 跨世界书分组 | 同组只激活一条：粘性优先 → 评分 → 覆盖 → 加权随机 |
| 递归扫描 | 缓冲逐层累积、`exclude/prevent_recursion`、`delay_until_recursion` 数字层级逐级开放 |
| 粘性 / 冷却 | 按对话隔离、到期自动转冷却、不跨对话泄漏 |
| 触发概率 | 激活后掷骰、失败整轮不重试 |
| 预算 | token 上限 + `ignore_budget` 豁免 |
| 正则键 | 官方 `/pattern/flags` 形式自动识别 |
| 扫描开关 | match_* 逐条控制是否扫描角色卡字段 |
| 锚点 | 示例消息前后（EM Top/Bottom）注入 |
| 导入导出 | 官方字段（insertion_order / extensions.*）完整读写 |

## 🔮 命理系统 — 全新（上游没有）

- **排盘引擎**：现代西洋占星、传统西洋占星、印度占星（吠陀）、深度古典占星、八字、紫微斗数、塔罗、雷诺曼
- **时间技法**：行运、推运、返照、小限、Firdaria、寿元、Almuten、阿拉伯点、中点、映点、龙首盘
- **双模式**：确定性排盘工具（`mingli`）+ 引擎自探索（JavaScript / Python 桥接）
- 一个开关同时控制两个命理工具（`mingli` + `mingli_guide`）

## 🤖 Agent 与技能

### 技能系统

**上游基础**：读取 `SKILL.md` + 提供 `use_skill` 工具供模型按需加载。

**本地在上游基础上新增 / 强化：**

- **新增自动触发注入**（`SkillAutoTriggerTransformer`）：命中技能关键词时自动把 SKILL.md 正文注入提示词，不依赖模型主动调用工具
- **新增公共技能目录** `/Rikkahub/skills`：文件管理器直接放入即可识别
- **技能目录改用外部存储**：文件管理器可访问，不再局限于应用私有目录
- **新增 GitHub 技能安装**（`SkillRegistry`）：按仓库/marketplace 直接安装
- **技能页 / 详情页重写**：浏览、编辑、启用管理
- **`use_skill` 工具增强**：按分类组织技能、返回 linked_files、附带命令提示

### 工具集

**上游基础**：时间、剪贴板、日历、JavaScript、屏幕时间、TTS、提问，以及对话、记忆、搜索、技能、工作区工具。

**本地在上游基础上新增：**

- 文件操作、Shell 命令、任务工具、计算器、数据库查询、Python 引擎、网页抓取、命理 ×2
- **Python / JS 双桥接**（`PythonBridge` / `JsBridge`）：AI 可直接读写对话、助手设置、群聊，并运行 Python / JavaScript 引擎
- 系统提示组装器：命理工作流、工具选择指引、工作伦理注入

## 💬 群聊 — 全新（上游没有）

- 多人角色共同对话，每个角色独立提示词、人设、模型
- 四种选人策略：自然（发言倾向 + 名字提及）、名单、加权、手动
- 自动接话：轮数与延迟可配，用户发言即打断
- 发言人状态与队列提示

## ⌨️ 宏引擎与斜杠命令 — 全新（上游没有）

- **宏引擎 2.0**：变量家族（set/get/inc/dec/add/has/delete + global + 简写）、`{{if}}/{{else}}`、`{{pick}}/{{roll}}/{{random}}`、时间宏、`{{original}}` 等，未知宏原样保留
- **斜杠命令**：`/sys` `/sendas` `/persona` `/trigger` `/sysgen` `/inject` `/char-update` `/char-duplicate` `/rename-char` + 7 个变量命令，输入框直接执行

## 👤 人设与导演备注 — 全新（上游没有）

- **人设**：官方五档位置（IN_PROMPT / TOP / BOTTOM / AT_DEPTH / NONE）、按角色注入、独立 SYSTEM 消息
- **导演备注**：官方间隔（1=每次 / N=倍数）、深度、注入角色语义 + 总开关

## 🛡️ 稳定性

- 后台生成保活：前台服务异步启动 + 600ms 防抖 + 失败兜底，切后台不打断生成
- 已清理上游没有的冗余：GitHub 工具、sleep、知识库、日志调试页（详见 DIVERGENCE.md，勿加回）

---

## ✅ 上游功能全部保留

文件级核对：上游每个文件本地都有对应，无功能删除（仅 4 组文件移动 / 重写）。以下能力原样保留：

Material You 主题、多提供商切换（OpenAI / DeepSeek / Claude / Gemini…）、流式输出、对话分支与重新生成、消息编辑 / 删除 / 翻译、全文搜索（jieba 中文分词）、收藏、历史、图片生成、TTS / ASR、MCP 服务器、工作区沙箱、备份（S3 / WebDAV / 提醒）、Web 服务端、聊天导出、日志页。

## 🔗 相关链接

- 上游项目：[RikkaHub](https://github.com/rikkahub/rikkahub)
