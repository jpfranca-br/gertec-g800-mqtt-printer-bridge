plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// No broker settings are compiled in: they are pushed at runtime with tools/push_config.py.
android {
    namespace = "dev.posbridge.g800"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.posbridge.g800"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation("com.hivemq:hivemq-mqtt-client:1.3.17")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
