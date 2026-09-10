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
            install("requests")
            install("beautifulsoup4")
            install("markdown")
            install("pypdf")
            install("openpyxl")
            install("markdownify")
            install("tabulate")
            install("python-dateutil")
            install("numpy")
            install("geocoder")
            install("geopy")
            install("pytz")
            install("timezonefinder")
            install("pyyaml")
            install("markupsafe")
            install("setuptools")
        }
    }
}
android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 37
    defaultConfig {
        applicationId = "me.rerere.rikkahub"
        minSdk = 26
        targetSdk = 37
        versionCode = 197
        versionName = "2.5.2fix10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "ENABLE_FIREBASE", enableFirebase.toString())

        ndk {
            // 只出 arm64-v8a 单一 APK（Chaquopy 要求 ndk.abiFilters，故不再用 splits abi）
            abiFilters += listOf("arm64-v8a")
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
    // Jsoup (HTML parsing for 教务 parsing)
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
