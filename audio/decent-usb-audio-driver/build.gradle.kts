// Vendored from the decent-player USB audio driver (MIT, see LICENSE).
// LastWave ships the same module; keeping the source identical is deliberate —
// the usbdevfs isochronous path is hardware-sensitive and every deviation here
// has to be re-proved on a DAC.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.decent.usbaudio"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        externalNativeBuild { cmake { cppFlags("-fno-fast-math") } }
    }

    buildTypes {
        getByName("debug")
        getByName("release")
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
