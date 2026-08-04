# RikkaHub Plus — 命理 & 酒馆增强版

> 基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的深度定制分支（Android 原生 LLM 聊天客户端）。
> 上游所有功能完整保留，在此之上新增/强化了以下能力（差异地图见 [DIVERGENCE.md](DIVERGENCE.md)）。

---

## 🚀 比上游 rikkahub 强在哪

### 1. 酒馆（SillyTavern）生态 —— 上游完全没有，从 0 到 1

- **角色卡 V2/V3 导入导出**（新增 `TavernCharacterCard` / `AssistantImporter` / `CardExporter`）：
  字段无损（描述/性格/场景/开场白/示例/系统提示/作者备注/深度提示/内嵌世界书/PHI），支持 PNG + JSON，导出时官方字段全对齐（`insertion_order`、`extensions.*`）。
- **世界书**（强化 `PromptInjectionTransformer`）：上游只有最简单的关键词包含匹配；本地实现了酒馆官方全语义——
  主/副关键词四档逻辑（AND_ANY/AND_ALL/NOT_ANY/NOT_ALL，主键先行）、跨世界书分组（评分/覆盖/加权随机）、递归扫描（缓冲逐层累积、`exclude/prevent_recursion`、数字层级延迟）、粘性/冷却、触发概率、token 预算与豁免、`/正则/` 键自动识别、match_* 逐条扫描开关、生成类型过滤。
- **宏引擎 2.0**（新增 `MacroEngine`）：上游只有简单占位符替换；本地实现官方宏全套——变量家族（set/get/inc/dec/add/has/delete + global + 简写）、`{{if}}/{{else}}/!`/比较/`&&||`、`{{pick}}/{{roll}}/{{random}}`、时间宏、`{{original}}` 等，未知宏原样保留。
- **斜杠命令**（新增）：`/sys` `/sendas` `/persona` `/trigger` `/sysgen` `/inject` `/char-update` `/char-duplicate` `/rename-char` + 7 个变量命令，输入框直接执行。
- **人设 / 导演备注**（新增）：按酒馆官方语义实现——人设五档位置（IN_PROMPT/TOP/BOTTOM/AT_DEPTH/NONE）+ 按角色注入；导演备注官方间隔/深度/角色语义 + 总开关。
- **群聊**（新增 `GroupChat` 全套）：4 种选人策略（自然/名单/加权/手动）、自动接话（轮数与延迟可配、用户发言即打断）、发言人状态提示、每角色独立提示词与模型。

### 2. 命理系统 —— 上游完全没有

- 排盘引擎：现代西洋占星、传统西洋占星、印度占星（吠陀）、深度古典占星、八字、紫微斗数、塔罗、雷诺曼。
- 时间技法：行运、推运、返照、小限、Firdaria、寿元、Almuten、阿拉伯点、中点、映点、龙首盘。
- 双模式：确定性排盘工具（`mingli`）+ 引擎自探索（JavaScript/Python 桥接），一个开关同时控制两个命理工具。

### 3. Agent 工具 —— 上游有基础，本地大幅加强

- 上游基础工具：时间/剪贴板/日历/JS/屏幕时间/TTS/提问 + 对话/记忆/搜索/技能/工作区工具。
- 本地**新增**：文件操作、Shell 命令、任务工具、计算器、数据库查询、Python 引擎、网页抓取、命理 ×2。
- **Python/JS 双桥接**（新增 `PythonBridge`/`JsBridge`）：AI 可直接读写对话、助手设置、群聊，运行 Python/JS 引擎。
- **技能系统强化**：上游只有"读 SKILL.md + use_skill 工具"；本地新增——
  自动触发注入（`SkillAutoTriggerTransformer`，命中关键词自动注入技能正文）、公共技能目录 `/Rikkahub/skills`（文件管理器可直接放）、外部存储技能目录、GitHub 技能安装（`SkillRegistry`）、技能页/详情页重写、`use_skill` 增强（按分类组织、linked_files、命令提示）。
- 系统提示组装器（新增）：命理工作流、工具选择指引、工作伦理注入。

### 4. 稳定性与性能

- 后台生成保活：前台服务异步启动 + 600ms 防抖 + 失败兜底，切后台不打断生成。
- 工具可控：命理工具总开关；工具构建链路已精简（去掉 skill 目录扫描、对齐上游 MCP 命名与校验）。
- 已清理上游没有的冗余：GitHub 工具、sleep 工具、知识库整套、日志调试页（详见 DIVERGENCE.md 第 5 节，不要加回）。

---

## ✅ 上游功能全部保留

文件级核对：上游每个文件本地都有对应，无功能删除（仅有 4 组文件移动/重写）。以下能力原样保留：
Material You 主题、多提供商切换（OpenAI/DeepSeek/Claude/Gemini…）、流式输出、对话分支与重新生成、消息编辑/删除/翻译、
全文搜索（jieba 中文分词）、收藏、历史、图片生成、TTS/ASR、MCP 服务器、工作区沙箱、备份（S3/WebDAV/提醒）、Web 服务端、聊天导出、日志页。

## ⚠️ 说明

- 本仓库为个人深度定制分支，与上游 RikkaHub 无关的问题请先到本仓库反馈。
- 上游后续新功能需按 [DIVERGENCE.md](DIVERGENCE.md) 的流程定期合入。

## 🔗 相关链接

- 上游项目：[RikkaHub](https://github.com/rikkahub/rikkahub)
