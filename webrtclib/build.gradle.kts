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
}

dependencies {
    // WebRTC / WebSocket / JSON 通过 api 暴露给宿主 app，便于 UI 层直接使用 org.webrtc 类型
    api("io.github.webrtc-sdk:android:125.6422.02")
    api("org.java-websocket:Java-WebSocket:1.5.3")
    api("com.alibaba:fastjson:1.1.72.android")

    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
