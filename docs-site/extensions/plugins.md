---
title: 插件：QuickJS 沙箱扩展
---

# 插件：QuickJS 沙箱扩展

插件 = ZIP 包（`manifest.json` + `main.js`），给 AI 添加**能做事的工具**：记录饮食、查教务、查模型资料都是插件。QuickJS 沙箱执行，域名白名单与完整性校验兜底。

## 在哪里管理

**设置 → 插件管理**：导入、启用 / 停用、文件夹归类、逐插件配置。

## 导入插件

1. 进入 **设置 → 插件管理**；
2. 点**导入**，选择插件 ZIP（**文件必须在 ZIP 根目录，不能有顶层文件夹**）；
3. 应用先解析 manifest 预览（名称、工具列表、申请的域名白名单），确认后落盘；
4. 启用插件，到助手工具页把插件工具勾选给对应助手。

## 内置插件

三个内置插件的源码与打包好的 ZIP 都在仓库 [docs/plugins/](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/tree/main/docs/plugins)——应用不预装，按上一节的方法自行下载 ZIP 导入即可；源码可直接参考来写自己的插件：

| 插件 | 用法示例 |
| --- | --- |
| **卡路里与蛋白质记录** | 跟 AI 说「中午吃了碗牛肉面」——热量与蛋白质自动记下；插件设置里配每日目标，详情页显示今日摄入与剩余额度 |
| **云南财经教务系统** | 问「明天有什么课」「我高数考了多少分」——账号密码填在插件设置，验证码本地 OCR 自动识别 |
| **AI 模型世界** | 问「XX 模型多少钱」「和 YY 比哪个强」——600+ 大模型档案与基准评分，本地库直查 |

## 插件能注册什么

- **AI 工具**：manifest 声明的 `tools` 自动注册（统一 `plugin_` 前缀），AI 按需调用；
- **系统提示词注入**：`systemPrompt` 直接注入；`sections` 模式每段配一个开关，支持 `file:` 外置文件与 `enc:` AES-256-GCM 加密文件；
- **详情页数据卡片**：声明 `detailCard` 后，插件详情页渲染实时状态卡片；
- **前端脚本**：声明 `frontend.scripts` 后，脚本只在**前端卡专属模式**的 WebView 里运行，接入酒馆助手同名的 `TavernHelper` / `SillyTavern` 接口（见下）；
- **配置表单**：文本 / 密码 / 开关 / 下拉 / 模型选择，配置以 `config` 注入沙箱；
- **dataStore**：每插件独立键值存储，`set` / `get` / `del` / `list`。

## 前端插件（酒馆助手脚本）

声明 `frontend.scripts` 的插件只在前端 WebView 模式生效（普通原生消息区不会执行），用于兼容 JS-Slash-Runner 那类改变聊天界面的脚本。纯前端插件可以省略 `entry`。

```json
{
  "id": "com.example.status",
  "name": "状态栏",
  "frontend": {
    "scripts": [
      { "name": "状态栏", "entry": "scripts/status.js", "enabled": true, "info": "显示 HP/MP" }
    ]
  }
}
```

脚本里可直接使用与酒馆助手同名的接口：

| 分类 | 接口 |
| --- | --- |
| 变量 | `getVariables` `replaceVariables` `insertOrAssignVariables` `getVariable` `setVariable` `deleteVariable` |
| 消息 | `getChatMessages` `setChatMessages` `createChatMessages` `deleteChatMessages` `getLastMessageId` |
| 生成 | `sendMessage` `triggerSlash` `substituteMacros` |
| 事件 | `eventOn` `eventOnce` `eventOff` `eventEmit`，常量 `tavern_events` / `iframe_events` |
| 脚本 | `getScriptId` `getScriptName` `getScriptInfo` `waitGlobalInitialized` |

`TavernHelper` 与 `SillyTavern` 互为别名。事件名与酒馆一致（`message_received`、`character_message_rendered`、`generation_started` 等）。

::: warning
前端脚本与酒馆助手一样是第三方代码，能访问聊天数据与网络。请只导入可信来源。
:::


## 沙箱能力

| API | 用途 |
| --- | --- |
| `fetch(url, options)` | 一次性请求，不保存 Cookie，域名白名单 fail-closed |
| `http.*` | 会话式请求，自带 Cookie 罐（自动保存每一跳 `Set-Cookie`）——「先拿 session 再带登录」的流程 |
| `image.decode(data)` | 图片 → RGBA 像素数组——验证码识别、取色等逐像素处理 |
| `dataStore` | 持久化键值存储 |
| `btoa` / `atob` / `TextEncoder` / `TextDecoder` | 编解码 |
| `config` | 插件配置对象 |

约束：单线程执行；工具调用 30 秒超时，详情页卡片 8 秒超时；只支持 ES5（`async`/`await` 会被预处理为同步）。

## 写一个最小插件

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

打包成 ZIP 导入即可。完整开发指南见仓库 [docs/PLUGINS_GUIDE.md](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android/blob/main/docs/PLUGINS_GUIDE.md)。

## 安全机制

- 导入两段式：先预览确认再落盘；
- 插件目录 SHA-256 完整性校验（`.integrity`），被改动后自动停用；
- Zip Slip 防护：解压时校验每个条目的 canonical path；
- `allowedHosts` 为空 = 拦截全部网络请求；
- 插件只能访问显式注入的桥接对象，拿不到宿主能力。

::: warning
插件是第三方代码，请只导入你信任的来源。
:::
