import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystorePath = providers.environmentVariable("ROCKSERVICE_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("ROCKSERVICE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ROCKSERVICE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ROCKSERVICE_KEY_PASSWORD").orNull
val requireReleaseSigning =
    providers.environmentVariable("ROCKSERVICE_REQUIRE_RELEASE_SIGNING").orNull == "true"
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { value -> !value.isNullOrBlank() }

if (requireReleaseSigning && !releaseSigningConfigured) {
    error(
        "Release signing is required, but ROCKSERVICE_KEYSTORE_PATH, " +
            "ROCKSERVICE_KEYSTORE_PASSWORD, ROCKSERVICE_KEY_ALIAS and " +
            "ROCKSERVICE_KEY_PASSWORD were not all provided.",
    )
}

android {
    namespace = "org.rockservice.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.rockservice.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha01"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "REAL_USB_WRITE_ENABLED", "false")
        }
        release {
            isMinifyEnabled = true
            buildConfigField("boolean", "REAL_USB_WRITE_ENABLED", "false")
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-common"))
    implementation(project(":core-security"))
    implementation(project(":core-usb"))
    implementation(project(":feature-device-detection"))
    implementation(project(":feature-firmware"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}
