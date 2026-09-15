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
        maven(url = "https://devrepo.kakao.com/nexus/repository/kakaomap-releases/")
        // Maintained AndroidUSBCamera/AUSBC fork used by the experimental USB CHASE camera path.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "JangsuBatteryPilot"
include(":app")