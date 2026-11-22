plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Continues on from the builds made in the Monolith repo (last one there was 112).
val buildNumber = 200 + (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.monolith.poke"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.monolith.poke"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // A fixed key, so every new Poke APK installs (and self-updates) over the last one.
    signingConfigs {
        create("poke") {
            storeFile = file("poke-signing.jks")
            storePassword = "pokepoke"
            keyAlias = "poke"
            keyPassword = "pokepoke"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("poke")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("poke")
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
        abortOnError = false
        checkReleaseBuilds = false
    }
}
