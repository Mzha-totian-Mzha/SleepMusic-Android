plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.sleepmusic.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.sleepmusic.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 17
        versionName = "1.15"
    }

    // امضای ثابت برای بیلدهای debug — همین keystore در ریپو commit شده تا بیلد گیت‌هاب
    // هم دقیقاً با همین امضا ساخته شود و APK هر نسخه روی نسخه‌ی قبلی نصب شود
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}