plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// 原生工具链版本来自 gradle.properties，与 :app 模块、CI 预装步骤共用同一来源。
// 缺失或为空即失败，避免 AGP 悄悄回退到自带默认 NDK 版本后重新触发整包下载。
fun nativeToolchainVersion(key: String): String =
    providers.gradleProperty(key).orNull?.takeIf { it.isNotBlank() }
        ?: error("gradle.properties 缺少 $key（或该值为空）")

val nativeNdkVersion = nativeToolchainVersion("rikkahub.ndkVersion")
val nativeCmakeVersion = nativeToolchainVersion("rikkahub.cmakeVersion")

android {
    namespace = "me.rerere.workspace"
    ndkVersion = nativeNdkVersion

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = nativeCmakeVersion
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
