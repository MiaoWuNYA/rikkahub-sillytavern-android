import com.android.build.api.dsl.Packaging
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties

// Firebase 遥测默认关闭（移植自 Rikkahub-Revised）：
// 仅当以 -Prikkahub.enableFirebase=true 构建时才启用 Google 服务与 Crashlytics
val enableFirebase = providers.gradleProperty("rikkahub.enableFirebase")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

/**
 * libhdguard.so 内置的派生因子。
 *
 * 它与由签名口令推导出的 kdfFactor 必须一致——native 侧按这个值分段异或存放。
 * 不一致说明签名口令变了或 hdguard.c 未同步，此时构建立即失败，
 * 而不是产出一个「装上去才发现解不开插件」的包。
 *
 * 该常量只参与构建期校验，不会写入 APK。
 */
val HD_GUARD_EXPECTED_FACTOR =
    "cc655f7608689bb5fe6003d206f8e38731c2cc0cbade823a83194c58b46efb12"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.baselineprofile)
    id("com.chaquo.python")
}

if (enableFirebase) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}
// Python 引擎配置 — Chaquopy 新 DSL
chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            // ── 基础 IO 与网络 ──────────────────────────────────
            install("requests")
            install("beautifulsoup4")
            install("lxml")            // bs4 的解析后端，比纯 Python 快数倍且容错更好
            install("chardet")         // 编码探测：中文乱码/GBK 文本的救星

            // ── 文档读写 ────────────────────────────────────────
            // convert.py 一直在用 docx / pptx / fpdf，但这三个包从未安装过——
            // docx 双向转换、pptx→txt、txt/md→PDF 三条路径运行时必然 ImportError。
            // 注释里写的「只保留实际被引用的包」删过头了。
            install("python-docx")
            install("python-pptx")
            install("fpdf2")           // 文本/图片 → PDF，带 Chaquopy 预编译 freetype
            install("pypdf")
            install("pdfminer.six")    // 补 pypdf 的版式与文本流提取短板
            install("openpyxl")
            install("xlsxwriter")      // 写出带格式的 xlsx（openpyxl 写样式更啰嗦）
            install("markdown")
            install("markdownify")
            install("tabulate")

            // ── 数值与表格：日常最高频的通用能力 ────────────────
            install("numpy")           // 数值计算地基
            install("pandas")          // CSV/Excel 统计分析

            // ── 图片处理 ────────────────────────────────────────
            install("pillow")          // 缩放/裁剪/旋转/格式转换/加水印

            // ── 中文处理 ────────────────────────────────────────
            install("pypinyin")        // 拼音、注音、按拼音排序
            install("opencc-python-reimplemented")   // 繁简转换

            // ── 文本与时间 ──────────────────────────────────────
            install("regex")           // 变长后顾/反向引用，标准 re 做不到
            install("dateparser")      // 自然语言日期："下周三下午三点"
            install("pytz")

            // ── 补齐：代码在用但从未安装的包 ────────────────────
            // convert.py 的 epub 分支 import ebooklib，但清单里没有，
            // 运行到那条路径必然 ImportError，而工具描述还向模型宣传
            // 支持 epub。属于宣传与能力脱节。
            //
            // 图表不再用 matplotlib：上游的 chart_display 是原生 Compose
            // 渲染，直接显示在聊天里、可交互、还省 token；matplotlib 只能
            // 出一张静态 PNG，8.1 MB 换来的能力与它高度重叠。
            install("ebooklib")
            install("pyyaml")          // convert.py 的 yaml 分支用它做严格解析
            install("pypdfium2")       // 自带 android wheel 的 PDFium，PDF 页面渲染

            // ── 符号数学与代码解析 ──────────────────────────────
            // 纯 Python 无原生依赖，且都是「聊天里真的会用到」的：
            // 解方程/求导/化简，以及给代码做词法分析。
            install("sympy")           // 符号数学：解方程、微积分、化简、矩阵
            install("pygments")        // 500+ 语言的词法分析器（可做代码结构解析）

            // ── 中文与结构化数据的小件 ──────────────────────────
            install("cn2an")           // 中文数字 ↔ 阿拉伯数字："三千零二十" → 3020
            install("zhon")            // 中文标点/字符常量表，写中文正则时省事
            install("jsonschema")      // JSON Schema 校验，约束结构化输出
            install("python-frontmatter")  // YAML frontmatter 解析（卡片/笔记头部）

            // ── 老式 .xls 支持 ──────────────────────────────────
            // openpyxl 只认 .xlsx；用户手上仍有大量 .xls，这是真实缺口。
            install("xlrd")
            install("xlwt")
            install("xlutils")

            // ── Word 模板渲染 ───────────────────────────────────
            install("docxtpl")         // Jinja2 语法填充 docx 模板
            // ── 代码检查（纯 Python，无原生依赖）──────────────
            install("pyflakes")        // Python 静态检查：未定义名、未用 import、可疑写法
            install("qrcode")          // 生成二维码（纯 Python）
            install("networkx")        // 图算法：最短路、连通分量、拓扑排序
            install("python-barcode")  // 条形码

            // ── 小体积高频工具（合计约 1.7 MB，全部是纯 Python）──────
            install("python-slugify")  // 中文/任意文本 → URL 友好的 slug
            install("unidecode")       // Unicode → ASCII 转写（去音标、统一形近字符）
            install("inflect")         // 英文单复数、序数词、a/an 选择
            install("humanize")        // 人类可读的量与时间（1024 → 1.0 KB）
            install("croniter")        // cron 表达式解析：下次触发时间
            install("isodate")         // ISO 8601 时间解析（含时长）
            install("semver")          // 语义化版本比较
            install("xmltodict")       // XML ↔ dict 互转
            install("deepdiff")        // 结构化数据差异（嵌套 dict/list 递归比对）
            install("jsonpath-ng")     // JSONPath 查询
            install("emoji")           // 表情符号的识别与转换
            install("ftfy")            // 修复乱码文本（mojibake、错误编码）
            install("parse")           // 自然语言时间解析（"下周三下午三点"）
            install("pyotp")           // TOTP/HOTP 两步验证码
            install("python-dotenv")   // 读取 .env 配置
            install("tomli")           // TOML 解析（读 pyproject.toml 等）
            install("base58")          // Base58 编解码（加密货币地址格式）
            install("shortuuid")       // 短随机 ID 生成
            install("nanoid")          // 短唯一 ID（URL 友好）
            install("ulid-py")         // 可排序的唯一 ID
        }

        // ── 让 docx / pptx 的模板文件落到磁盘 ──
        //
        // Chaquopy 默认把 Python 模块直接从 APK 加载，源码不会以独立文件
        // 存在。这对纯代码包没问题，但 python-docx / python-pptx 需要
        // **读自己包内的模板 XML**，用的是这种拼法：
        //
        //     os.path.join(os.path.split(__file__)[0], "..", "templates", "x.xml")
        //
        // 模块从 APK 加载时，__file__ 指向 imy 内部，而 templates/*.xml
        // 在那种布局下取不到，于是：
        //   - 加页眉/页脚 → PackageNotFoundError / FileNotFoundError
        //   - 写 PPT 备注、形状树 → 同样失败
        //
        // extractPackages 是官方给出的正解：被点名的包会在首次 import 时
        // 解压成真实文件，__file__ 于是指向磁盘，模板路径便能正常解析。
        //
        // 只点这两个包。它们体量小（docx+pptx 约 3 MB），解压开销可忽略；
        // numpy/pandas 那类大包不能开，否则首次导入要等好几秒。
        // inflect 必须在这里：它依赖 typeguard，而 typeguard 走
        // inspect.getsource(sys.modules[f.__module__]) 读被装饰函数所在
        // 模块的**源码文本**做插桩。Chaquopy 默认把纯 Python 编译成 .pyc
        // 装进 APK 内的 zip，源码读不到，import inflect 直接
        //   OSError: could not get source code
        //
        // 实测对照：只留 .pyc → OSError；源码可见 → plural("box") 返回
        // "boxes"、number_to_words(1234) 正常。加进来即可。
        extractPackages("docx", "pptx", "inflect")
    }
}
android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 37
    defaultConfig {
        applicationId = "me.rerere.rikkahub.huadeng"
        minSdk = 26
        targetSdk = 37
        // 版本号 2.5.6 -> 2.5.7。本轮修掉了会清空用户聊天记录与记忆的严重
        // 缺陷，值得进一位。
        //
        // 提版本号还有一个必需的理由：版本比较只看主版本段
        // （UpdateChecker.isNewerMainVersion），若继续停在 2.5.6，第四段的
        // 每日构建序号会被忽略，装了 nightly 的用户永远看不到这次的更新提示。
        //
        // versionCode 保持本地递增（高于历史 232，避免被系统判定为降级）。
        versionCode = 233
        versionName = "2.5.7"

        // 插件解密密钥的派生因子之一：由签名口令做 HMAC，口令只存在于
        // local.properties / CI secrets，不写入 APK 明文。APK 内只保留 HMAC 结果，
        // 缺少口令则无法反推出原始密钥材料。
        val kdfProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val signSecret = kdfProps.getProperty("keyPassword")
            ?: kdfProps.getProperty("storePassword")
            ?: ""
        val kdfFactor = if (signSecret.isEmpty()) {
            // 未配置签名口令（如 CI 未注入 secrets）：退化为占位值。
            // 该分支构建出的包无法解密证书派生的插件，属预期行为。
            logger.warn(
                "[华灯] PLUGIN_KDF_FACTOR 降级为占位值：local.properties 缺少 " +
                "keyPassword/storePassword，本次构建的 APK 无法解密 v2 加密插件。"
            )
            "NO_SIGNING_SECRET"
        } else {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(
                "hd.plugin.kdf.factor".toByteArray(), "HmacSHA256"))
            mac.doFinal(signSecret.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        // 注意：PLUGIN_KDF_FACTOR 不再写入 BuildConfig。
        //
        // 该常量会被编译成 dex 里的字符串字面量，任何 strings | grep 都能取出，
        // 配合公开的签名证书即可完整复现密钥派生、解开插件载荷。
        // 因子现由 libhdguard.so 在运行时供给（见 app/src/main/cpp/hdguard.c），
        // 上面算出的 kdfFactor 仍用于校验 native 侧数据的一致性，但不进入 APK。
        check(kdfFactor == HD_GUARD_EXPECTED_FACTOR) {
            "[华灯] 签名口令推导出的因子与 libhdguard.so 内置值不一致。" +
                "两者必须相同，否则 APK 无法解密 v2 插件。" +
                "请重新生成 hdguard.c 中的 BLOB，或检查 local.properties 的签名口令。"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "ENABLE_FIREBASE", enableFirebase.toString())

        ndk {
            // 只出 arm64-v8a 单一 APK（Chaquopy 要求 ndk.abiFilters，故不再用 splits abi）
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // hdguard：插件密钥派生因子的运行时供给，不依赖第三方库
                arguments += listOf("-DANDROID_STL=none")
                cFlags += listOf("-O2", "-fvisibility=hidden", "-fno-stack-protector")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))
                val storeFilePath = localProperties.getProperty("storeFile")
                val storePasswordValue = localProperties.getProperty("storePassword")
                val keyAliasValue = localProperties.getProperty("keyAlias")
                val keyPasswordValue = localProperties.getProperty("keyPassword")
                if (storeFilePath != null && storePasswordValue != null &&
                    keyAliasValue != null && keyPasswordValue != null
                ) {
                    storeFile = file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
                // 前端卡宿主桥走 WebView 反射，R8 看不见调用方，
                // 会把 @JavascriptInterface 方法改名、把注入的 JS 常量整段删掉。
                // 不挂这份规则时 debug 正常、release 静默失效，只在真机上表现为
                // 「宿主未注入 generate 接口」。
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
            }
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // 纯 JVM 单元测试中 android.util.Log 等调用返回默认值而非抛异常
            isReturnDefaultValues = true
        }
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
    androidResources {
        generateLocaleConfig = false
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libtermux.so"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)
    implementation(libs.androidx.material3.adaptive.navigation3)
    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)


    // DataStore
    implementation(libs.androidx.datastore.preferences)
    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)
    // Haze (background blur)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.material3)
    implementation(libs.haze.glass)
    implementation(libs.haze.glass.material3)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)
    implementation(libs.diffutils)
    implementation(libs.termux.terminal.view)
    implementation(libs.guava.listenablefuture)
    // jetbrains markdown parser
    implementation(libs.jetbrains.markdown)
    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)
    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // ucrop
    implementation(libs.ucrop)
    // pebble (template engine)
    implementation(libs.pebble)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)
    // serialization
    implementation(libs.kotlinx.serialization.json)
