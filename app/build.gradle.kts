plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.renpy.mobile"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.renpy.mobile"
        minSdk = 24
        // Termux 兼容性：Android 10 (API 29) 起禁止 exec 应用私有可写目录里的二进制
        // （W^X 策略，execve 返回 EACCES），而 bootstrap 的 bash/apt 都装在 files/usr 下。
        // 官方 Termux 至今把 targetSdk 钉在 28 以保留该能力，这里同样处理。
        targetSdk = 28
        versionCode = 14
        versionName = "0.3.7"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // 内置的 Termux bootstrap 已是 zip，禁止 aapt 二次压缩，
    // 保证运行时能直接按流式从 assets 读取并解压。
    androidResources {
        noCompress += "termux-bootstrap-aarch64.zip"
    }

    // 内置引擎桥接用 dlopen(nativeLibraryDir/librenpython.so) 加载 38MB 引擎库，
    // 需要 .so 在设备安装时落地为实体文件（而非直接从 APK 映射），故保留旧式解包。
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
    implementation(composeBom)
    // M3 Expressive：material3 1.5.0 公开了 MotionScheme / 新组件 / 48 色角色，由 BOM 统一管理
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.graphics:graphics-shapes:1.0.1")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Termux 终端模拟器（Apache 2.0）：真 PTY + 完整终端能力
    implementation(project(":termux-libs:terminal-view"))
}
