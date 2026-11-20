plugins {
    id("com.android.application")
}

android {
    namespace = "dev.deepgravity"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.deepgravity"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.1.0"
    }

    signingConfigs {
        create("sideload") {
            storeFile = file("../keystore/webhub.jks")
            storePassword = "webhub123"
            keyAlias = "webhub"
            keyPassword = "webhub123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*", "**/*.kotlin_metadata")
    }
}

// 零第三方依赖，APK 保持最小
dependencies { }
