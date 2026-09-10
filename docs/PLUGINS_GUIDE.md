# RikkaHub Plus 插件开发指南

> 本指南源自 orangechat/Tumin 的插件体系（AGPL-3.0），按 RikkaHub Plus 的实现做了裁剪：
> 不支持声明式 UI 页（ui）、WebView 自定义页（customPage/customPageWebView）、事件钩子（hooks）与提示词模板注入（promptTemplate）。
> 工具型插件（tools + config）完整可用。

> 本文档面向插件开发者，介绍如何为 RikkaHub Plus 编写、打包和安装插件。

---

## 一、快速开始

### 1.1 插件是什么

插件是一个 **ZIP 压缩包**，内部包含：

```
my-plugin.zip
├── manifest.json      ← 插件元数据（必填）
└── main.js            ← 入口脚本（必填）
```

安装时，用户通过「设置 → 插件管理 → 导入 ZIP」选择文件，橘瓣会自动解压并加载。

### 1.2 Hello World

**manifest.json**

```json
{
  "id": "com.example.hello",
  "name": "Hello 插件",
  "description": "一个最简示例插件",
  "version": "1.0.0",
  "author": "你的名字",
  "icon": "👋",
  "entry": "main.js",
  "tools": [
    {
      "name": "say_hello",
      "description": "向用户打招呼",
      "parameters": [
        {
          "name": "name",
          "type": "string",
          "description": "用户名字",
          "required": true
        }
      ]
    }
  ]
}
```

**main.js**

```javascript
function say_hello(params) {
  var name = params.name || "陌生人";
  return {
    success: true,
    greeting: "你好，" + name + "！"
  };
}

exports.say_hello = say_hello;
```

将这两个文件打包为 ZIP 后导入，AI 就能调用 `say_hello` 工具了。

---

## 二、manifest.json 字段详解

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | ✅ | 插件唯一标识，建议反向域名格式，如 `com.example.plugin.name` |
| `name` | string | ✅ | 显示名称 |
| `description` | string | ✅ | 一句话描述 |
| `version` | string | ✅ | 版本号，如 `1.0.0` |
| `author` | string | ✅ | 作者名 |
| `icon` | string | ✅ | 图标，可以是 Emoji（如 `🌤️`）或 URL |
| `entry` | string | ✅ | 入口文件路径，相对于插件根目录，如 `main.js` |
| `tools` | array | ❌ | 向 AI 注册的工具列表 |
| `config` | array | ❌ | 用户配置项，安装后在插件详情页显示设置表单 |
| `permissions` | array | ❌ | 插件权限声明，目前支持 `ai_chat`、`disable_native_selection` |
| `allowedHosts` | array | ❌ | 网络域名白名单。空数组 = 禁止所有网络请求。`*` = 允许所有（不推荐） |
| `hooks` | array | ❌ | 事件钩子，如监听消息发送/接收、每日定时 |
| `promptTemplate` | string | ❌ | 注入到 AI 系统提示词的模板，让 AI 知道如何使用该插件 |
| `customPage` | string | ❌ | 内置页面标识，如 `"memory_bank"` |
| `customPageWebView` | object | ❌ | WebView 自定义页面配置，`{ "entry": "ui/index.html" }` |
| `ui` | object | ❌ | 声明式 UI 定义，渲染为原生 Compose 界面 |

### 2.1 tools 工具定义

```json
{
  "name": "工具函数名（AI 调用时使用的名称）",
  "description": "AI 看到的功能描述",
  "parameters": [
    {
      "name": "参数名",
      "type": "string | number | integer | boolean | object | array",
      "description": "参数说明",
      "required": false
    }
  ]
}
```

工具函数名必须与 `main.js` 中 `exports.xxx` 导出的函数名一致。

### 2.2 config 配置项

