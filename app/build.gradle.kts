import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: environment variables (CI) or keystore.properties (local, git-ignored).
//   SIGNING_STORE_FILE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signing(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)

// No broker settings are compiled in: they are pushed at runtime (tools/push_config.py) or set on the device.
android {
    namespace = "dev.posbridge.g800"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.posbridge.g800"
        minSdk = 26
        targetSdk = 35
        // CI sets these from the git tag (v1.2.3) and the run number.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 3
        versionName = System.getenv("VERSION_NAME") ?: "0.3.0"
    }

    signingConfigs {
        val storeFile = signing("SIGNING_STORE_FILE", "storeFile")
        if (storeFile != null) {
            create("release") {
                this.storeFile = rootProject.file(storeFile)
                storePassword = signing("SIGNING_STORE_PASSWORD", "storePassword")
                keyAlias = signing("SIGNING_KEY_ALIAS", "keyAlias")
                keyPassword = signing("SIGNING_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // HiveMQ/Netty would need keep rules; the APK is small enough
            signingConfig = signingConfigs.findByName("release")
        }
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

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

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
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
