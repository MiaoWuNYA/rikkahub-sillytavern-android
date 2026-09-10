# RikkaHub Plus（华灯版）

[**简体中文**](README.md) | [**English**](README_EN.md)

> 本项目是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的深度定制分支，已合入上游最新版本（**v2.5.1**）。
> 上游全部功能原样保留，在此基础上重点强化了 **⚡提示词前缀缓存**、**🧠记忆与长对话**、**🍺酒馆（SillyTavern）兼容**、**🗼中转站兼容**、**🛡隐私与稳定性** 五大方向。
> 逐文件差异与上游合并手册见 [DIVERGENCE.md](DIVERGENCE.md)。

---

## 📌 这是什么

一个跑在手机上的 AI 聊天客户端（Kotlin + Jetpack Compose + Material You）：

- **提示词前缀缓存**：借鉴 DeepSeek Harness 的前缀稳定性设计，可显著降低长对话的 token 费用
- **记忆与长对话**：语义记忆 RAG + 上下文滚动压缩，长对话不再失忆、不再爆上下文
- **多供应商**：OpenAI / Claude / Gemini / DeepSeek 等一切 OpenAI、Anthropic、Google 兼容 API（内置 OrcaRouter 聚合网关，默认停用）
- **中转站兼容**：Gemini 经 OpenAI 兼容中转时正文被吞、被截断、"response" 前缀伪影，一键修复
- **防空回复**：Gemini 经典空回复自动微扰重试，系统提示词移入对话流（世界书注入位置保持不变）
- **豆包语音全家桶**：语音合成 2.0（Doubao TTS）+ 火山 ASR，一个 Agent Plan Key 全搞定
- **深度兼容酒馆（SillyTavern）**：角色卡、世界书、预设、正则脚本、快速回复（QR）、HTML 卡片、多开场白 —— 全部按官方语义无损导入导出
- **可编程提示词**：宏引擎 2.0、20+ 斜杠命令、人设 Persona、作者注释、群聊
- **隐私加固**：请求日志脱敏、工具审批边界、遥测默认关闭

---

## 🏮 华灯设置（本分支专属）

设置 → **华灯设置**：把本分支新增的兼容与辅助功能集中在一个页面，全局开关对所有助手生效（助手级开关可单独覆盖）。

### 1. 中转站兼容（Proxy Fix）

Gemini 经 OpenAI 兼容中转站（newapi 等）接入时的三类经典病态，自动修复：

- **正文被 reasoning_content 吞掉**：部分中转把实际回复塞进推理字段，content 只剩伪影——流结束后检测到正文为空而推理有实质内容时，自动把推理提升为正文（思考阶段不误判，正常模型的长思考 + 短回答不受影响）
- **"response" 前缀伪影**：正文开头出现 `response` / `Response:` 残留——流式与非流式路径均自动剥离，前缀被拆散在多个 delta 中也能完整识别；带边界判定，不会误伤 "responses" 等英文单词
- **正文截断**：提升与剥离逻辑让回复完整呈现，不再"思考里是答案、正文只剩半截"

流式 / 非流式路径均已覆盖，默认关闭，华灯设置或助手编辑页开启。

### 2. 防空回复（全局版）

- **系统提示词入对话流**：SYSTEM 消息原位转为 user 轮（首条后跟模型确认轮），世界书 / 人设 / 滚动摘要等注入位置与内容保持不变——绕开 Gemini 对 systemInstruction 的安全拦截
- **空回复自动微扰重试**：检测到无文本、无工具调用的空回复时，对末条用户消息做标点微扰（加/删句号、加空格，4 变体轮换）自动重试至多 3 次
- 助手级开关与全局开关任一开启即生效

### 3. 云财教务系统（YNUFE）

云南财经大学强智教务账号完整集成在华灯设置页内：

