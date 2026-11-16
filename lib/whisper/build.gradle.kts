// BACKCHANNEL: offline speech recognition (whisper.cpp), built from source for every ABI.
plugins {
  id("signal-library")
}

android {
  namespace = "net.mikesimone.backchannel.whisper"
  // Same NDK as the app and the reproducible-build Docker image.
  ndkVersion = libs.versions.ndk.get()

  defaultConfig {
    consumerProguardFiles("consumer-rules.pro")
    ndk {
      //noinspection ChromeOsAbiSupport
      abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
    }
    externalNativeBuild {
      cmake {
        // Several native libraries (whisper, ggml and its per-CPU backends) share one C++ runtime.
        arguments += listOf("-DANDROID_STL=c++_shared")
      }
    }
  }

  packaging {
    jniLibs {
      // Built along with whisper.cpp but not used (AGP packages every target; restricting
      // externalNativeBuild targets would also drop ggml's run-time loaded CPU backends).
      excludes += "**/libparakeet.so"
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = libs.versions.cmake.get()
    }
  }
}