```json
{
  "config": [
    {
      "name": "api_key",
      "type": "string",
      "label": "API Key",
      "description": "你的 API 密钥",
      "required": true,
      "placeholder": "sk-..."
    },
    {
      "name": "enabled",
      "type": "boolean",
      "label": "启用功能",
      "default": true
    },
    {
      "name": "model",
      "type": "model",
      "label": "选择模型",
      "description": "用于 AI 调用的模型"
    }
  ]
}
```

支持的 `type`：`string`、`number`、`boolean`、`select`、`password`、`model`。

配置值会在运行时注入为全局变量 `config`，如 `config.api_key`。

### 2.3 hooks 事件钩子

```json
{
  "hooks": [
    { "event": "message_sent", "handler": "onMessageSent" },
    { "event": "message_received", "handler": "onMessageReceived" },
    { "event": "daily_cron", "handler": "onDailyCron", "schedule": "0 3 * * *" }
  ]
}
```

| 事件 | 触发时机 | event 参数 |
|------|---------|-----------|
| `message_sent` | 用户发送消息、已落库、AI 尚未回复前 | `{ assistant_id, conversation_id, message, role: "user", timestamp }` |
| `message_received` | AI 回复生成完成、已落库后 | `{ assistant_id, conversation_id, message, role: "assistant", timestamp }` |
| `daily_cron` | 每天凌晨定时触发（默认 03:00） | `{ timestamp, date, hour, minute }` |

> ⚠️ 所有 hook 都在单线程上串行执行，超时 16.5 秒会被跳过。

---

## 三、main.js 编写规范

### 3.1 运行环境

- **引擎**：QuickJS（ES5 子集）
- **禁止**：`let`、`const`、箭头函数 `=>`、模板字符串 `` ` ``、`async/await`、`Promise`
- **必须**：用 `var` 声明变量、传统 `function` 定义函数、字符串拼接用 `+`

### 3.2 模块导出

通过 `exports` 对象导出工具函数：

```javascript
function my_tool(params) {
  // ...
}
exports.my_tool = my_tool;
```

只有 `exports` 上的函数才会被 AI 识别为可用工具。

### 3.3 返回值格式

工具函数返回一个**普通对象**，橘瓣会将其序列化为 JSON 展示给 AI：

```javascript
return {
  success: true,
  data: "...",
  error: null   // 失败时填错误信息
};
```

---

## 四、沙箱内置 API

### 4.1 网络请求 fetch（同步）

```javascript
var response = fetch(url, options);
```

| 参数 | 说明 |
|------|------|
| `url` | 请求地址 |
| `options.method` | `GET` / `POST` / `PUT` / `DELETE`，默认 `GET` |
| `options.headers` | 请求头对象 |
| `options.body` | 请求体字符串 |

**返回值**：

```javascript
{
  ok: true,           // HTTP 2xx 为 true
  status: 200,
  headers: { "content-type": "application/json" },
  body: "...",        // 原始响应文本
  text: function() { return this.body; },
  json: function() { return JSON.parse(this.body); }
}
```

**示例**：

```javascript
function get_weather(params) {
  var city = params.city || "Beijing";
  var url = "https://wttr.in/" + encodeURIComponent(city) + "?format=j1";
  var response = fetch(url);

  if (!response.ok) {
    return { success: false, error: "请求失败" };
  }

  var data = response.json();
  return {
    success: true,
    temperature: data.current_condition[0].temp_C + "°C"
  };
}
exports.get_weather = get_weather;
```

> ⚠️ `fetch` 是**同步阻塞**调用，超时 15 秒。目标域名必须在 `manifest.allowedHosts` 中声明，否则请求会被拒绝。

### 4.2 配置 config

```javascript
var apiKey = config.api_key;        // 读取用户在设置页填写的值
var enabled = config.enabled;
```

`type: "model"` 的配置会自动解析为对象：

```javascript
// manifest: { "name": "chat_model", "type": "model" }
var modelId = config.chat_model.modelId;
var baseUrl = config.chat_model.baseUrl;
var apiKey  = config.chat_model.apiKey;
```

### 4.3 记忆库 memoryBank

```javascript
memoryBank.save("用户喜欢喝拿铁");                    // 保存记忆
var results = memoryBank.recall("用户喜欢什么咖啡", 3); // 语义搜索，返回最多3条
var list = memoryBank.search("咖啡", "text", 10);       // 关键词搜索
memoryBank.delete("memory-id");                         // 删除记忆（预留）
```

### 4.4 数据存储 dataStore

每个插件有独立的键值存储空间：

```javascript
dataStore.set("counter", 42);
var count = dataStore.get("counter");   // 不存在返回 null
dataStore.del("counter");
var keys = dataStore.list("prefix");    // 按前缀列出所有 key
```

### 4.5 音乐播放器 musicPlayer（需要权限）

```javascript
musicPlayer.play("/storage/.../music.mp3", "歌曲名", "歌手");
musicPlayer.pause();
musicPlayer.resume();
musicPlayer.stop();
var status = musicPlayer.getStatus();   // { state, title, artist }
```

### 4.6 控制台

```javascript
console.log("普通日志");
console.info("信息");
console.warn("警告");
console.error("错误");
```

输出到 Android Logcat，Tag 为 `PluginSandbox`。

### 4.7 编码工具

```javascript
var encoded = btoa("hello");           // Base64 编码
var decoded = atob(encoded);            // Base64 解码

