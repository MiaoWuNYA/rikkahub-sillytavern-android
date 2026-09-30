# 华灯 2.5.5 · 前端卡与主题导入大修

> ~~历时一百万年~~ 修复了绝大多数前端酒馆卡奇怪的 bug，建议更新。

本次把「酒馆主题导入」和「前端角色卡运行」两条链路从头到尾重做了一遍。核心变化：**卡片不再被关在 520dp 的小窗口里**，而是像官方酒馆那样整条内联进消息、随页面滚动。

---

## 中文

### 1. 酒馆主题全量无损导入

主题不再"能读进去但到处不对"。气泡、圆角、小图标、头像框都做了真实映射：

- **气泡背景彻底修好**。此前 534 个主题里气泡背景大面积失效，根因是抄来的 CSS 变量名与官方运行时不符，`var()` 解析失败后整条声明被丢弃。现在补齐官方变量表，按官方优先级取值。
- **背景图解析补上 `@import`**，并从 CSS 直接提取聊天背景（纯色 / 渐变 / 图片三种都能取到）。
- **四类图标映射补齐**（侧栏、发送区、模型选择、推理选择），圆角与上限按主题实际值走。
- **内层元素跟着主题走**：代码块、引用块、嵌套气泡不再是一套写死的样式。
- **字体跟随主题**：解析 `@import` 与 `body { font-family }`，拿到本地 ttf/otf 才会应用。
- 支持**取消气泡边框**；113 个主题刻意设置的透明气泡不再被渲染层强行加不透明度。

### 2. 前端卡完整内联显示，无需进入二级界面

这是本次改动最大的一处，也是"划不动"问题的**真正根因**。

之前的做法是给卡片加 520dp 高度上限，超出部分让卡内 WebView 自己滚。这制造了**两个滚动体**：卡内要滚、外层消息列表也要滚，手势归属只能靠 `requestDisallowInterceptTouchEvent` 仲裁——而 Compose 的 LazyColumn 走指针消费、不走原生视图拦截链，**这个仲裁天然不可靠**。表现就是「弹一下、翻一点、然后又翻不动」。前几版都在优化仲裁逻辑，那是在错误的层面上修。

**官方酒馆压根不设卡片高度上限**：卡整条内联进消息 DOM，随页面滚过去，只有一个滚动体，冲突从构造上就不存在。现在照官方做，冲突消失，不靠仲裁。

全屏查看也从跳转裸 WebView 改成就地弹窗，并注册**同一座宿主桥**——此前展开态会报「宿主未注入 generate 接口」，因为全屏页一个接口都没挂。

### 3. 纯前端卡支持前端复写开场白

卡的「入局簿」式流程现在能真正跑完：

- `window.generate(request)` —— 卡自己拼好 prompt，宿主送模型，支持流式回调
- `setChatMessages(...)` —— 把开局正文写回消息 0
- `Mvu.getMvuData` / `replaceMvuData` —— **MVU 变量系统**，从世界书 `[initvar]` 初始化整棵 `stat_data`
- `getChatMessages` / `setChatMessage` —— 切开局（卡的「命牌问卜」按钮）
- `getVariables` / `updateVariablesWith` —— chat 作用域变量
- `eventOn` / `iframe_events` / `toastr` —— 事件与提示

### 4. 一批只在 release 包才炸的问题

**这条特别值得说**：宿主桥在 debug 包里完全正常，release 包里**整段代码被 R8 消除**——因为 `@JavascriptInterface` 只能靠反射到达，R8 看不到这条路径。用户装 release 后看到的是「宿主未注入 generate 接口」，仿佛我们没写过桥。

已补 ProGuard 保留规则，并加了 DEX 级验证：构建后逐字符串确认 17 项宿主 API 全部在包里。同时修掉 SnakeYAML 引用 Android 不存在的 `java.beans.*` 导致 release 构建直接失败的问题。

