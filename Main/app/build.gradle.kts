plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.amitutilitysoft.mepcalc"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.amitutilitysoft.mepcalc"
    minSdk = 26
    targetSdk = 36
    // Overridable by CI: -PmepVersionCode=N -PmepVersionName=1.0.N (release-apk
    // workflow bumps release.version at the repo root on every signed release).
    versionCode = (findProperty("mepVersionCode") as String?)?.toIntOrNull() ?: 1
    versionName = (findProperty("mepVersionName") as String?) ?: "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
    viewBinding = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }

  // Keep precompiled/stdlib.mathc uncompressed inside the APK/AAB for fast asset reads
  androidResources {
    noCompress += "mathc"
  }

  // Compile the Math Engine (MEP) from src/main/cpp/mathengine/ by default
  // (unified top-to-bottom executable module architecture, unbounded modules)
  // unless -PusePrebuiltMepSo is passed (e.g. after tools/build_android_arm64.sh).
  if (!project.hasProperty("usePrebuiltMepSo")) {
    sourceSets.getByName("main") {
      jniLibs.setSrcDirs(emptyList<File>())
    }
    externalNativeBuild {
      cmake {
        path = file("src/main/cpp/mathengine/CMakeLists.txt")
      }
    }
    defaultConfig {
      // -PmepAbi=x86_64 lets CI build the engine for an x86_64 emulator; default stays arm64-v8a.
      val mepAbi = (project.findProperty("mepAbi") as String?) ?: "arm64-v8a"
      ndk { abiFilters += listOf(mepAbi) }
      externalNativeBuild {
        cmake {
          arguments += listOf(
            "-DMATH_ENGINE_BUILD_SHARED=ON",
            "-DCMAKE_BUILD_TYPE=Release"
          )
          cFlags += listOf("-O2", "-fPIC", "-fvisibility=hidden")
        }
      }
    }
  }

  // Store-facing signed releases are UNIVERSAL: the release workflow builds
  // `libmathengine.so` for every ABI below (see cpp/build_engine_abi.sh) and
  // passes -PmepAbis=... so the packaged APK advertises exactly the ABIs that
  // really contain the engine. Without this, the APK shipped the engine only
  // for arm64-v8a while still installing on armeabi-v7a/x86 devices, where it
  // crashed on launch.
  (project.findProperty("mepAbis") as String?)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?.let { abis ->
      defaultConfig {
        ndk {
          abiFilters.clear()
          abiFilters += abis
        }
      }
    }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation(libs.okhttp)
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")
  implementation("androidx.viewpager2:viewpager2:1.1.0")
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