- 学号 / 密码本机混淆存储，AI 查询课表 / 成绩 / 考试安排 / 教务公告 / 空教室时自动登录
- 验证码内置 OCR 自动识别，失败时展示图片手动输入
- 登录状态与会话有效期展示，一键清除凭据

---

## 🔊 豆包语音（火山引擎 Agent Plan）

- **Doubao TTS（新增）**：豆包语音合成大模型 2.0
  - `seed-tts-2.0` 资源 + 内置 16 个实测音色预设（灿灿 2.0 豆包同款默认、甜美桃子、快乐小东等），支持手动输入任意音色
  - 语速调节、音频格式（mp3/wav/pcm/ogg/opus）与采样率可选
  - 完整解析火山特有的拼接式 JSON 分块流响应，长音频合成验证通过
  - Agent Plan 专属接口路径默认内置，标准控制台用户可改回官方路径
- **火山 ASR（修复）**：Agent Plan 的 `ark-xxx` 密钥只在 `/api/v3/plan/` 专属路径有效，默认地址已切换为 plan 专属路径，标准控制台密钥用户可在设置中改回
- 一个 Agent Plan API Key 同时驱动语音输入与语音输出

> 顺带合入了上游 2.5.1 的语音模式（voice mode）与消息队列增强：排队发送、语音回复直接返回、工具审批打断保护。

---

## ⚡ 提示词前缀缓存（Prompt Cache）

主流供应商（DeepSeek / Kimi / Claude 等）都提供自动前缀缓存：只要本次请求的前缀与上次完全一致即可命中，命中部分的计费远低于正常价。但聊天场景里大量内容**每轮都在变**（时间戳、最近会话、记忆、随机数、滚动摘要），前缀一动缓存全废。

本分支借鉴 DeepSeek Harness 的前缀稳定性设计，针对性地消除这些常见分叉点：

- **动态上下文冻结锚点**：最近会话、记忆引用等动态注入内容变化时，旧块**原位冻结保留**、新块追加到上下文尾部——动态内容不再随历史后移，前缀分叉只发生在尾部
- **随机宏按消息稳定取值**：`{{random}}` / `{{pick}}` 等随机宏按消息固定取值，历史消息不再每轮重新掷随机数
- **滚动压缩摘要追加式**：长对话触发上下文压缩后，摘要以追加方式衔接，token 前缀跨压缩保持稳定
- **注入位置治理**：记忆全量注入移至上下文尾部、Recent Chats 移出前缀区，热路径上不再有易变内容
- **前缀分叉诊断**：内置诊断视图逐条对比相邻两轮请求，显示公共前缀、估算命中率，支持**字符级差异定位**——缓存为什么失效一目了然

实际效果因对话形态而异：在稳定、纯追加的聊天场景下可明显提升缓存命中率、降低长对话成本；具体命中率与节省幅度取决于对话内容的变化频率和供应商的缓存定价。

---

## 🧠 记忆系统与长对话（移植自 [Rikkahub-Revised](https://github.com/YaeNovin/Rikkahub-Revised)）

- **语义记忆 RAG**：记忆分 FACT（事实）/ EPISODIC（情节）两类，用向量模型做嵌入 + 余弦相似度检索（附中文分词大词 + CJK 二元组词法兜底），情节记忆带时间衰减加权，检索结果按预算注入系统提示词
- **memory_tool 增强**：新增 `list` 读取操作与 fact / episodic 类型区分，模型可先查看已有记忆再决定写入或更新；情节记忆可按助手独立开关
- **记忆管理页**：按助手独立查看 / 编辑 / 删除记忆条目
- **上下文滚动压缩**：长对话超过阈值（按模型上下文窗口自动计算或手动指定）时，用压缩模型把早期对话滚动摘要为非破坏性摘要（原文保留、仅请求时替换前缀），摘要以系统消息注入，突破上下文窗口限制而不丢人设与伏笔
- **最近对话引用**：可选把该助手最近的对话列表注入提示词，跨会话连续性
- 全部按助手独立开关，默认关闭，不影响存量行为

