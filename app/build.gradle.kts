import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        buildConfigField("String", "API_URL", "\"https://www.wantgoo.com\"")
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
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.exoplayer.hls)
}
