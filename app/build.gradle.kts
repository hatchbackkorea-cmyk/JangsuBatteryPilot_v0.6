plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.seungjae.jangsu280battery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seungjae.jangsu280battery"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "0.11.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}


dependencies {
    implementation("com.garmin:fit:21.213.0")
}
