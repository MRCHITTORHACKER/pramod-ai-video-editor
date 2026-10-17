import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pramod.videoeditor"
    compileSdk = 36

    // Release signing: wired only when keystore.properties exists next to settings.gradle.kts.
    // CI / debug builds work fine without it.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystoreProps.load(FileInputStream(keystorePropsFile))
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.pramod.videoeditor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // inter-module
    implementation(project(":core-model"))
    implementation(project(":core-database"))
    implementation(project(":core-storage"))
    implementation(project(":core-security"))
    implementation(project(":feature-home"))
    implementation(project(":feature-projects"))
    implementation(project(":feature-editor"))
    implementation(project(":feature-captions"))
    implementation(project(":feature-typography"))
    implementation(project(":feature-templates"))
    implementation(project(":feature-audio"))
    implementation(project(":feature-vfx"))
    implementation(project(":feature-export"))
    implementation(project(":ai-groq"))
    implementation(project(":ai-gemini"))
    implementation(project(":media-engine"))
    // compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    // app extras
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.core.splashscreen)
    implementation(libs.coil.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.media3.ui)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
}
