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

rootProject.name = "ModarAI"
include(":app")

// Лаунчер SA-MP (Modar SAMP): отдельное приложение в samp-launcher/.
// Из консоли без Android Studio: samp-launcher/tools/build-apk.sh
include(":samp-launcher:app")
