plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.homewind.karoo"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.homewind.karoo"
        minSdk = 31 // Karoo 3: Android 12
        targetSdk = 34
        // versionCode = major * 10000 + minor * 100 + patch (CI checks it). The Karoo
        // compares it with latestVersionCode in manifest.json to offer updates.
        versionCode = 500
        versionName = "0.5.0"
    }

    // Release signing from environment (GitHub secrets in CI). Without it the release
    // build stays unsigned and CI ships the debug build instead.
    val keystoreFile = System.getenv("KAROO_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }?.takeIf { it.exists() }
    if (keystoreFile != null) {
        signingConfigs {
            create("homewind") {
                storeFile = keystoreFile
                storePassword = System.getenv("KAROO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KAROO_KEY_ALIAS")
                keyPassword = System.getenv("KAROO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("homewind")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
    }

    buildFeatures {
        buildConfig = true
        compose = true // Glance (RemoteViews for the graphical data fields)
    }
}

dependencies {
    implementation(libs.karoo.ext)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
