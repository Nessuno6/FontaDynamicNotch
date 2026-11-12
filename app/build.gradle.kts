plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.fonta.dynamicnotch"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.fonta.dynamicnotch"
        minSdk = 31
        targetSdk = 37
        // Matches the GitHub release tag (v<versionName>): the in-app update check compares them.
        versionCode = 4
        versionName = "0.2.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    buildTypes {
        // Compose runs far slower in a debuggable build; this is the one to judge animations by. Signed with the
        // debug key so it installs over a debug build (and keeps the settings) on your own devices.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.savedstate)
    implementation(libs.kotlinx.serialization.json)
    // Lets the notch window (not an Activity) take the read permission of files dropped on it.
    implementation(libs.hiddenapibypass)
}
