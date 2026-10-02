# 华灯 2.5.6

修崩溃、修掉帧，合入上游 2.5.6。

## 中文

**修复**

- 「主动消息」开启后必现崩溃：后台线程初始化 ChatService 触发 `addObserver must be called on the main thread`。生命周期注册统一走主线程，机器人服务同类隐患一并覆盖
- 长会话回复卡顿：消息正则替换每次重组都重跑，改为按文本缓存
- 应用内更新把人导向旧包：`update.json` 的下载链接一直写死，现已与版本号同步
- 深度思考无法关闭、酒馆模式残留近期对话、主题样式无法重置

**新增**

- 酒馆模式：请求只保留角色卡与世界书
- 插件密钥改为签名证书派生，调试入口遮蔽插件内容

**上游 2.5.6**

- 提供商高级设置，支持自定义请求头
- 复制助手时一并复制记忆
- Gemini Interactions API；注册 Claude Opus/Sonnet 5.5、Gemini 4
- MCP：OAuth 回调改 localhost 绕开 WAF 403；工具 inputSchema 内联 `$ref` 修 400
- fork 标题不再叠成 `xxx(1)(1)`
- 对话导出可选不含思考过程；模型列表按供应商折叠
- 新增 `chart_display` 工具；图片生成快捷方式

---

## English

Crash and frame-drop fixes, plus upstream 2.5.6.

**Fixes**

- Proactive messages crashed on every trigger: a background thread initialized `ChatService`, hitting `addObserver must be called on the main thread`. Lifecycle registration now always goes through the main thread; the same latent bug in the bot services is covered
- Frame drops in long conversations: regex replacement re-ran on every recomposition, now cached per text
- In-app update pointed at an old package: `update.json` had a hardcoded download link, now kept in sync with the version
- Reasoning couldn't be turned off, tavern mode leaked recent chat, theme styles wouldn't reset

**New**

- Tavern mode: requests carry only the character card and world book
- Plugin keys derived from the signing certificate; debug entry hides plugin content

**Upstream 2.5.6**

- Provider advanced settings with custom request headers
- Duplicating an assistant can copy its memories
- Gemini Interactions API; Claude Opus/Sonnet 5.5 and Gemini 4 registered
- MCP: OAuth callback on localhost to dodge WAF 403s; `$ref` inlined in tool input schemas to fix 400s
- Fork titles stop stacking into `xxx(1)(1)`
- Export without reasoning; model list collapses by provider
- New `chart_display` tool; image-generation shortcut
