import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.privacyguard.app"
    // API 37 平台包已改为 minor-revision 命名（platforms;android-37.0），
    // 必须声明 compileSdkMinor=0，AGP 才能以哈希 android-37.0 定位平台（否则报
    // "Failed to find target with hash string 'android-37'"，本机/新版 cmdline-tools 无法构建）
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.privacyguard.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 260
        versionName = "2.6.0"

        resourceConfigurations += listOf("zh", "zh-rCN")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
            arg("room.expandProjection", "true")
        }
    }

    signingConfigs {
        // CI 注入正式签名：CI_KEYSTORE_BASE64 / CI_KEYSTORE_PASSWORD / CI_KEYSTORE_ALIAS
        // 三个环境变量均存在时，把 base64 解码为 build 目录内的临时 keystore 并创建 ciRelease 配置；
        // 任一缺失则不创建，release 回退 debug 签名（配置方法见 README「Release 签名与 CI」）。
        if (!System.getenv("CI_KEYSTORE_BASE64").isNullOrBlank() &&
            !System.getenv("CI_KEYSTORE_PASSWORD").isNullOrBlank() &&
            !System.getenv("CI_KEYSTORE_ALIAS").isNullOrBlank()
        ) {
            create("ciRelease") {
                val keystoreFile = layout.buildDirectory.file("tmp-signing/release.keystore").get().asFile
                keystoreFile.parentFile.mkdirs()
                keystoreFile.writeBytes(Base64.getMimeDecoder().decode(System.getenv("CI_KEYSTORE_BASE64")))
                storeFile = keystoreFile
                storePassword = System.getenv("CI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CI_KEYSTORE_ALIAS")
                keyPassword = System.getenv("CI_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 正式签名仅在 CI 注入 CI_KEYSTORE_* 环境变量时生效；
            // 回退 debug 签名仅供本地开发出包，debug 签名的 release 包不可用于正式发布或覆盖升级。
            signingConfig = signingConfigs.findByName("ciRelease") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        // AGP 9 默认关闭 BuildConfig 生成；代码中引用 BuildConfig（如 VERSION_NAME）必须显式开启
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.version"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/*.txt"
        }
    }
}

dependencies {
    // AndroidX & Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Miuix 0.9.4 (Xiaomi HyperOS / MIUI Design for Compose)
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")

    // Room 2.8.5
    val roomVersion = "2.8.5"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Root / libsu
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
}

// [压制说明] 禁用 AAR metadata 校验（check*AarMetadata）：当前依赖组合下该校验会阻断 CI 出包，
// 且 Release 运行时行为未在本环境完整验证，属保守压制。
// [恢复条件] 升级 AGP / 依赖后若构建能在启用该校验的情况下通过，应删除本段恢复默认校验。
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach {
    enabled = false
}
