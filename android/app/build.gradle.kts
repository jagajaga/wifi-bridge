plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.wifibridge"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.wifibridge"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("WB_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("WB_VERSION_NAME") ?: "0.1.0"
    }

    // Release signing comes from the environment (CI secrets). Without it the
    // release build falls back to the debug key so it is still installable.
    val releaseKeystore = System.getenv("WB_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("WB_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WB_KEY_ALIAS")
                keyPassword = System.getenv("WB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    testOptions {
        unitTests.all {
            // Shared, independently generated vectors live outside the Android project.
            it.systemProperty(
                "wb.vectors",
                rootProject.file("../docs/test-vectors.json").absolutePath,
            )
            it.systemProperty("wb.srcMain", project.file("src/main/java").absolutePath)
            it.maxHeapSize = "1g"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.tink.android)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded) {
        exclude(group = "com.google.zxing", module = "core")
    }
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
