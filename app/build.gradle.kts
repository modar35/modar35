plugins {
    id("com.android.application")
}

android {
    namespace = "com.modar.riftarena"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.modar.riftarena"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    sourceSets {
        getByName("main") {
            // Один и тот же локальный game bundle используется в web preview и APK.
            assets.srcDir("../web")
        }
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
