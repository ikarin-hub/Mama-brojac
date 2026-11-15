plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every GitHub build gets a higher version number, so a new APK installs over the old one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "hr.mamabrojac.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "hr.mamabrojac.app"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
        // Phones only (keeps the app smaller)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Fixed key kept in the project, so updates install without uninstalling first.
    signingConfigs {
        create("fixed") {
            storeFile = file("mama.keystore")
            storeType = "PKCS12"
            storePassword = "mamabrojac"
            keyAlias = "mama"
            keyPassword = "mamabrojac"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Fast mode: on-device speech model (Vosk), downloaded into assets by the build
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
