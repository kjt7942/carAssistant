plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 앱(네이티브) 버전: 자바/코틀린, 매니페스트, gradle을 바꿀 때만 native-version.txt를 1 올립니다.
// 화면(index.html)만 바꾸면 올리지 않아도 자동 업데이트됩니다.
val nativeVersion = file("../native-version.txt").readText().trim().toInt()

android {
    namespace = "com.kjt7942.carassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kjt7942.carassistant"
        minSdk = 29
        targetSdk = 34
        versionCode = nativeVersion
        versionName = "0.2.$nativeVersion"
    }

    // 업데이트 설치에는 매번 같은 서명 키가 필요합니다. 키는 GitHub Actions 비밀값으로만 전달합니다.
    val keystorePath: String? = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("fixed") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("fixed")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
