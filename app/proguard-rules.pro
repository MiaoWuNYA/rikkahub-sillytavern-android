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

# ---- SnakeYAML ----
#
# SnakeYAML 的 MethodProperty 引用了 java.beans.*（JavaBeans 内省），
# 而 Android 运行时根本没有这个包。我们只用 Yaml().load() 解析 Map/List，
# 走的是 SafeConstructor，从不触发 Bean 内省路径。
#
# R8 是静态分析，看到 java.beans 就报 Missing class 并中断整个 release 构建。
# 这里声明「这些缺失类不影响运行」，让 R8 放心删掉那条走不到的分支。
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn org.yaml.snakeyaml.**

# ── JNI 符号的保留规则 ────────────────────────────────────────────────
#
# 背景：native 侧的符号名是「类的全限定名 + 方法名」拼出来的，例如
#   Java_com_soreverse_mcp_nativecore_HdGuard_hdKdfFactor
# R8 一旦重命名类或方法，这个符号就对不上，运行时报 UnsatisfiedLinkError。
#
# 这类问题的特征是：debug 包正常、release 包静默失效，
# 而且往往表现为某个功能「取不到数据」而不是明显崩溃，
# 与上面前端桥那段是同一类陷阱。
#
# 此前只有 RizinBridge 的 external 方法侥幸没中招——它们恰好都是 public，
# 而 R8 默认保留 public 成员名。依赖这种巧合不可靠，这里显式声明。

# 保留 native 方法所在类的类名与方法名
-keepclasseswithmembernames,includedescriptorclasses class com.soreverse.mcp.nativecore.** {
    native <methods>;
}
-keep class com.soreverse.mcp.nativecore.HdGuard { *; }
-keep class com.soreverse.mcp.nativecore.RizinNativeEngine { *; }

# 兼容：RizinBridge 是 RizinNativeEngine 的 typealias，
# 旧调用点可能仍以该名字引用
-keep class com.soreverse.mcp.nativecore.RizinBridge { *; }

# 插件解密入口：被插件加载器经反射/间接路径调用，R8 看不到调用方
-keep class me.rerere.rikkahub.plugin.crypto.PluginCrypto { *; }


# ---- kotlinx.serialization 多态 ----
#
# 背景：LocalToolOption 是 @Serializable 的 sealed class，每个子类用
# @SerialName("show_image") 之类挂进序列化模块。
#
# 这套注册对 R8 是「不可见」的：它发生在编译器生成的多态序列化器里，
# 按 Class 对象查表，没有任何直接的 Kotlin 代码引用。于是 R8 认定
# 新增的子类不可达并删掉它们，运行时序列化立刻抛
#   Serializer for subclass 'LocalToolOption$ShowImage' is not found
#
# 表现：debug 包正常，release 包一打开助手设置页就闪退——因为只有
# release 才跑 R8。和本文件上面的 CardHostBridge 是同一类问题：
# 反射/生成代码构成的数据通路，R8 看不见。
#
# 实测确认（R8 的 usage.txt 里明确列出被删的两个）：
#   me.rerere.rikkahub.data.ai.tools.LocalToolOption$CodeCheck:
#   me.rerere.rikkahub.data.ai.tools.LocalToolOption$ShowImage:
#
# 之前的写法 -keepclassmembers 只保住成员，保不住类本身。
# 必须用 -keep 并让规则作用于「sealed 类的所有子类」。

# 保留序列化注解（@SerialName 的值是运行时查表用的键）
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, InnerClasses, Signature, *Annotation*

# 只保住「类不被删」和「序列化入口」，不保成员体——保成员名会让 APK
# 从 107 MB 涨到 114 MB，没必要。
#
# 注意不能写 allowshrinking：那等于告诉 R8「没被引用就删掉」，而
# sealed 子类恰恰是「看起来没被引用」的那种（只出现在多态序列化器的
# 查表里）。加了它之后 ShowImage / CodeCheck 立刻又被删了。
-if @kotlinx.serialization.Serializable class me.rerere.**
-keep class <1>
-keepclassmembers class <1> {
    *** Companion;
    *** INSTANCE;
    static **$* *;
    kotlinx.serialization.KSerializer serializer(...);
}

# 编译器为每个 @Serializable 类型生成的 $serializer
-keep class me.rerere.**$$serializer { *; }

# sealed 继承体系：子类只被多态序列化器按 Class 查表引用，
# R8 看不到这层通路，会判定不可达。显式保住整条继承链。
-keep class me.rerere.rikkahub.data.ai.tools.LocalToolOption { *; }
-keep class me.rerere.rikkahub.data.ai.tools.LocalToolOption$* { *; }

# kotlinx.serialization 自身的 Companion 与 serializer 工厂
-keepclasseswithmembers class kotlinx.serialization.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.** {
    *** Companion;
}
-dontwarn kotlinx.serialization.**