// QuickJS (JS 引擎执行; 原由 highlight 模块 api 传递, 上游重写 highlight 后需显式声明)
    implementation(libs.quickjs)

    // YAML front matter
    implementation(libs.snakeyaml)
    // zxing
    implementation(libs.zxing.core)
    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
        implementation(libs.androidx.camera.core)
    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    baselineProfile(project(":app:baselineprofile"))
    ksp(libs.androidx.room.compiler)
    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    // Apache Commons Text
    implementation(libs.commons.text)
    // Jsoup (HTML 渲染，richtext 组件用)
    implementation(libs.jsoup)
    // Toast (Sonner)
    implementation(libs.sonner)
    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)
    // lucide icons
    implementation(libs.lucide.icons)
    implementation(libs.huge.icons)
    // image viewer
    implementation(libs.image.viewer)
    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)
    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)
    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)
    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)
    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)
    // modules
    implementation(project(":ai"))
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(project(":videogen"))
    implementation(project(":common"))
    implementation(project(":material3"))
    implementation(project(":workspace"))
    implementation(project(":oauth"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))
    // Leak Canary
    // debugImplementation(libs.leakcanary.android)
    // tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// ── JS engine assets check ──
// CI 通过 .github/workflows/build.yml 的 esbuild 步骤生成这些文件。
// 本地开发时文件可能不存在，打印明确提示，不阻断构建。
val jsEngines = listOf(
    "qimen-engine.js", "ziwei-nihai.js", "iching-shifa-engine.js",
    "taixuan-engine.js", "lunar-engine.js", "astronomy-engine.js",
    "horoscope-engine.js", "kaabalah-engine.js", "caelus-engine.js",
    "iztro-engine.js", "natalengine-engine.js", "node-jhora-engine.js"
)
tasks.register("checkJsEngines") {
    doLast {
        val assetsDir = layout.projectDirectory.dir("src/main/assets")
        val missing = jsEngines.filter { !assetsDir.file(it).asFile.exists() }
        if (missing.isNotEmpty()) {
            logger.warn("⚠️  Missing JS engines (${missing.size}/${jsEngines.size}): ${missing.joinToString(", ")}")
            logger.warn("    CI builds these via esbuild from npm packages. Local eval_javascript tool will fail until APK is built by CI.")
            logger.warn("    To build locally: install npm + esbuild, then run CI steps manually or download prebuilt files from CI artifacts.")
        } else {
            logger.lifecycle("✅ All ${jsEngines.size} JS engines present in assets/")
        }
    }
}
tasks.named("preBuild") { dependsOn("checkJsEngines") }

