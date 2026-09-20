plugins {
    id("com.android.application")
}

android {
    namespace = "com.modar.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.modar.ai"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // В приложении нет AndroidX и внешних зависимостей: только framework-API.
}

dependencies {
    // Намеренно пусто — приложение собирается без сторонних библиотек.
}
