plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.youtubevoice.app"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.youtubevoice.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.4.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.database)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.newpipe.extractor)
    implementation(libs.coil.compose)

    implementation(libs.androidx.datastore.preferences)
}

tasks.register<Exec>("runNdkBuild") {
    group = "build"
    val ndkDir = android.ndkDirectory
    val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    executable = if (isWindows) {
        ndkDir.resolve("ndk-build.cmd").absolutePath
    } else {
        ndkDir.resolve("ndk-build").absolutePath
    }
    args(
        "NDK_PROJECT_PATH=${layout.buildDirectory.get().asFile.resolve("intermediates/ndkBuild").absolutePath}",
        "NDK_LIBS_OUT=${project.projectDir.resolve("src/main/jniLibs").absolutePath}",
        "APP_BUILD_SCRIPT=${project.projectDir.resolve("src/main/jni/Android.mk").absolutePath}",
        "NDK_APPLICATION_MK=${project.projectDir.resolve("src/main/jni/Application.mk").absolutePath}",
        "-j${Runtime.getRuntime().availableProcessors()}"
    )
}

tasks.named("preBuild").configure {
    dependsOn("runNdkBuild")
}
