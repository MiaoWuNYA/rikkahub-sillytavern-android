---
title: 本地工具：AI 的设备内工具集
---

# 本地工具：AI 的设备内工具集

除[设备工具箱](/extensions/toolbox)的 30 个系统能力外，华灯内置了一整套设备端工具：文件、代码、计算、检索、任务、记忆。在**助手详情 → 本地工具**按助手勾选启用。

## 工具总表

工具调用在界面上显示中文雅称——`file` 显示为「书阁司卷」，`execute_python` 显示为「灵枢演算」：

| 工具 | 雅称 | 说明 |
| --- | --- | --- |
| `file` | 书阁司卷 | 统一文件工具：读 / 写 / patch / 列 / 搜 / 复制 / 移动 / 建目录 / 删除 |
| `execute_command` | 乾纲令行 | 设备上执行 Shell 命令，返回 stdout / stderr / 退出码 |
| `execute_python` | 灵枢演算 | 设备内隔离的 Python 执行（Chaquopy） |
| `eval_javascript` | 锦囊码算 | 设备内 JavaScript 执行 |
| `calculator` | 神机妙算 | 700+ 函数计算器（统计 / 金融 / 矩阵 / 微积分 / 物理） |
| `database_query` | 天书检阅 | 只读 SQLite 查询本机数据库 |
| `task_create` 等 | 布局落子 / 察势观局 / 阅尽楸枰 / 运筹帷幄 | 任务列表：创建 / 查询 / 列出 / 更新，复杂多步任务 AI 会自行建单 |
| `web_fetch` | 摘星引卷 | 任意 URL 的 HTTP 请求（GET / POST / PUT / PATCH / DELETE） |
| `scrape_web` | 剖页取真 | 抓取网页正文 |
| `search_web` | 天网搜罗 | 联网搜索（走[搜索服务](/chat/search)配置） |
| `check_code` | — | 代码检查 |
| `diff_text` | — | 文本对比 |
| `show_image` | — | 在对话中展示图片 |
| `present_file` | 献卷呈览 | 通过系统分享面板分享文件 |
| `clipboard` | 拾简掇墨 | 剪贴板读写（每次执行需审批） |
| `get_screen_time` | 察屏知时 | 屏幕使用时间（限制查询范围且不输出包名） |
| `get_time_info` | 察今知时 | 当前时间与时区 |
| `ask_user` | 垂询问策 | AI 主动向你提问澄清 |
| `memory_tool` | 心念归藏 | 记忆读写，支持 `list` 与 fact / episodic 区分 |
| `conversation_search` | 探寻搜查 | 按语义检索历史对话 |
| `recent_chats` | 溯源观澜 | 最近对话列表 |
| `read_history_message` | 回溯拾遗 | 取回被[瞬态裁剪](/settings/huadeng)的历史消息原文 |
| `judge` | Jev 明断 | 询问 [Jev 判断模型](/memory/jev)是非题 / 选择题 / 打分题 |
| 情侣空间系列 | 观俪影轩 / 题俪影轩 / 双鲤日记 / 共读同赏 / 弦歌共聆 / 节庆铭记 等 | 见[情侣空间](/companion/couple-space)与[生活空间](/companion/life-space) |

## 系统提示词装配器

工具开启较多时，**系统提示词装配器**会自动生成工具选用指南与工作守则——告诉 AI 什么场景用哪个工具、如何组合，弱模型也能把工具链用起来。

## DSML 文本工具调用兼容

部分模型或中转站不走 function calling，而是把工具调用写成正文里的标记。华灯会自动解析这类文本调用并真正执行，同时把标记从正文中清除——配合[中转站兼容](/settings/huadeng)，弱供应商也能跑工具流。

另有**工具别名映射**：模型输出的 DSML 惯用名（如 `web_search`）自动映射到实际注册名（`search_web`），调用不再因名字对不上而失败。

## 审批边界

无论[审批策略](/settings/huadeng)如何设置：剪贴板、屏幕时间、Shell 命令每次执行都需确认；文件工具的写入类操作需要确认。隐私加固细节见仓库 README。
