plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.wangyao.webrtclib"
    compileSdk = 34

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -fexceptions"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // WebRTC 预编译 .so 存放在 jniLibs/<abi>/ 下（由自编译脚本产出或使用随附版本）
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    // ===================================================================
    // [已注释] 原先通过 Maven 引入的预编译 WebRTC 包。
    // 现已改为「本地 .so + 本地 org.webrtc Java 源码」的方式，
    // .so 位于 src/main/jniLibs/<abi>/libjingle_peerconnection_so.so，
    // Java 源码位于 src/main/java/org/webrtc/。
    // 如需恢复 Maven 方式，取消下面两行注释即可（并删除本地 jniLibs/java 源码）。
    // -------------------------------------------------------------------
    // api("io.github.webrtc-sdk:android:125.6422.02")
    // [A/B 诊断结束] 已恢复本地 .so + org.webrtc 源码方式。
    // ===================================================================

    api("org.java-websocket:Java-WebSocket:1.5.3")
    api("com.alibaba:fastjson:1.1.72.android")

    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