var encoder = new TextEncoder();
var bytes = encoder.encode("你好");     // Uint8Array

var decoder = new TextDecoder();
var text = decoder.decode(bytes);       // "你好"
```

---

## 五、promptTemplate（让 AI 知道你的插件）

在 `manifest.json` 中提供 `promptTemplate`，安装后橘瓣会自动将其注入到 AI 的系统提示词中：

```json
{
  "promptTemplate": "## 天气查询插件\n你可以使用 `get_weather` 工具查询指定城市的天气。参数：city（城市名，必填）。"
}
```

这样 AI 就能在对话中主动调用你的插件工具，无需用户在系统提示词里手动描述。

---

## 六、打包与安装

### 6.1 打包

将 `manifest.json` 和 `main.js`（以及 WebView 用到的 HTML/CSS/JS 等资源）压缩为 ZIP：

```bash
zip -r my-plugin.zip manifest.json main.js
```

### 6.2 安装

1. 打开橘瓣 → 设置 → 插件管理
2. 点击「导入插件」
3. 选择 ZIP 文件
4. 确认 manifest 信息 → 点击「安装」

安装后橘瓣会：
- 验证 manifest.json 格式
- 校验入口文件存在
- 计算并保存 SHA-256 完整性校验和
- 加载工具到 AI 工具列表

### 6.3 网络白名单

`manifest.allowedHosts` 控制插件能访问哪些域名：

```json
{
  "allowedHosts": ["api.openweathermap.org", "wttr.in"]
}
```

- 空数组 `[]` = **禁止所有网络请求**
- `["*"]` = 允许所有域名（不推荐，仅用于开发调试）
- 安装时会向用户展示你声明的域名列表

---

## 七、WebView 插件

如果你的插件需要复杂的自定义界面（如图表、富文本编辑器、游戏等），可以使用 **WebView** 方案。

### 7.1 声明方式

在 `manifest.json` 中声明 `customPageWebView`：

```json
{
  "customPageWebView": {
    "entry": "ui/index.html"
  }
}
```

插件目录结构：

```
my-plugin/
├── manifest.json
├── main.js
└── ui/
    ├── index.html
    ├── style.css
    └── app.js
```

### 7.2 WebView ↔ 原生 Bridge

WebView 中的 JavaScript 可以通过 `Bridge` 对象与橘瓣原生交互：

```javascript
// 调用插件 main.js 中导出的函数
Bridge.callJSFunction("myTool", JSON.stringify({ key: "value" }));