// ── Chaquopy 依赖瘦身 ──
//
// requirements-common.imy 是一个 stored（不再二次压缩）的 zip，直接决定 APK
// 体积的一半。里面带着每个包完整的 tests/ 目录——pandas 的测试套件单独就占
// 十几 MB，全包合计约 15.7 MB。
//
// 生产环境永远不会跑这些测试：包里没有 pytest，也没有代码 import 它们。
// 属于「引入了资源但实际不会被使用」的典型形态，删掉零功能影响。
//
// 实现交给 build-tools/strip_chaquopy.py：Gradle 的 Kotlin DSL 里 java.util.zip
// 会被解析成项目属性而报 Unresolved reference，用外部脚本反而干净可控。
//
// 时机：mergeReleaseAssets 之后、packageRelease 之前就地重写那些 imy。
// 之所以原地改而不是产出到新目录：AGP 9 没有公开 API 去替换 packageRelease
// 读取 assets 的位置，原地替换是唯一不依赖其内部结构的接法。
val stripChaquopyTests = tasks.register<Exec>("stripChaquopyTests") {
    description = "剔除 Chaquopy imy 中的 tests / .pyi / .h 条目以减小 APK 体积"
    group = "build"

    val assetsDir = layout.buildDirectory.dir("intermediates/assets/release/mergeReleaseAssets")
    val script = rootProject.layout.projectDirectory.file("build-tools/strip_chaquopy.py")

    onlyIf { assetsDir.get().asFile.resolve("chaquopy").exists() }

    commandLine(
        "python3", script.asFile.absolutePath,
        assetsDir.get().asFile.absolutePath,
    )
    // Exec 任务的输出不可预测，关掉 up-to-date 检查，保证每次打包都真的执行
    outputs.upToDateWhen { false }
}

tasks.matching { it.name == "mergeReleaseAssets" }.configureEach {
    finalizedBy(stripChaquopyTests)
}
tasks.matching { it.name == "packageRelease" }.configureEach {
    mustRunAfter(stripChaquopyTests)
}
