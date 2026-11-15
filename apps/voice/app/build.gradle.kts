plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.dsh.voice"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "dev.dsh.voice"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "7.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

val sherpaAar = file("libs/sherpa-onnx-1.13.8.aar")
if (!sherpaAar.exists()) {
    logger.warn("[DSH Voice] 缺少 app/libs/sherpa-onnx-1.13.8.aar，构建会失败。" +
        "请从 https://github.com/k2-fsa/sherpa-onnx/releases 下载后放到 app/libs/（见 PLACE_AAR_HERE.md）")
}

dependencies {
    implementation(files(sherpaAar))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
}
