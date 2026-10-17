plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pramod.videoeditor.mediaengine"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // inter-module
    implementation(project(":core-model"))
    implementation(project(":feature-vfx"))
    implementation(project(":feature-audio"))
    // module
    implementation(libs.media3.transformer)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    implementation(libs.media3.effect)
    implementation(libs.media3.datasource)
    implementation(libs.kotlinx.coroutines.android)
}