---

## 🍺 酒馆系统（对齐 SillyTavern 官方语义）

> 酒馆核心系统（角色卡结构、世界书引擎、宏引擎 2.0、斜杠命令、群聊）来自中间分支 [heikeyangle-code/rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus) 的 `mingli2` 分支。本分支（huadeng）在其基础上**新增**：HTML 卡片渲染（默认展开 + 点击全屏）、多开场白导入、酒馆预设与正则脚本导入、QR 快速回复导入、世界书编辑器字段补全 + Token 预算兜底、Vector Storage 向量库语义条目、提示词查看器、正则深度限制与缓存、开场白宏替换。

### 1. 角色卡：导入 → 结构化 → 注入 → 导出 → 编辑

- **字段覆盖 20+（上游仅 6 个）**：示例对话（mes_example）、备选开场白、多语.creator_notes、对话后指令（post_history_instructions）、角色版本、标签、昵称、资源、group_only_greetings、创建/修改日期、内嵌世界书（character_book）、extensions 原始 JSON（含深度提示词的 depth/role）—— 上游丢弃的字段全部保留，**导入 → 导出往返无损**
- **官方 Chat Completion 注入结构**：主提示词、角色字段独立消息、示例对话按 `<START>` 拆分为真实 user/assistant 轮次、PHI 追加在历史之后、深度提示词按配置的深度/角色注入
- **V2 / V3 双版本**：V3 高级字段（nickname、多语备注、source、时间戳）与 PNG 卡（ccv3）支持，非 PNG 自动转 PNG 并做注入宏替换
- **可视化角色卡编辑器**：全部字段编辑 + 内嵌世界书管理 + 一键导出
- **多开场白导入**：alternate_greetings 全量导入，可在聊天中切换
- **HTML 卡片**：酒馆 HTML 展示卡直接渲染，默认展开 + 点击全屏

### 2. 世界书（Lorebook）

逐条对齐酒馆官方 world-info.js 语义，条目字段从上游 6 个扩展到 30+：

| 能力 | 官方对应 |
|---|---|
| 四种次级关键词逻辑（任意/全部/排除任一/排除全部） | `selective_logic` |
| 整词匹配 / 正则 / 区分大小写 | `match_whole_words` / `key_regex` / `key_case_sensitive` |
| 条目级扫描深度 | `scan_depth` |
| 常驻激活 | `constant` |
| 跨书分组 + 组权重 + 组覆盖 | `group` / `group_weight` / `group_override` |
| 触发概率 | `probability` / `use_probability` |
| 粘滞 / 冷却 | `sticky` / `cooldown` |
| 延迟激活 | `extensions.delay` |
| 递归排除 / 阻止递归 / 延迟递归 | `exclude_recursion` / `prevent_recursion` / `delay_until_recursion` |
| 预算豁免 | `extensions.ignore_budget` |
| 角色字段匹配（人设/描述/性格/深度提示词/场景/备注 ×6） | `extensions.match_*` |
| 显示顺序 / 生成过滤器 / 触发器 | `display_index` / `display_position` / `triggers` |

**扫描引擎**：完整实现官方 checkWorldInfo 状态机（INITIAL → 递归 / 最少激活 / 延迟层级循环），带预算、溢出、粘滞、冷却全生命周期；跨书分组按官方规则选出唯一条目（粘滞优先 → 关键词评分 → 组覆盖 → 加权随机）；递归扫描支持激活内容回灌与逐层打开。

**世界书编辑器**：全局设置（扫描深度、Token 预算 + 绝对上限、最少激活 + 最大深度、递归扫描 + 步数上限、插入策略、溢出提醒、组评分）、条目全字段编辑、拖拽排序、外置/内嵌世界书双向同步；支持向量库（Vector Storage）语义条目。

### 3. 酒馆预设与正则脚本导入

