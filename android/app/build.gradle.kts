plugins {
    id("com.android.application")
}

// 发布签名从用户级 ~/.gradle/gradle.properties 读取（不放进仓库）。没配置时 release 版用调试签名，方便别人自己编译。
val releaseStore = providers.gradleProperty("FT_STORE_FILE").orNull

android {
    namespace = "com.cjxh.filetransfer"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.cjxh.filetransfer"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("FT_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("FT_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("FT_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseStore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
