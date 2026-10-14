plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val dynamicVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: 201
val dynamicVersionName = project.findProperty("versionName")?.toString() ?: "2.0.1"
val defaultGeminiKey = project.findProperty("geminiApiKey")?.toString() ?: ""

android {
    namespace = "com.rvgroup.pkassist"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rvgroup.pkassist"
        minSdk = 26
        targetSdk = 34
        versionCode = dynamicVersionCode
        versionName = dynamicVersionName

        buildConfigField("String", "GITHUB_OWNER", "\"PKYcodes\"")
        buildConfigField("String", "GITHUB_REPO", "\"PKassist\"")
        buildConfigField("String", "DEFAULT_GEMINI_KEY", "\"$defaultGeminiKey\"")
        buildConfigField("String", "PRIMARY_GEMINI_MODEL", "\"gemini-2.5-flash-lite\"")
        buildConfigField("String", "FALLBACK_GEMINI_MODEL", "\"gemini-2.0-flash-lite\"")
    }

    signingConfigs {
        create("release") {
            val ksFile = file("release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = "pkassist123"
                keyAlias = "pkassist_key"
                keyPassword = "pkassist123"
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksFile = file("release.keystore")
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
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

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
