plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ch.pdfwerkstatt.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ch.pdfwerkstatt.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    // Signatur: Schlüssel aus GitHub-Secrets (siehe README). Ohne Secrets wird mit dem Debug-Schlüssel signiert.
    val ksFile = file("release.jks")
    signingConfigs {
        if (ksFile.exists()) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // HTML/WASM/Sprachdaten nicht zusätzlich komprimieren (schneller beim Laden)
    androidResources { noCompress += listOf("html", "wasm", "gz", "js") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
