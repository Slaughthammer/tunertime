import java.io.FileInputStream
import java.util.Properties
val keyprops=Properties().apply {
    load(FileInputStream(rootProject.file("keystore.properties")))
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.myapplication20251227_2"
    compileSdk {
        version = release(36)
    }
    sourceSets {
        main {
            jniLibs.srcDirs("src/main/lib")
        }
    }
    signingConfigs {
        create("release") {
            storeFile = file(keyprops["storeFile"] as String)
            storePassword = keyprops["storePassword"] as String
            keyAlias = keyprops["keyAlias"] as String
            keyPassword = keyprops["keyPassword"] as String
        }
    }

    defaultConfig {
        applicationId = "com.example.myapplication20251227_2"
        minSdk = 16
        targetSdk = 36
        versionCode = 17
        versionName = "17.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
/*
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
*/
}
