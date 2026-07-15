pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "RockServiceMobile"

include(
    ":app",
    ":core-common",
    ":core-security",
    ":core-usb",
    ":feature-device-detection",
    ":feature-firmware",
    ":native-rockchip",
    ":native-image-tools",
)
