plugins {
    id("com.android.application")
}

android {
    namespace = "com.modar.samp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.modar.samp"
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

    // Ни AndroidX, ни сторонних библиотек: только framework-API,
    // поэтому APK собирается и без Android Studio (см. samp-launcher/tools/build-apk.sh).
}

dependencies {
    // Намеренно пусто.
}
