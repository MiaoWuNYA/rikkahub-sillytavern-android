# 前端角色卡宿主桥的保留规则
#
# 背景：酒馆前端卡靠 JS 调用宿主注入的对象（window.rikkaHostGen.generate 等）
# 来生成开场白。这条链路对 Kotlin 来说是"死代码"——没有任何 Kotlin 代码
# 调用 generate/poll/toast，全部经由 WebView 的反射桥进入。
#
# R8 看不到这条反射路径，会做两件破坏性的事：
#   1. 把 @JavascriptInterface 方法改名（JS 侧按原名找不到，直接 TypeError）；
#   2. 把注入用的 JS 常量整段消除（CardHostShimKt 被判定不可达）。
#
# 后果在 debug 包里看不出来，只有 release 包才炸，属于最难发现的一类问题：
# 卡会显示「宿主未注入 generate 接口，无法生成」，仿佛我们没写过桥。

# 保留所有 @JavascriptInterface 方法的原名（Android 内置规则通常已覆盖
# 方法本身，这里显式写出以防内置规则变化或被其它规则覆盖）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留宿主桥类：方法名即 JS 侧的调用名，一旦混淆 JS 就调不到
-keep class me.rerere.rikkahub.ui.components.richtext.CardHostBridge { *; }

# 保留注入用的 JS 文本。cardHostShim() 是普通 Kotlin 函数，
# 没有注解也没有 Kotlin 侧调用者，R8 会连类带字符串一起删掉。
-keep class me.rerere.rikkahub.ui.components.richtext.CardHostShimKt { *; }

# 高度上报桥同理：JS 调用 window.rikkaHost.reportHeight
-keep class me.rerere.rikkahub.ui.components.richtext.HtmlWebViewBlockKt$HeightBridge { *; }

# WebView 相关的回调依赖反射调用，保留名字便于排查线上问题
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}
