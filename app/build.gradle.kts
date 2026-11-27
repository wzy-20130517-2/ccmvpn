plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ccm.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ccm.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // 内置的 cloudflared 只有 arm64 版，别的 ABI 装上也跑不了
        ndk { abiFilters += "arm64-v8a" }
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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        // cloudflared 是预编译可执行文件，不能让 aapt 压缩，否则运行时解出来还得再解压
        noCompress += "cloudflared"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    // hev-socks5-tunnel：tun2socks，把 VpnService 的 TUN 流量送进 SOCKS5
    implementation("com.zaneschepke:hevtunnel:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// hev 那个 AAR 会把 core-ktx 传递提升到 1.17.0，而 1.17 要求 compileSdk 36 + AGP 8.9+。
// 我们跟 ccm-android 保持一致用 35 / 8.7.3，所以把 core-ktx 钉在 1.13.1。
configurations.all {
    resolutionStrategy {
        force("androidx.core:core:1.13.1")
        force("androidx.core:core-ktx:1.13.1")
    }
}
