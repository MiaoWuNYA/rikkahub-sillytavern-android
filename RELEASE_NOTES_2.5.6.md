# 华灯 2.5.6

合入上游 2.5.6，修掉「主动消息必现崩溃」，顺手治了长会话的掉帧。

## 中文

**1. 主动消息不再必现崩溃**

开「主动消息」之后，一到触发点就闪退，反复重建服务还是崩。根因是闹钟/WorkManager 在后台线程拉起服务时顺带初始化了 ChatService，而它要往进程生命周期注册观察者——Android 强制要求这步在主线程做，违规直接抛 `addObserver must be called on the main thread`。

修复分两层：ChatService 内部把注册/注销统一派发到主线程，不再看调用方脸色；主动消息服务改成惰性取依赖，首次获取同步投到主线程，超时兜底不会卡死前台服务。QQ 机器人、微信机器人两处同源隐患一并覆盖。

**2. 长会话回复时掉帧明显缓解**

回复生成时主线程被 Choreographer 记了一堆 Skipped frames，长会话下几乎没法操作。定位到消息渲染里三处正则替换——它要遍历助手的全部正则逐条做字符串替换，而原先每次重组都会重跑一遍，哪怕文本一个字都没变。

现在按「原文 + 作用域 + 深度 + 正则表」记忆化：稳定消息重组时直接命中缓存；正在流式增长的那条不缓存，避免为不断变化的文本做无用功。语义完全不变。

**3. 合入上游 2.5.6**

- **提供商高级设置**：自定义请求头终于有了正经入口，按供应商配置
- **复制助手带记忆**：复制助手时可以把记忆一起带过去
- **Gemini Interactions API** 支持；注册 Claude Opus/Sonnet 5.5、Gemini 4（推理参数走 thinkingLevel）
- **MCP 修复**：OAuth 回调改 localhost，绕开动态客户端注册被 WAF 拦 403；工具 inputSchema 的 `$ref` 内联展开，修 `$defs` 丢失导致的请求 400
- **fork 标题不再叠罗汉**：不再出现 `xxx(1)(1)`，已有序号自动递增
- **对话导出**可选不含思考过程；**模型列表**支持按供应商折叠且状态持久化
- 新增 `chart_display` 本地工具，聊天里直接画折线/柱状/散点图
- 图片生成快捷方式；请求日志长按闪退、生成标题找不到快速模型的提示等修复

**4. 合并取舍**

本地功能一个没少：酒馆/世界书/插件管理/双人空间/生命中心/语音视频通话等路由与界面全部保留。上游把 Skills 页挪进了子包，本地没跟，导入路径保持原样以避免破坏已有功能。顺带补上 `SettingSecurity` 缺失的 `@Serializable`——这是本地既有的隐患，缺了它导航序列化会在运行时炸。

---

## English

Merged upstream 2.5.6, fixed the guaranteed crash on proactive messages, and took a pass at long-conversation frame drops.

**1. Proactive messages no longer crash on every trigger**

Turning on proactive messages meant a crash the moment one fired — and again after the service rebuilt itself. The alarm/WorkManager path starts the service on a background thread, and that path was eagerly resolving `ChatService`, whose constructor registers a process-lifecycle observer. Android requires that call on the main thread, so it threw `addObserver must be called on the main thread`.

Two layers now: `ChatService` dispatches its own register/unregister to the main thread regardless of caller, and the proactive-message service resolves its dependency lazily, hopping to the main thread on first access with a timeout so the foreground service can't wedge. The same latent bug in the QQ and WeChat bot services is covered by the same fix.

**2. Long conversations drop far fewer frames while generating**

Reply generation was racking up Choreographer skipped frames, leaving long chats nearly unusable. The culprit was three regex-replacement calls in message rendering: they walk every regex on the assistant and run a string replace each, and they were re-running on every recomposition even when the text hadn't changed by a character.

Results are now memoized on (text, scope, depth, regex table). Stable messages hit the cache; the message currently streaming isn't cached, so nothing is wasted on text that keeps changing. Behaviour is identical.

**3. Upstream 2.5.6 merged**

- **Provider advanced settings** with custom request headers, configured per provider
- **Duplicating an assistant** can carry its memories over
- **Gemini Interactions API** support; Claude Opus/Sonnet 5.5 and Gemini 4 registered (reasoning via `thinkingLevel`)
- **MCP fixes**: OAuth callback moved to localhost to dodge WAF 403s on dynamic client registration, and `$ref` inlining for tool input schemas to fix the missing-`$defs` 400
- **Fork titles stop stacking**: no more `xxx(1)(1)` — existing suffixes increment
- **Export without reasoning**; **model list collapses by provider** with persisted state
- New `chart_display` local tool for line, bar and scatter charts inside chat
- Image-generation shortcut, plus fixes for the request-log long-press crash and the missing-fast-model title-generation prompt

**4. Merge choices**

Nothing local was dropped: tavern, world books, plugin management, couple space, life hub, voice and video calls all keep their routes and screens. Upstream moved the Skills pages into a subpackage and this fork deliberately didn't follow, so the original import paths stay intact. Also repaired a missing `@Serializable` on `SettingSecurity` — a pre-existing local defect that would have crashed navigation serialization at runtime.
