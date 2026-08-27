plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val updateRepository = (project.findProperty("updateRepo")?.toString()
    ?: System.getenv("UPDATE_REPOSITORY")
    ?: System.getenv("GITHUB_REPOSITORY")
    ?: "").trim()
val signingKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")

android {
    namespace = "com.seungjae.jangsu280battery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seungjae.jangsu280battery"
        minSdk = 26
        targetSdk = 35
        val appVersionName = rootProject.file("VERSION.txt").readText().trim()
        val versionParts = appVersionName.substringBefore("-").split(".").map { it.toInt() }
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        versionName = appVersionName
        val escapedRepo = updateRepository.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$escapedRepo\"")
    }

    signingConfigs {
        if (!signingKeystorePath.isNullOrBlank()) {
            create("fixedRelease") {
                storeFile = file(signingKeystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("fixedRelease")?.let { signingConfig = it }
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        aidl = true
    }
}

dependencies {
    implementation("com.garmin:fit:21.213.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.core:core-ktx:1.15.0")
}