- **预设（Preset）导入**：酒馆 JSON 预设按官方提示词管理器结构导入
- **正则脚本（Regex）导入**：Find/Replace/_ALT、OnlyFormat、宏支持、注入深度（minDepth/maxDepth）、排序与缓存，作用于展示与提示词两层

### 4. 快速回复（Quick Replies, QR）

酒馆 QR 集合导入，输入框斜杠面板一键执行。

### 5. 宏引擎 2.0

对齐酒馆官方 Macro 2.0 完整语法：

- **变量**：`{{setvar}}` `{{getvar}}` `{{.var}}` 简写全家桶，全局 + 会话级持久化 —— 角色卡可以记住剧情状态
- **条件**：`{{if}} / {{else}}`、比较运算符、`&&` / `||`、作用域块、嵌套
- **随机与时间**：`{{pick}}`（回合内稳定）、`{{roll::1d20}}`、`{{random}}`、`{{time}}`、`{{datetimeformat}}`
- **对话感知**：`{{lastUserMessage}}` `{{lastCharMessage}}` `{{idleDuration}}` `{{charFirstMessage::N}}` `{{original}}` 等 60+ 官方宏全量支持
- 未知宏原样保留，不破坏提示词

### 6. 斜杠命令

输入框直接敲，`/help` 列出全部命令与中文说明。20+ 内置命令：

- **角色扮演**：`/impersonate`（AI 以你的口吻起草发言）、`/continue`（续写）、`/sendas`（替角色发言）、`/sys`（系统消息）、`/sysgen`（AI 生成旁白）、`/trigger`（不追加消息直接触发回复）、`/message-name`、`/delname`
- **变量与随机**：`/listvar` `/setvar` `/getvar` `/addvar` `/incvar` `/decvar` `/flushvar` `/reroll-pick`
- **角色卡管理**：`/char-update` `/char-duplicate` `/rename-char`
- **注入**：`/inject`（按位置/深度/角色注入提示词）、`/prompt`
- 技能提供的命令自动出现在面板中

### 7. 人设 Persona 与作者注释 Author's Note

- 人设：官方五档注入位置（IN_PROMPT / TOP / BOTTOM / AT_DEPTH / NONE）、按角色绑定、独立 SYSTEM 消息注入、一键禁用
- 作者注释（导演备注）：官方间隔语义（每次 / 每 N 条用户消息）、注入深度与角色、总开关

### 8. 群聊

多角色同场对话，每个成员有独立提示词 / 人设 / 模型；4 种发言策略（NATURAL AI 选人 / 列表 / 加权随机 / 手动）+ 5 种扩展模式；自动接话（轮数 1-10 可设、延迟可设、被用户消息打断）；发言者实时状态；群聊持久化。

---

## 🛠 技能与工具

### 技能（Skills）

- **自动触发**：匹配关键词即注入 SKILL.md，不依赖模型主动调用
- **公共技能目录** `/Rikkahub/skills`：文件管理器直接增删
- **GitHub 一键安装 / 批量下载 / 更新检测**：支持子目录与多技能仓库，记录来源与整目录哈希
- 技能页与详情页重写，`use_skill` 增强

### 工具集

在上游基础上新增：文件操作、Shell、任务工具、计算器、数据库查询、Python 引擎（Chaquopy）、网页抓取、云南财经教务系统查询（课表/成绩/考试/公告/空教室，账号管理在华灯设置页）；另有**系统提示词装配器**（工具选用指南 / 工作守则）。

---

## 🛡 隐私与稳定性

### 隐私加固

- **请求日志脱敏**：请求头白名单机制，提示词 / Schema / 二进制 / 凭据脱敏，错误信息密钥掩码与体积上限
- **工具审批与隐私边界**：剪贴板 / 屏幕时间 / Shell 每次执行需审批，文件工具写入类操作需审批；屏幕时间限制查询范围与明细条数且不输出包名
- **备份恢复资源预算**：限制条目数、单条目与总解压大小，防御异常归档耗尽资源
- **按日文件清理**：聊天附件与生成图片可按保留天数自动清理（默认关闭）
- **Firebase 遥测默认关闭**：构建属性开启才启用 Google 服务与 Crashlytics

