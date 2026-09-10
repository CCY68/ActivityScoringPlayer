import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.johnson.fitness"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.johnson.fitness"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 設定頁顯示的建置資訊：電視上裝的是哪一次 build、對應哪個 commit。
        // 時間取建置機器的本地時區；每次 build 都會變，這個 demo 不在意快取失效。
        // 用檔頭 import：android {} 區塊內的 `java` 會解析成 Gradle 的 java 擴充，java.time 反而找不到
        val buildTime = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz"))
        val gitSha = runCatching {
            providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
                .standardOutput.asText.get().trim()
        }.getOrDefault("unknown")
        val gitDirty = runCatching {
            // 只看已追蹤檔：未追蹤的雜檔（例如本機的 AGENTS.override.md）不影響建置內容
            providers.exec { commandLine("git", "status", "--porcelain", "--untracked-files=no") }
                .standardOutput.asText.get().isNotBlank()
        }.getOrDefault(false)
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha${if (gitDirty) "+dirty" else ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(files("libs/device-module.aar"))
    implementation(files("libs/activity-scoring-core.aar"))
    // Core AAR 已內含 MAF 解析／解密 class；kotlinx.serialization 仍是其外部 runtime dependency。
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.tv.material)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.glide.compose)
    implementation(libs.material.icons.core)
    implementation(libs.material.icons.extended)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.exoplayer.hls)
    testImplementation(libs.junit)
}
