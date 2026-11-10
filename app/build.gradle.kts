import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load keystore properties
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.unixshells.devbrowser"
    compileSdk = 35

    defaultConfig {
        // 앱 ID: Play 스토어 원본(com.unixshells.devbrowser)과 겹치면 설치 실패·원본 업데이트 덮어쓰기가 생겨 .secure로 분리
        applicationId = "com.unixshells.devbrowser.secure"
        minSdk = 35
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

// DevTools frontend is pre-built from Chromium source and checked into
// app/src/main/assets/devtools_frontend/. To rebuild it:
//   1. Build devtools-frontend in your Chromium checkout:
//      autoninja -C out/Release third_party/devtools-frontend/src/front_end
//   2. Copy the built files:
//      ./scripts/copy-devtools.sh

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.9.0")

    // NanoHTTPD for local HTTP server (serves DevTools frontend)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

}