// 调用 AI 生成文本（需要 ai_chat 权限）
Bridge.callAI("你好，请介绍一下自己");

// 获取插件配置
var config = JSON.parse(Bridge.getConfig());

// 读取 PluginDataStore
var value = Bridge.dataStoreGet("myKey");
Bridge.dataStoreSet("myKey", "hello");

// 调用生物识别验证
Bridge.verifyFingerprint();

// 控制音乐播放
Bridge.musicPlayerPlay("/path/to/music.mp3", "歌名", "歌手");

// 震动反馈
Bridge.vibrate(100);

// 获取设备信息
var info = JSON.parse(Bridge.getDeviceInfo());
```

### 7.3 WebView 中的事件

WebView 页面可以通过 `hookConfigs` 声明事件绑定：

```json
{
  "hookConfigs": {
    "onPageTurn": {
      "action": "call_js_function",
      "function": "onPageChanged",
      "autoTrigger": true
    },
    "onAnnotationAdded": {
      "action": "call_ai",
      "promptTemplate": "用户在《{book}》第{chapter}章写了一条批注：\n引用：「{quote}」\n心得：{note}",
      "autoTrigger": true
    }
  }
}
```

### 7.4 内置页面标识

如果不想用 WebView，也可以使用橘瓣内置的页面：

```json
{
  "customPage": "memory_bank"
}
```

目前支持的内置页面：`memory_bank`（记忆库管理）。

---

## 八、声明式 UI（进阶）

除了 WebView，插件还可以通过 `manifest.json` 的 `"ui"` 字段声明原生 Compose 界面，无需编写 HTML。

### 7.1 基础结构

```json
{
  "ui": {
    "title": "插件管理页",
    "components": [
      { "type": "stats", "items": [...] },
      { "type": "button_row", "buttons": [...] },
      { "type": "card_grid", "queryName": "items", ... }
    ],
    "queries": {
      "items": { "type": "dataStore_list", "prefix": "item:" }
    },
    "actions": {
      "create": { "type": "dataStore_set", "keyField": "id" },
      "delete": { "type": "dataStore_delete" }
    }
  }
}
```

### 7.2 支持的组件

| 组件 | 说明 |
|------|------|
| `stats` | 统计数字卡片 |
| `search_bar` | 搜索输入框 |
| `filter_bar` | 过滤标签 |
| `card_grid` | 卡片网格（支持图片、标题、副标题、标签、删除） |
| `card_list` | 卡片列表 |
| `button_row` | 按钮行 |
| `dialog_form` | 弹窗表单（新增/编辑数据） |
| `section` | 分组区域 |
| `text` | 文本段落 |
| `empty_state` | 空状态提示 |

### 7.3 表单字段类型

`string`、`integer`、`boolean`、`select`、`image`、`file`、`multiline`

---

## 八、常见问题

**Q1: AI 调用了我的工具但返回了 undefined？**

检查 `exports.xxx` 是否正确导出，且函数名与 `manifest.json` 中的 `tools[].name` 完全一致。

**Q2: fetch 返回 "Host not allowed"？**

在 `manifest.allowedHosts` 中添加目标域名，然后重新导入插件。

**Q3: 插件安装后提示 "完整性校验失败"？**

不要手动修改 `Orangechat/plugins/` 目录下的插件文件。如需修改，重新打包 ZIP 导入。

**Q4: 如何调试插件？**

使用 `console.log()` 输出日志，在 Android Studio 的 Logcat 中筛选 `PluginSandbox` Tag 查看。

**Q5: 可以让插件调用 AI 吗？**

需要声明 `"permissions": ["ai_chat"]`，然后通过 Bridge 调用（目前仅支持声明式 UI 和 WebView 中的 `call_ai` action）。

---

## 九、示例插件参考

项目内置了官方示例插件，可直接参考：

| 插件 | 路径 | 亮点 |
|------|------|------|
| 天气查询 | `plugins/example/weather/` | 基础工具 + fetch 网络请求 |
