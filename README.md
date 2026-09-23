<div align="center">

# RikkaHub Plus · 华灯版

### 安卓上的 **AI 聊天客户端**
### 兼为 **SillyTavern（酒馆）兼容端**

**开箱即聊 · 提示词缓存省 token · 记忆不失忆 · 角色卡 / 世界书 / 预设按酒馆官方语义一键导入**

[**简体中文**](README.md) | [**English**](README_EN.md) | [差异手册](DIVERGENCE.md)

[![Release](https://img.shields.io/github/v/release/MiaoWuNYA/rikkahub-sillytavern-android?label=release&color=brightgreen)](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/releases)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/releases)
[![Stars](https://img.shields.io/github/stars/MiaoWuNYA/rikkahub-sillytavern-android?color=yellow)](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/stargazers)

</div>

> **RikkaHub 的深度定制分支**（已合入上游 v2.5.2，另有 2150+ 提交的增量），Kotlin + Jetpack Compose 原生实现。
>
> - **只想找个好用的 AI 聊天 App**：接上任意 API 就能聊——多供应商、流式输出、语音通话、插件工具、记忆系统、缓存省钱，装上就用，酒馆功能默认不增加上下文，不浪费用量。
> - **想找一个安卓酒馆 APP**：角色卡、世界书、预设、正则、美化主题按官方语义无损导入，无需 Termux 或 Node.js
>
> 两条路线共用同一个客户端，互不干扰。

---

## ✨ 相对上游多了什么

| | 方向 | 一句话 |
|---|---|---|
| ⚡ | **提示词缓存优化** | 消除每轮变化的上下文分叉点，长对话 token 费用大幅下降 |
| 🧠 | **记忆与长对话** | 语义 RAG + 三层记忆 + 滚动压缩，长对话不失忆不爆窗 |
| 🧿 | **Jev 智能决策** | 挂载 TypeSafe System One 判断模型：记忆筛选 + 主模型的快问快答 |
| 🧩 | **插件系统** | QuickJS 沙箱插件，ZIP 导入，AI 可直接调用插件工具 |
| 🍺 | **酒馆深度兼容** | 角色卡 / 世界书 / 预设 / 正则 / QR / 美化主题，按官方语义无损导入导出 |
| 🗼 | **中转站兼容** | Gemini 走 OpenAI 兼容中转的三类经典病态自动修复 |
| 🔊 | **豆包语音** | TTS 2.0 + 火山 ASR，一个 Agent Plan Key 支撑语音/视频通话 |
| 📱 | **手机增强** | 30 个设备工具（懒发现省 token）+ 微信/QQ Bot + AI 主动发消息 |
| 🏮 | **华灯设置** | 本分支新增开关的统一入口页 |
| 🎨 | **外观与主题** | 7 项配色自定义 + 酒馆美化主题导入 |
| 🛠 | **技能与工具** | GitHub 一键装技能 + 20 个新工具 + DSML 文本工具调用兼容 |
| 💞 | **情感陪伴** | 情侣空间、生活空间（周期/备忘录/日历/一起听/共读书架） |
| 🛡 | **隐私与稳定** | 日志脱敏、审批边界、遥测默认关闭、SSE 与数据库加固 |

<details>
<summary><b>🗺️ 相对另外两个上游分支的定位（点击展开）</b></summary>

本仓库有两条上游血脉，理解这个才能看懂差异：

```
rikkahub/rikkahub (最上游，v2.5.2)
        │
        ├──► heikeyangle-code/rikkahub-plus (中间分支 mingli2)
        │            │  酒馆系统 / 宏引擎 / 斜杠命令 / 群聊
        │            ▼
        └──► MiaoWuNYA/rikkahub-sillytavern-android  ← 本仓库
                     │  酒馆增强 / 缓存优化 / 插件 / 记忆 / 中转兼容 / 手机增强
                     │
                     ├─ 另从 orangechat & Tumin 引入：三层记忆、情侣空间、
                     │  生活空间、外观自定义、插件系统雏形
                     └─ 另从 Rikkahub-Revised 移植：语义记忆 RAG、滚动压缩
```

- **对最上游 `rikkahub`**：上游功能全部保留；酒馆、插件、记忆等全部是增量
- **对中间分支 `heikeyangle-code/rikkahub-plus`（mingli2）**：酒馆核心来自它，本分支在其上补齐了
  HTML 卡片、多开场白、预设与正则导入、QR 导入、世界书编辑器补全与 Token 预算兜底、
  Vector Storage 语义条目、提示词查看器、正则深度限制与缓存、开场白宏替换
- **对其它同源分支**：见文末[致谢](#-致谢与版权说明credits)，特色功能的来源均已注明

</details>

---

## 🚀 快速开始

1. 到 [Releases](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/releases/latest) 下载最新 APK（`arm64-v8a`，Android 8.0+）
2. **配模型**：设置 → 模型与服务 → **提供商**，添加你的 API；再到**默认模型和提示词**给对话/标题/压缩分别选模型
3. 开始聊天。想要更多：
   - **导入角色卡**（助手页 → 导入，支持 PNG 卡 / V2 / V3 JSON，世界书和预设自动带进来）
   - **导入世界书 / 预设 / 正则 / QR**（设置 → 扩展管理）
   - **换个酒馆主题**（设置 → 偏好设置 → 聊天外观自定义 → 导入酒馆主题）
   - **加插件**（设置 → 插件管理 → 导入 ZIP，[内置插件](#-内置插件)开箱可用）

> 应用内更新：设置 → 关于 → 检查更新（走 GitHub Releases API，国内镜像自动回退）

---

## 💬 聊天功能（本分支核心增强）

### ⚡ 提示词前缀缓存优化

主流供应商（DeepSeek / Kimi / Claude 等）都提供自动前缀缓存：本次请求前缀与上次**逐字节一致**即命中，命中部分计费远低于正常价。但聊天场景里大量内容每轮都在变（时间戳、最近会话、记忆、随机数、滚动摘要），前缀一动缓存全废。

本分支借鉴 DeepSeek Harness 的前缀稳定性设计，逐个消除这些分叉点：

| 分叉点 | 处理方式 |
|---|---|
| 最近会话 / 记忆引用等动态注入 | **冻结锚点**：旧块原位保留、新块追加到尾部，动态内容不再随历史后移 |
| `{{random}}` / `{{pick}}` 等随机宏 | 按消息固定取值，历史消息不再每轮重掷 |
| 世界书 / 技能 / 跨窗口记忆 / OCR / 正则 depth | 按「用户轮」冻结，同一轮内多次请求前缀一致 |
| 已入历史的助手消息宏渲染 | 按消息冻结，结果不再随当前时间/变量变化 |
| 时间族宏（`{{time}}` 等） | 5 分钟粒度对齐，避免秒级抖动 |
| 滚动压缩摘要 | 追加式衔接，token 前缀跨压缩保持稳定 |
| 记忆全量注入 / Recent Chats | 移出前缀区（前者移至上下文尾部） |
| 多 API Key | 按会话粘性选 Key，避免上游缓存因账号切换失效 |
| Claude `redacted_thinking` | 保留并回放，不破坏思维链块结构 |

**诊断工具**：内置**前缀分叉诊断**，逐条对比相邻两轮请求，显示公共前缀、估算命中率，并支持**字符级差异定位**——缓存为什么失效一目了然。另有**提示词查看器**可直接查看本轮发给模型的完整提示词。

> 实际效果因对话形态而异：稳定、纯追加的聊天场景下命中率提升明显；具体幅度取决于内容变化频率与供应商定价。

### 🧠 记忆与长对话

- **语义记忆 RAG**（移植自 [Rikkahub-Revised](https://github.com/YaeNovin/Rikkahub-Revised)）：记忆分 FACT（事实）/ EPISODIC（情节）两类，向量嵌入 + 余弦相似度检索（附中文分词大词 + CJK 二元组词法兜底），情节记忆带时间衰减加权，结果按预算注入
- **三层记忆**（源自 orangechat/Tumin）：固定记忆区（独立编辑、不覆盖角色卡）+ 近期生活流（跨会话记忆当前对话，其他对话的未读事件自动注入）+ 长期记忆词项重叠打分召回；生活流超阈值自动后台摘要
- **memory_tool 增强**：新增 `list` 读取操作与 fact / episodic 类型区分，模型可先查看已有记忆再决定写入或更新
- **记忆管理页**：按助手独立查看 / 编辑 / 删除记忆条目
- **上下文滚动压缩**：长对话超阈值（按模型上下文窗口自动算或手动指定）时，用压缩模型把早期对话滚动摘要（原文保留、仅请求时替换前缀），突破上下文窗口而不丢人设与伏笔
- **最近对话引用**：可选把该助手最近的对话列表注入提示词，跨会话连续性
- **瞬态内容裁剪**：超过两轮的网页搜索结果 / 图片 / 音视频自动从请求中剔除（附消息 ID，AI 可用 `read_history_message` 取回原文）
- **首轮自动读取记忆**：第一轮自动注入最近记忆（开局没有相关性查询可用），之后按相关性按需召回 —— AI 开局就认识你

> 记忆类功能（记忆 / RAG 检索 / 三层记忆 / 跨窗口生活流）**默认开启**——记忆让 AI 更了解用户，不为省上下文牺牲体验。已有助手保留原有设置，可随时在助手级关闭。

### 🧿 Jev 智能决策（TypeSafe System One）

设置 → 华灯设置 → **Jev 智能决策**。Jev 是一个**只做判断、不生成文本**的专用小模型（端到端约 100ms，输出 token 免费），作为客户端内部的隐形决策层挂载，填入 API Key 即可启用。两个接管开关：

| 开关 | 作用 |
|---|---|
| **自动记忆筛选** | 用 Jev 逐条判断"这条记忆与当前对话相关吗"，替代 embedding 相似度检索——省掉 embedding API 调用。候选按时间倒序分批并行判断，相关概率过半即注入；身份类记忆（称呼、偏好）即使对话没直接提到也算相关 |
| **大模型工具调用（judge）** | 给主模型挂一个 judge 工具（界面显示「Jev 明断」）：拿不准是非题 / 选择题 / 打分题时直接问 Jev，不再自己猜。系统提示同步注入工具路由说明，弱模型也能发现并使用 |

**静默是硬要求**：Jev 不可用（没配 Key、网络失败、置信度不足）时，记忆与工具全部回退原有逻辑，不弹错误、不阻塞对话。

### 🧩 插件系统

QuickJS 沙箱插件。插件 = ZIP 包（`manifest.json` + `main.js`），设置 → **插件管理**一键导入。

> 插件系统源自 orangechat / Tumin，本分支在其之上补齐了工具型插件所需的沙箱能力（会话式 HTTP、图片解码、详情页卡片）。

#### 能做什么

- manifest 声明的 `tools` 自动转成 AI 可调用的工具（统一 `plugin_` 前缀）
- **系统提示词注入**：`systemPrompt` 直接注入，或 `sections` 每段配一个开关（支持 `file:` 外置文件与 `enc:` AES-256-GCM 加密文件）
- **详情页数据卡片**：声明 `detailCard` 后，插件详情页调用该导出函数渲染成实时状态卡片（如卡路里的「今日摄入」）
- 文件夹归类、启用/停用、per-插件配置表单（文本 / 密码 / 开关 / 下拉 / **模型选择**）
- 每插件独立 `dataStore` 键值存储，数据落在 `plugin_data_<id>`

#### 沙箱内置能力

| API | 用途 |
|---|---|
| `fetch(url, options)` | 一次性请求，**不保存 Cookie**，域名白名单 fail-closed |
| **`http.*`** | 会话式请求，**自带 Cookie 罐**（自动保存每一跳 `Set-Cookie`），可取二进制 `bytes()` / `base64()`。用于「先拿 session 再带着登录」的流程——教务、论坛这类 |
| **`image.decode(data)`** | 图片 → RGBA 像素数组（布局同 `canvas.getImageData`）。沙箱没有 canvas，逐像素处理（验证码识别、取色）靠它 |
| `dataStore` | `set` / `get` / `del` / `list` |
| `btoa` / `atob` / `TextEncoder` / `TextDecoder` | 编解码 |
| `config` | 插件配置注入的全局对象 |

单线程执行，工具调用 30 秒超时，详情页卡片 8 秒超时。**只支持 ES5**（`async`/`await` 会被预处理为同步）。

#### 安全

- 导入两段式：先解析 manifest 预览确认，再落盘
- 插件目录 **SHA-256 完整性校验**（`.integrity`），被改动后自动停用
- **Zip Slip 防护**：解压时校验每个条目的 canonical path
- `allowedHosts` 为空 = 拦截全部网络请求
- 插件只能访问显式注入的桥接对象，拿不到宿主能力

> ⚠️ 插件是第三方代码，请只导入你信任的来源。

#### 内置插件

| 插件 | 说明 |
|---|---|
| 🍱 **卡路里与蛋白质记录** | 跟 AI 说一句「中午吃了碗牛肉面」，热量与蛋白质就都记下来了。每日目标在插件设置里配，详情页显示今日摄入与各自剩余额度 |
| 🎓 **云南财经教务系统** | 课表 / 成绩 / 考试安排 / 教务公告 / 空闲教室查询。账号密码填在插件设置里，验证码本地 OCR 自动识别，连续失败则返回图片让你手输 |

两个插件包在 [`docs/plugins/`](docs/plugins/)（含源码，可直接参考），开发指南见 [docs/PLUGINS_GUIDE.md](docs/PLUGINS_GUIDE.md)。

<details>
<summary><b>✍️ 写插件的最小例子（点击展开）</b></summary>

`manifest.json`：
```json
{
  "id": "com.example.hello",
  "name": "打个招呼",
  "version": "1.0.0",
  "entry": "main.js",
  "tools": [{
    "name": "hello",
    "description": "向指定的人问好",
    "parameters": [{ "name": "who", "type": "string", "required": true }]
  }],
  "allowedHosts": []
}
```

`main.js`：
```js
exports.hello = function (args) {
  var name = args && args.who ? args.who : '世界';
  return { greeting: '你好，' + name + '！' };
};
```

打包成 ZIP（**文件必须在根目录，不能有顶层文件夹**）即可导入。

</details>

### 🗼 中转站兼容与防空回复

Gemini 经 OpenAI 兼容中转站（newapi 等）接入时的经典病态，自动修复：

- **正文被 reasoning_content 吞掉**：部分中转把实际回复塞进推理字段，`content` 只剩伪影——流结束后若正文为空而推理有实质内容，自动把推理提升为正文（思考阶段不误判，正常模型的长思考 + 短回答不受影响）
- **`response` 前缀伪影**：正文开头出现 `response` / `Response:` 残留——流式与非流式路径均自动剥离，前缀被拆散在多个 delta 中也能完整识别；带边界判定，不会误伤 `responses` 等英文单词
- **正文截断**：提升与剥离逻辑让回复完整呈现，不再「思考里是答案、正文只剩半截」

**防空回复（全局版）**：

- **系统提示词入对话流**：SYSTEM 消息原位转为 user 轮（首条后跟模型确认轮），世界书 / 人设 / 滚动摘要等注入位置与内容保持不变 —— 绕开 Gemini 对 `systemInstruction` 的安全拦截
- **空回复自动微扰重试**：检测到无文本、无工具调用的空回复时，对末条用户消息做标点微扰（加/删句号、加空格，4 变体轮换）自动重试至多 3 次

两者默认关闭，在华灯设置或助手编辑页开启。

### 🔊 豆包语音（火山引擎 Agent Plan）

- **Doubao TTS**：豆包语音合成大模型 2.0
  - `seed-tts-2.0` 资源 + 内置 14 个 2.0 音色预设（默认灿灿 2.0，另有快乐小东、甜美桃子、高冷御姐等），支持手动输入任意音色 ID
  - 语速调节、音频格式（mp3/wav/pcm/ogg/opus）与采样率可选
  - 完整解析火山特有的**拼接式 JSON 分块流**响应，长音频合成验证通过
  - Agent Plan 专属接口路径默认内置，标准控制台用户可改回官方路径
- **火山 ASR**：Agent Plan 的 `ark-xxx` 密钥只在 `/api/v3/plan/` 专属路径有效，默认地址已切换为 plan 专属路径，标准控制台密钥用户可在设置中改回
- **一个 Agent Plan API Key** 同时驱动语音输入与语音输出

> 配合语音 / 视频通话使用：聊天页顶栏一键进入通话界面（仅在 TTS 与 ASR 均已配置时显示）——本地静音检测 → ASR 增量转写 → 自动发送 → 回复流式朗读，支持随时打断，挂断后通话内容折叠成存档卡片留在会话里。

### 📱 手机增强

#### 设备工具箱（30 个工具）

助手工具页开启后，AI 可调用手机系统能力：

手电筒 · 震动 · 音量读写 · 亮度读写 · Toast · 电量 · 存储 · Wi-Fi / 音频 / 电话 / 传感器信息 · 分享 · 壁纸 · 系统通知 · 闹钟 / 计时器 · 音乐控制 · 短信读取 · 联系人 · 通话记录 · 定位 · 应用启动 · 打开网页 · 媒体扫描 · 文件下载 · 打开文件

为节约 token 采用**懒发现元工具**：上下文里只注册一个 `device_toolbox` 工具位，AI 先 `action=list` 拉取工具目录（含参数说明与权限授予状态），再 `action=run` 调用具体工具。

#### 微信 Bot / QQ Bot

把某个已有助手接入消息通道（AI、记忆、工具全部复用该助手）：

- **微信 Bot**：扫码登录自己的微信号（iLink 协议），HTTP 长轮询收消息 → 自动回复；token 过期自动停服并通知
- **QQ Bot**：QQ 开放平台官方 API，填入 AppID + AppSecret 即可，WebSocket 网关实时收发，token 自动刷新

两者默认关闭，开启前有隐私风险确认弹窗。

#### AI 主动发消息

AlarmManager 精确闹钟 + WorkManager 兜底双通道，随机间隔（可设范围）主动找你聊天；生成时注入上下文（上次聊天距今、当前时间、电量），正在生成时礼貌放弃不冲突。默认关闭。

### 🎨 外观自定义与主题

- **颜色覆盖**：主色、全局文字、用户 / AI / 思维链气泡、聊天背景、输入框 7 项自定义颜色（ARGB）
- **气泡美化**：用户 / AI 气泡背景图 + 圆角 + 主题色遮罩，抽屉背景图与**聊天背景图**（图片上叠加聊天背景色遮罩）
- **文字颜色细化**：正文 / 引用 / 斜体色独立设置，附酒馆橙、暖金、玫红、珊瑚、薰衣草、天蓝、薄荷等预设色板
- **酒馆美化主题导入**：SillyTavern 美化主题 JSON 一键导入，已用 **537 个真实主题**全量验证（537/537 解析成功）：
  - **叠层配色合成**：按酒馆渲染层级（`blur_tint` → `chat_tint` → 消息气泡色调 → 背景图）把多层半透明色调合成为不透明近似色，透明气泡主题不再变成整片色块；8 位 hex 按 CSS 规范 `#RRGGBBAA` 解析
  - **背景图**：从 `custom_css` 识别 `#bg1` / `body` / `#chat` 上的 `background-image`（URL 自动下载、data URI 解码）
  - **气泡样式**：`.mes` / `#chat` 的 `border-radius` 映射为气泡圆角；`chat_display=1`（气泡模式）自动开启 AI 气泡显示
  - **主题字体**：`@font-face` 里的 ttf/otf 自动下载并设为聊天字体（woff/woff2 忽略）
  - 其余：`main_text_color` → 全局文字、`quote/italics_text_color` → 引用/斜体色、`font_scale` → 字号比例
- 预设色板 + HCT 自定义主题 + 动态取色保持不变

### 🛠 技能与工具

#### 技能（Skills）

- **自动触发**：匹配关键词即注入 `SKILL.md`，不依赖模型主动调用
- **公共技能目录** `/Rikkahub/skills`：文件管理器直接增删
- **GitHub 一键安装 / 批量下载 / 更新检测**：支持子目录与多技能仓库，记录来源与整目录哈希

#### 工具集

上游基础上新增：

| 工具 | 中文名 | 说明 |
|---|---|---|
| `file` | 书阁司卷 | 统一文件工具：读 / 写 / patch / 列 / 搜 / 复制 / 移动 / 建目录 / 删除 |
| `execute_command` | 乾纲令行 | 设备上执行 Shell 命令，返回 stdout/stderr/退出码 |
| `execute_python` | 灵枢演算 | 设备内隔离的 Python 执行（Chaquopy） |
| `calculator` | 神机妙算 | 700+ 函数计算器（统计 / 金融 / 矩阵 / 微积分 / 物理） |
| `database_query` | 天书检阅 | 只读 SQLite 查询本机数据库 |
| `task_*` | 布局落子 / 运筹帷幄 | 任务列表创建 / 查询 / 更新 / 团队编排 |
| `web_fetch` | 摘星引卷 | 任意 URL 的 HTTP 请求（GET/POST/PUT/PATCH/DELETE） |
| `present_file` | 献卷呈览 | 通过系统分享面板分享文件 |
| `read_history_message` | 回溯拾遗 | 取回被瞬态裁剪的历史消息原文（开启裁剪时注册） |
| `device_toolbox` | 万象百宝囊 | 30 个设备工具的懒发现入口 |
| `life_*` / `shared_*` / `*_couple_space` | 手札杂记 / 共读同赏 / 观俪影轩 等 | 生活空间与情侣空间操作 |
| `memory_tool` / `conversation_search` / `recent_chats` | 心念归藏 / 探寻搜查 / 溯源观澜 | 记忆与对话检索 |
| `judge` | Jev 明断 | 询问 Jev 判断模型是非题 / 选择题 / 打分题（见 [Jev 智能决策](#-jev-智能决策typesafe-system-one)） |

另有**系统提示词装配器**（工具选用指南 / 工作守则）。

#### 工具调用兼容

- **DSML 文本工具调用兼容**：模型不走 function calling、而是把调用写在正文里时，自动解析并执行，并从正文中清除标记
- **工具别名映射**：`web_search` → `search_web` 等 DSML 惯用名自动映射到实际注册名
- **工具中文雅称**：工具调用在界面上一律显示中文名（`web_search` → 联网搜索，`execute_python` → 灵枢演算）

### 💞 情感陪伴

可在**华灯设置 → 清爽简洁模式**一键隐藏，隐藏后也不再向 AI 注册对应工具。

- **情侣空间**：绑定恋人后解锁「兔眠空间」（双方都能发动态、AI 会在评论区真的回复）、「我们的日记」、「纪念日」
- **生活空间**：今日 / 周期与身体 / 备忘录 / 日历提醒 / 一起听 / 共读书架六个面板，AI 可通过工具参与记录

---

## 🍺 酒馆兼容（对齐 SillyTavern 官方语义）

**既有的酒馆资产可直接迁移**——全部按 SillyTavern 官方语义解析，而非近似转换：

| 资产类型 | 兼容情况 |
|---|---|
| **角色卡**（PNG / V2 / V3 JSON） | 字段覆盖 **20+**（上游仅 6 个）：示例对话、备选开场白、多语备注、对话后指令、角色版本、标签、昵称、资源、内嵌世界书、`extensions` 原始 JSON……上游丢弃的字段全部保留，**导入 → 导出往返无损** |
| **世界书 / Lorebook** | 条目字段 **30+**，逐条对齐官方 `world-info.js`：四种次级关键词逻辑、整词/正则/大小写、条目级扫描深度、常驻激活、跨书分组 + 权重 + 覆盖、触发概率、粘滞 / 冷却、延迟激活、递归排除 / 阻止递归 / 延迟递归、预算豁免、角色字段匹配 ×6 |
| **预设**（Preset） | 按官方提示词管理器结构导入 |
| **正则脚本**（Regex） | Find / Replace / `_ALT`、OnlyFormat、宏支持、注入深度（minDepth/maxDepth）、排序与缓存，同时作用于展示层与提示词层 |
| **快速回复**（QR） | QR 集合导入，输入框斜杠面板一键执行 |
| **美化主题**（Theme） | **537 个真实主题全量验证通过**：叠层配色合成、`custom_css` 背景图、气泡圆角、主题字体 |
| **HTML 展示卡** | 直接渲染，默认展开 + 点击全屏 |
| **多开场白** | `alternate_greetings` 全量导入，聊天中随时切换 |

**提示词链路同样遵循官方结构**：主提示词、角色字段独立消息、示例对话按 `<START>` 拆分为真实 user/assistant 轮次、PHI 追加在历史之后、深度提示词按配置的深度与角色注入。

### 与常见安卓酒馆方案的区别

搜索"安卓酒馆"返回的项目多为**容器或启动器**——将 Node.js 与 SillyTavern 一并打包运行（其描述中常见 *runner*、*launcher*、*容器*、*installer*、*local Node.js server* 等措辞）。

本项目为**原生实现**：基于 Kotlin + Jetpack Compose 的完整客户端，酒馆兼容层是核心代码而非外挂。区别在于：

- **无需运行 Node 服务**，不占用后台常驻内存，冷启动更快
- **酒馆能力与客户端能力互通**：角色卡的世界书可调用插件工具、记忆系统、语音朗读与设备工具箱
- **完整的移动端体验**：Material You 主题、手势操作、通知栏、分享面板

### 同时优化了酒馆的两处常见痛点

- ⚡ **长对话成本** → [提示词前缀缓存优化](#-提示词前缀缓存优化)，消除每轮变化的分叉点
- 🧠 **上下文溢出与记忆丢失** → [记忆与长对话](#-记忆与长对话)，语义 RAG + 三层记忆 + 滚动压缩

> 酒馆核心（角色卡结构、世界书引擎、宏引擎 2.0、斜杠命令、群聊）来自中间分支
> [heikeyangle-code/rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus) 的 `mingli2` 分支。
> 本分支在其基础上**新增**：HTML 卡片渲染、多开场白导入、预设与正则脚本导入、QR 快速回复导入、
> 世界书编辑器字段补全 + Token 预算兜底、Vector Storage 语义条目、提示词查看器、
> 正则深度限制与缓存、开场白宏替换。

<details>
<summary><b>📖 酒馆系统详解（点击展开）</b></summary>

#### 1. 角色卡：导入 → 结构化 → 注入 → 导出 → 编辑

- **字段覆盖 20+（上游仅 6 个）**：示例对话、备选开场白、多语 creator_notes、对话后指令（post_history_instructions）、角色版本、标签、昵称、资源、group_only_greetings、创建/修改日期、内嵌世界书、extensions 原始 JSON（含深度提示词的 depth/role）——上游丢弃的字段全部保留，**导入 → 导出往返无损**
- **官方 Chat Completion 注入结构**：主提示词、角色字段独立消息、示例对话按 `<START>` 拆成真实 user/assistant 轮次、PHI 追加在历史之后、深度提示词按配置的深度/角色注入
- **V2 / V3 双版本**：V3 高级字段与 PNG 卡（ccv3）支持，非 PNG 自动转 PNG 并做注入宏替换
- **可视化角色卡编辑器**：全部字段编辑 + 内嵌世界书管理 + 一键导出（JSON / PNG 嵌入）
- **多开场白**：`alternate_greetings` 全量导入，可在聊天中随时切换
- **HTML 卡片**：酒馆 HTML 展示卡直接渲染，默认展开 + 点击全屏

#### 2. 世界书（Lorebook）

逐条对齐酒馆官方 `world-info.js` 语义，条目字段从上游 6 个扩展到 30+：

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

**扫描引擎**：完整实现官方 `checkWorldInfo` 状态机（INITIAL → 递归 / 最少激活 / 延迟层级循环），带预算、溢出、粘滞、冷却全生命周期；跨书分组按官方规则选出唯一条目（粘滞优先 → 关键词评分 → 组覆盖 → 加权随机）。

**世界书编辑器**：全局设置（扫描深度、Token 预算 + 绝对上限、最少激活 + 最大深度、递归扫描 + 步数上限、插入策略、溢出提醒、组评分）、条目全字段编辑、拖拽排序、外置/内嵌双向同步、Vector Storage 语义条目。

#### 3. 预设 / 正则 / 美化主题

- **预设导入**：酒馆 JSON 预设按官方提示词管理器结构导入
- **正则脚本导入**：Find/Replace/`_ALT`、OnlyFormat、宏支持、注入深度（minDepth/maxDepth）、排序与缓存，作用于展示与提示词两层
- **美化主题导入**：见[外观自定义与主题](#-外观自定义与主题)

#### 4. 快速回复（Quick Replies）

酒馆 QR 集合导入，输入框斜杠面板一键执行。

#### 5. 宏引擎 2.0

- **变量**：`{{setvar}}` `{{getvar}}` `{{.var}}` 简写全家桶，全局 + 会话级持久化 —— 角色卡可以记住剧情状态
- **条件**：`{{if}} / {{else}}`、比较运算符、`&&` / `||`、作用域块、嵌套
- **随机与时间**：`{{pick}}`（回合内稳定）、`{{roll::1d20}}`、`{{random}}`、`{{time}}`、`{{datetimeformat}}`
- **对话感知**：`{{lastUserMessage}}` `{{lastCharMessage}}` `{{idleDuration}}` `{{charFirstMessage::N}}` `{{original}}` 等 **60+ 官方宏**全量支持
- 未知宏原样保留，不破坏提示词

#### 6. 斜杠命令

输入框直接敲，`/help` 列出全部命令与说明。**20+ 内置命令**：

- **角色扮演**：`/impersonate`（AI 以你的口吻起草）、`/continue`、`/sendas`、`/sys`、`/sysgen`、`/trigger`、`/message-name`、`/delname`
- **变量与随机**：`/listvar` `/setvar` `/getvar` `/addvar` `/incvar` `/decvar` `/flushvar` `/reroll-pick`
- **角色卡管理**：`/char-update` `/char-duplicate` `/rename-char`
- **注入**：`/inject`（按位置/深度/角色注入）、`/prompt`
- 技能提供的命令自动出现在面板中

#### 7. 人设 Persona 与作者注释

- **人设**：官方五档注入位置（IN_PROMPT / TOP / BOTTOM / AT_DEPTH / NONE）、按角色绑定、独立 SYSTEM 消息注入、一键禁用
- **作者注释**（导演备注）：官方间隔语义（每次 / 每 N 条用户消息）、注入深度与角色、总开关

#### 8. 群聊

多角色同场对话，每个成员有独立提示词 / 人设 / 模型；4 种发言策略（NATURAL AI 选人 / 列表 / 加权随机 / 手动）+ 5 种扩展模式；自动接话（轮数 1-10 可设、延迟可设、被用户消息打断）；发言者实时状态；群聊持久化。

</details>

---

## 🔍 常见需求对照

| 需求 | 对应功能 |
|---|---|
| **找一个好用的安卓 AI 聊天 App** | 即本项目：接上 API 就能聊，多供应商 + 流式 + 语音 + 记忆 + 插件 |
| **降低长对话 API 花费** | 提示词前缀缓存优化，命中部分大幅降价 |
| **AI 总忘记之前说过什么** | 语义记忆 RAG + 三层记忆 + 滚动压缩，可叠加 Jev 记忆筛选 |
| **在安卓设备上使用酒馆角色卡与世界书** | 角色卡 V2/V3/PNG、世界书 30+ 字段、预设、正则、QR、美化主题，全部按官方语义导入 |
| **寻找安卓平台的 SillyTavern 客户端** | 即本项目，酒馆兼容层为核心模块 |
| **将桌面端酒馆数据迁移至手机** | 角色卡 / 世界书 / 预设 / 正则 / QR / 主题，六类资产均支持导入 |
| **手机端酒馆性能不足，或不愿配置 Termux** | 原生客户端，无需 Termux 与 Node.js，直接安装 APK |
| 接入自建中转站或第三方 API | 兼容 OpenAI / Anthropic / Google / DeepSeek 协议，中转站兼容开关可修复常见异常 |
| 为 AI 扩展自定义工具 | QuickJS 插件系统，编写 `main.js` 打包为 ZIP 即可导入 |
| 在微信 / QQ 中继续对话 | 微信 Bot（扫码登录）、QQ Bot（官方 API），复用任意助手的 AI 与记忆 |
| 语音朗读与语音对话 | 豆包 TTS 2.0 + 火山 ASR，支撑语音 / 视频通话 |
| 让 AI 操作手机 | 设备工具箱 30 个工具：手电筒、音量、短信、联系人、定位、通知等 |
| 注重隐私 | 请求日志脱敏、工具审批边界、遥测默认关闭，数据全部保存在本机 |

> **搜索关键词**：AI 聊天 App、安卓 AI 客户端、AI 助手、AI 记忆、提示词缓存、prompt cache、
> 安卓酒馆、手机酒馆、酒馆客户端、SillyTavern 安卓、SillyTavern Android、酒馆手机版、
> 角色卡导入、世界书、Lorebook、角色扮演 AI、AI 角色扮演、RP 客户端、
> Android LLM chat、AI 聊天客户端、RAG 记忆、OpenAI 兼容中转、
> Kotlin Jetpack Compose AI 应用、本地 AI 聊天。

---

## 🏮 华灯设置（本分支专属）

设置 → **华灯设置**：把本分支新增的兼容与辅助功能集中在一个页面，全局开关对所有助手生效（助手级开关可单独覆盖）。

### 兼容与辅助

| 开关 | 说明 |
|---|---|
| **中转站兼容** | Gemini 经 OpenAI 兼容中转接入时的三类经典病态自动修复（默认关） |
| **防空回复** | Gemini 空回复自动微扰重试 + 系统提示词入对话流（默认关） |
| **上下文瞬态内容裁剪** | 超过两轮的网页搜索结果、图片、音视频不再随每次请求发送（占位说明附消息 ID），大幅减少图片与搜索类长对话的 token 消耗 |
| **清爽简洁模式** | 隐藏情侣空间、生活空间等娱乐入口，且不再向 AI 注册对应工具，界面更简洁、上下文更省 token |
| **上下文滚动压缩** | 对话过长时自动把早期消息压缩为摘要。关闭后不再自动压缩（助手级开关仍可单独启用） |
| **工具结果截断** | 工具输出超过 32KB 自动截断并保存到文件。中转站导致工具调用异常时可尝试关闭 |
| **系统提示词转义** | 把系统消息中的 `<` `>` 转为 HTML 实体，绕过中转站 WAF 拦截（遇到 `upstream_content_rejected` 时可开启） |

### 接入与自动化

微信 Bot、QQ Bot、AI 主动发消息、安全设置、导演备注五个入口。

> **安全设置**（设置 → 安全设置）：全局工具调用审批策略 —— **强制确认所有工具调用**（每次执行前都要确认）或**自动批准所有工具调用**（跳过审批，谨慎开启），两项互斥。前者默认关闭，后者默认开启。

### Jev 智能决策

TypeSafe System One 判断模型的接入配置页：API 地址 / Key / 模型名、置信度阈值滑块，以及两个接管开关（自动记忆筛选、大模型工具调用）。详见 [Jev 智能决策](#-jev-智能决策typesafe-system-one)。

---

## 🛡 隐私与稳定性

### 隐私加固

- **请求日志脱敏**：请求头白名单机制，提示词 / Schema / 二进制 / 凭据脱敏，错误信息密钥掩码与体积上限
- **工具审批边界**：剪贴板 / 屏幕时间 / Shell 每次执行需审批，文件工具写入类操作需审批；屏幕时间限制查询范围与明细条数且不输出包名
- **备份恢复资源预算**：限制条目数、单条目与总解压大小，防御异常归档耗尽资源
- **按日文件清理**：聊天附件与生成图片可按保留天数自动清理（默认关闭）
- **Firebase 遥测默认关闭**：构建属性开启才启用 Google 服务与 Crashlytics
- **插件沙箱**：见[插件系统 → 安全](#安全)

### 稳定性

- **前台服务保活**：切后台不断流，生成不被系统杀死
- **SSE 长连接加固**：OkHttp 30s PING 保活，事件流请求禁用缓存与压缩，代理环境下流式输出不再被缓冲截断
- **数据库平滑升级**：全部 schema 变更走显式迁移，老数据无损升级；迁移全部幂等，重复列不再崩溃
- 备份导入一致性快照、启动安全恢复、图片选择迁移 PickVisualMedia 等大量修复

---

## 📦 下载

| 渠道 | 说明 |
|---|---|
| **稳定版** | [Releases](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/releases) 按版本发布（`2.5.4fixN`） |
| **Nightly** | Actions 每天两次自动构建（过去 24 小时无新提交则跳过），覆盖 [nightly](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/releases/tag/nightly) 预发布；版本按日期推进（`2.5.4fixYYYYMMDD`） |
| **手动构建产物** | 每次推送在 [Actions](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/actions) 产出 APK Artifact（构建页底部「Artifacts → rikkahub-plus-fresh」，需登录） |
| **应用内更新** | 设置 → 关于 → 检查更新（GitHub Releases API，国内可达镜像自动回退） |

> 仅提供 `arm64-v8a` 单一架构 APK，支持 Android 8.0（API 26）及以上。

---

## ✅ 与上游的关系

- **上游功能全部保留**：Material You 主题、多供应商、流式生成、会话分叉与重新生成、消息编辑 / 删除 / 翻译、全文搜索（jieba）、收藏、图片生成、TTS / ASR、MCP、工作区沙箱（终端多 Tab + Shell 兼容模式）、备份（S3 / WebDAV）、网络对话端、聊天导出等一切照旧
- **已合入上游版本**：`rikkahub/rikkahub` master **v2.5.2**（2026-09）
- **相对中间分支 mingli2**：除酒馆增强外，新增提示词前缀缓存、语义记忆 RAG 与滚动压缩、Jev 智能决策、中转站兼容与防空回复、豆包语音、隐私加固
- **v2.5.4 以来新增**：教务系统移出应用改为插件、卡路里与蛋白质记录插件、插件沙箱的会话式 HTTP 与图片解码、详情页数据卡片、更新检查改用 GitHub Releases API、Jev 智能决策接入
- **合并上游**：见 [DIVERGENCE.md](DIVERGENCE.md) 的冲突处理手册

---

## 🙏 致谢与版权说明（Credits）

本项目站在前人的肩膀上，特别感谢以下项目：

| 项目 | 关系 | 许可证 |
|---|---|---|
| [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) | **原始上游项目**，本仓库的全部基础功能来自它 | AGPL-3.0 |
| [heikeyangle-code/rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus) | **直接上游（中间分支）**，酒馆系统、宏引擎、斜杠命令、群聊等核心增强的开发者 | AGPL-3.0 |
| [SillyTavern/SillyTavern](https://github.com/SillyTavern/SillyTavern) | **酒馆系统的兼容目标**；角色卡 / 世界书 / 宏 / 斜杠命令的**语义与格式规范**参考其官方实现（AGPL-3.0），本项目未复制其代码 | AGPL-3.0 |
| [sue1231513/orangechat](https://github.com/sue1231513/orangechat) | 同源分支，本项目从它与其下游 Tumin 引入了**情侣空间 / 生活空间 / 三层记忆 / 聊天外观自定义 / QuickJS 插件系统**等特色功能 | AGPL-3.0 |
| [lingwangshu018/Tumin](https://github.com/lingwangshu018/Tumin) | orangechat 的下游分支，同上 | AGPL-3.0 |
| [ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent) | 同源分支，设备工具箱的部分工具实现参考 | AGPL-3.0 |
| [YaeNovin/Rikkahub-Revised](https://github.com/YaeNovin/Rikkahub-Revised) | 同源分支，本项目从中移植了**语义记忆 RAG** 与**上下文滚动压缩** | AGPL-3.0 |

本项目与其上游均为 **AGPL-3.0** 许可，本仓库沿用同一许可证继续开源。各上游项目的版权归其原作者所有，感谢他们慷慨开源。

---

<div align="center">

如果这个分支对你有用，欢迎点一个 ⭐ Star ✨

</div>
