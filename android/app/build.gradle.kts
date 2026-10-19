plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ets2gps"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ets2gps"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Zero third-party dependencies on purpose: plain Android framework APIs only.
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013") // the real org.json, so the trip file code can be tested off-device
}
