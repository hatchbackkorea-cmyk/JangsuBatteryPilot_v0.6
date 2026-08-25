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
        versionCode = 25
        versionName = "0.16.0"
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
    }
}

dependencies {
    implementation("com.garmin:fit:21.213.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
