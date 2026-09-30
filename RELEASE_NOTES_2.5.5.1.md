# 华灯 2.5.5.1

> ~~历时一百万年~~ 修复了绝大多数前端酒馆卡奇怪的 bug，建议更新。

## 中文

**1. 酒馆主题全量无损导入**

气泡、圆角、小图标、头像框都能正常映射了。之前气泡背景大面积失效，是 CSS 变量名和官方对不上；现在按官方变量表取值。背景图、字体、代码块、引用块也一起跟主题走，气泡边框可以取消。

**2. 前端卡完美上下翻动，无需进入二级界面**

以前卡片被限制在 520dp 高度里，卡内要自己滚、外层消息列表也要滚，两个滚动体抢手势，所以「弹一下、翻一点、又翻不动」。官方酒馆本来就不限制高度——卡片整条内联进消息、跟着页面滚。改成一样之后，冲突直接消失。

**3. 纯前端卡支持前端复写开场白**

卡内的生成、写回消息、切开局、变量读写都能跑了。之前 release 包里这套桥被 R8 整个裁掉（debug 正常、release 报「宿主未注入 generate 接口」），现在补了保留规则，并逐次构建验证。

**4. 状态栏 / 世界书显示单独适配**

状态栏是 MVU 变量的只读视图，并且按楼显示——旧楼显示旧状态、新楼显示新状态。为此变量改成挂在每条消息上持久化，卡片被列表回收重组后状态栏也不会空白或串楼。

**5. 卡片识别不再误伤普通消息**

以前围栏 HTML 和普通 HTML 片段会被误当成卡片，把正常消息渲染成源码；现在只有真正的完整网页才走卡片路径。

---

## English

**1. Full-fidelity SillyTavern theme import**

Bubbles, corner radii, icons and avatar frames now map correctly. Bubble backgrounds were failing across the board because the CSS variable names didn't match the official runtime — the full official variable table is now used. Background images, fonts, code blocks and blockquotes follow the theme too, and bubble borders can be turned off.

**2. Cards scroll perfectly inline — no second screen needed**

Cards used to be capped at 520dp, which meant the card scrolled internally *and* the message list scrolled — two scroll containers fighting over the same gesture ("it bounces, scrolls a bit, then jams"). Official SillyTavern never caps card height; the card inlines into the message and scrolls with the page. Matching that removes the conflict entirely.

**3. Pure frontend cards can rewrite the opening message**

Generation, writing back to the message, switching openings, and variable read/write all work now. In release builds this whole bridge was previously stripped by R8 — debug worked, release reported "host did not inject a generate interface". Keep rules added, verified on every build.

**4. Status bar / world book rendering adapted separately**

The status bar is a read-only view of MVU variables, rendered per message — old messages show old state, new messages show new state. Variables are now persisted on each message, so recycling the card no longer blanks the status bar or bleeds state across messages.

**5. Card detection no longer misfires**

Fenced and plain HTML fragments used to be mistaken for cards, rendering normal messages as raw source. Only genuine full HTML documents take the card path now.

---

## 验证 / Verification

- 584 单元测试全绿 / 584 unit tests passing
- release DEX 逐字符串验证宿主 API 全部保留 / all host API strings verified present in release DEX
- 版本 / version: `2.5.5.1` (code 231)