### 5. 卡片识别不再误伤普通消息

围栏 HTML 与裸 HTML 片段此前会被误判成卡片，把正常消息渲染成一堆源码。现在只在确实是完整网页文档时才走卡片路径。

---

## English

### 1. Full-fidelity SillyTavern theme import

Themes are no longer "readable but wrong everywhere". Real mapping for bubbles, corner radii, icons, and avatar frames:

- **Bubble backgrounds actually work now.** Across the 534-theme corpus, bubble backgrounds were failing en masse — the imported CSS variable names didn't match the official runtime, so `var()` resolution failed and the whole declaration was dropped. The full official variable table is now in place with official precedence.
- **Background image parsing handles `@import`**, and chat backgrounds are extracted straight from CSS (solid / gradient / image).
- **Four icon families mapped** (drawer, composer, model picker, reasoning picker); corner radii follow the theme.
- **Inner elements follow the theme**: code blocks, blockquotes, nested bubbles are no longer hard-coded.
- **Fonts follow the theme**: `@import` and `body { font-family }` are resolved; local ttf/otf are applied.
- **Bubble borders can be disabled**; the 113 themes that deliberately set transparent bubbles no longer get an opacity forced over them.

### 2. Full inline card rendering — no second screen needed

The largest change here, and the **actual root cause** of the "can't scroll" problem.

The previous approach capped card height at 520dp and let the WebView scroll internally. That created **two scroll containers**: the card scrolled, and the outer message list scrolled. Ownership could only be arbitrated via `requestDisallowInterceptTouchEvent` — but Compose's LazyColumn consumes pointers rather than going through the native view interception chain, so **the arbitration is unreliable by construction**. The symptom: "it bounces, scrolls a bit, then jams". Earlier attempts tuned the arbitration logic; that was fixing the wrong layer.

**Official SillyTavern never caps card height** — the card is inlined into the message DOM and scrolls with the page, leaving exactly one scroll container. Adopting that removes the conflict entirely rather than arbitrating it.

Fullscreen preview also changed from navigating to a bare WebView into an in-place dialog that registers **the same host bridge** — previously the expanded view reported "host did not inject a generate interface" because that page registered no interfaces at all.

### 3. Pure frontend cards can now rewrite the opening message

The card's "character intake" flow runs end to end:

- `window.generate(request)` — the card builds its own prompt; the host runs it with streaming support
- `setChatMessages(...)` — writes the opening prose back to message 0
- `Mvu.getMvuData` / `replaceMvuData` — **MVU variable system**, seeded from the world book's `[initvar]` entry
- `getChatMessages` / `setChatMessage` — swipes (the card's "draw a fate card" button)
- `getVariables` / `updateVariablesWith` — chat-scoped variables
- `eventOn` / `iframe_events` / `toastr` — events and toasts

### 4. Release-only failures

**Worth calling out**: the host bridge worked perfectly in debug builds and was **stripped entirely by R8 in release builds** — `@JavascriptInterface` is only reachable via reflection, and R8 cannot see that path. Users on release saw "host did not inject a generate interface", as if the bridge had never been written.

ProGuard keep rules added, plus DEX-level verification: every build is checked string-by-string to confirm all 17 host APIs survived. Also fixed SnakeYAML referencing `java.beans.*` (absent on Android), which was aborting release builds outright.

### 5. Card detection no longer misfires

Fenced and bare HTML fragments were being mistaken for cards, rendering normal messages as raw source. Only genuine full HTML documents take the card path now.

---

## 验证 / Verification

- 579 单元测试全绿 / 579 unit tests passing
- release DEX 逐字符串验证 17 项宿主 API 全部保留 / all 17 host API strings verified present in release DEX
- 签名 / signature: `CN=HuaDeng`, SHA-256 `f25c3c0987033896e2c23e3c4aef454a12b9aa95aa938c601dfeb5f025230a4b`
