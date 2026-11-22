plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release signing. Set these env vars (the GitHub workflow does it from
// repo secrets) so every build is signed with the same key and updates install
// over the top. Without them the release APK is signed with the debug key.
val keystorePath: String? = System.getenv("AR_KEYSTORE_PATH")

android {
    namespace = "za.co.arsmarthome.mediabridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "za.co.arsmarthome.mediabridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("AR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AR_KEY_ALIAS")
                keyPassword = System.getenv("AR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
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
    buildFeatures {
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "META-INF/*"
    }
}

dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}
