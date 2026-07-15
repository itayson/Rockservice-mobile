import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "org.rockservice.ndk.rockchip"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake { cppFlags += listOf("-Wall", "-Wextra", "-Werror") }
        }
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
