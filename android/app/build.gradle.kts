plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.puito.cryptoagent"
    compileSdk = 34

    defaultConfig {
        // 固定包名：覆盖安装保留 SharedPreferences
        applicationId = "com.puito.cryptoagent"
        minSdk = 26
        targetSdk = 34
        // CI 会 sed 覆盖；本地默认也保持递增基数
        versionCode = 100
        versionName = "1.0.0"
    }

    signingConfigs {
        // 全渠道同一密钥，避免 CI 默认 debug.keystore 每次不同导致无法覆盖安装
        create("stable") {
            val ks = rootProject.file("keystore/crypto-agent-upload.jks")
            if (!ks.exists()) {
                throw GradleException(
                    "Missing signing keystore: ${ks.absolutePath}. " +
                        "See android/keystore/README.md",
                )
            }
            storeFile = ks
            storePassword = System.getenv("CRYPTO_AGENT_STORE_PASSWORD") ?: "cryptoagent"
            keyAlias = System.getenv("CRYPTO_AGENT_KEY_ALIAS") ?: "cryptoagent"
            keyPassword = System.getenv("CRYPTO_AGENT_KEY_PASSWORD") ?: "cryptoagent"
        }
    }

    buildTypes {
        debug {
            // 不要再加 applicationIdSuffix，否则与正式包名不一致无法覆盖
            versionNameSuffix = "-debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.11.0")
}
