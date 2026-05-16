// Mythara MINIMAL watch face — Watch Face Format (WFF).
//
// Companion to the Tactical face; ships as a separate APK so the user
// can pick either from the system watch-face picker. Resource-only
// (no Kotlin/Java) — the Wear OS renderer parses res/raw/watchface.xml
// and draws it. Same applicationIdSuffix pattern as the tactical face;
// install-cluster.sh installs BOTH and lets the user choose.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mythara.watchface.minimal"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mythara.watchface.minimal"
        minSdk = 33  // WFF v1 floor (Wear OS 4)
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            // Keep resources — WFF needs every raw/xml/drawable intact.
            isShrinkResources = false
        }
    }
}