### 稳定性

- **前台服务保活**：切后台不断流，生成不被系统杀死
- **SSE 长连接加固**：OkHttp 30s PING 保活，事件流请求禁用缓存与压缩，代理环境下流式输出不再被缓冲截断
- **数据库平滑升级**：全部 schema 变更走显式迁移，老数据无损升级
- 备份导入一致性快照、启动安全恢复；图片选择迁移 PickVisualMedia 等大量修复

---

## ✅ 与上游的关系

- **上游功能全部保留**：Material You 主题、多供应商、流式生成、会话分叉与重新生成、消息编辑/删除/翻译、全文搜索（jieba）、收藏、图片生成、TTS / ASR（含火山引擎双向流式与豆包语音合成）、MCP、工作区沙箱（终端多 Tab + Shell 兼容模式）、备份（S3 / WebDAV）、网络对话端、聊天导出等一切照旧
- **已合入上游最新版本**：rikkahub/rikkahub master（**v2.5.1**），本次合入的亮点：语音模式与消息队列、翻译快捷入口、工作区 Shell 兼容模式与 HTML/SVG 预览、自定义时间提醒间隔、自定义 Response API 路径、DeepSeek V4.1 Flash、`ask_user` 自定义文本回复、图片生成页多选等。上游更新可随时通过 `git fetch rikkahub && git merge rikkahub/master` 拉入（冲突手册见 [DIVERGENCE.md](DIVERGENCE.md)）
- **相对 mingli2 分支**：除酒馆增强外，主要新增提示词前缀缓存、语义记忆 RAG 与上下文滚动压缩、中转站兼容与防空回复、豆包语音、隐私加固、云南财经教务系统工具等（酒馆部分的增量见「酒馆系统」开头的注记）

## 📦 下载

- **Nightly 预发布**：本仓库 Actions 每日自动构建并发布到 [Releases](https://github.com/MiaoWuNYA/rikkahub-plus/releases/tag/nightly)（tag `nightly`，每晚覆盖为最新）
- **稳定版**：[Releases](https://github.com/MiaoWuNYA/rikkahub-plus/releases) 按版本发布（`2.5.2fixN`）
- **手动构建产物**：[Actions](https://github.com/MiaoWuNYA/rikkahub-plus/actions) 每次推送构建 APK Artifact（`rikkahub-plus-fresh`）

---

## 🙏 致谢与版权说明（Credits）

本项目站在前人的肩膀上，特别感谢以下项目：

| 项目 | 关系 | 许可证 |
|---|---|---|
| [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) | **原始上游项目**，本仓库的全部基础功能来自它 | AGPL-3.0 |
| [heikeyangle-code/rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus) | **直接上游（中间分支）**，酒馆系统、宏引擎、斜杠命令、群聊等核心增强的开发者 | AGPL-3.0 |
| [YaeNovin/Rikkahub-Revised](https://github.com/YaeNovin/Rikkahub-Revised) | 同源分支，本项目从中移植了**语义记忆 RAG** 与**上下文滚动压缩** | AGPL-3.0 |
| [SillyTavern/SillyTavern](https://github.com/SillyTavern/SillyTavern) | 酒馆系统的兼容目标；角色卡 / 世界书 / 宏 / 斜杠命令的**语义与格式规范**参考其官方实现（AGPL-3.0），本项目未复制其代码 | AGPL-3.0 |

本项目与其上游均为 **AGPL-3.0** 许可，本仓库沿用同一许可证继续开源。各上游项目的版权归其原作者所有，感谢他们 generously 地开源。

---

如果这个分支对你有用，欢迎点一个 ⭐ Star ✨
