plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.zml.m7208w"
    compileSdk = 35
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a") }
        applicationId = "com.zml.m7208w"
        minSdk = 24
        targetSdk = 35
        versionCode = 15
        versionName = "8.2"
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // 二维码解码仅用ZXing核心库(无UI/相机依赖), 配网扫码用(D2)
    implementation("com.google.zxing:core:3.5.2")
}
