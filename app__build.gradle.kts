import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Supabase config is read from KidsLock/kidslock.properties (not committed to Git).
val kidsLockCfg = Properties().apply {
    val f = rootProject.file("kidslock.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String): String = kidsLockCfg.getProperty(key, "").trim()

android {
    namespace = "vn.vicomart.kidslock"
    compileSdk = 36

    defaultConfig {
        applicationId = "vn.vicomart.kidslock"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SUPABASE_URL", "\"${cfg("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("supabase.anonKey")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    androidResources {
        noCompress += listOf("tflite")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Camera
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // On-device face detection (bundled model, works offline)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // On-device age model runtime (LiteRT, formerly TensorFlow Lite)
    implementation("com.google.ai.edge.litert:litert:1.4.0")

    // Device-owner biometric unlock
    implementation("androidx.biometric:biometric:1.1.0")
}
