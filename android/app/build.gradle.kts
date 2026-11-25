import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 設定値は local.properties(コミットしない)か環境変数から読む。
// 必要な項目は local.properties.example を参照。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun config(name: String): String =
    (localProperties.getProperty(name) ?: System.getenv(name) ?: "").trim()

val keystoreFile = config("SNAPASK_KEYSTORE_FILE")

android {
    namespace = "app.snapask"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.snapask"
        minSdk = 26
        targetSdk = 35
        // リリース用のワークフローはタグ(v0.2.0 など)と実行番号から値を渡す。手元のビルドは既定値になる。
        versionCode = config("SNAPASK_VERSION_CODE").toIntOrNull() ?: 1
        versionName = config("SNAPASK_VERSION_NAME").ifEmpty { "0.1.0" }

        buildConfigField("String", "API_BASE_URL", "\"${config("SNAPASK_API_BASE_URL")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${config("SNAPASK_GOOGLE_WEB_CLIENT_ID")}\"")
    }

    // Google ログインは APK の署名(SHA-1)を見て許可する。
    // 手元のビルドと CI のビルドで署名が変わらないよう、同じ鍵を debug / release の両方に使う。
    signingConfigs {
        if (keystoreFile.isNotEmpty()) {
            create("shared") {
                storeFile = rootProject.file(keystoreFile)
                storePassword = config("SNAPASK_KEYSTORE_PASSWORD")
                keyAlias = config("SNAPASK_KEY_ALIAS")
                keyPassword = config("SNAPASK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (keystoreFile.isNotEmpty()) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (keystoreFile.isNotEmpty()) "shared" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 仲間内に配るアプリなので、lint の指摘ではビルドを止めない(指摘はレポートに出る)。
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
}
