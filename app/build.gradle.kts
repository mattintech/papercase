plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mattintech.papercase"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mattintech.papercase"
        minSdk = 31
        targetSdk = 37
        // Used by local and F-Droid builds. The Google Play release workflow overrides
        // both with -PversionName (from the git tag) and -PversionCode (next free code on Play).
        versionCode = 2
        versionName = "1.0.0"
        providers.gradleProperty("versionCode").orNull?.let { versionCode = it.toInt() }
        providers.gradleProperty("versionName").orNull?.let { versionName = it }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing is only configured when a keystore is supplied (the Google Play
    // release workflow). F-Droid and local builds produce unsigned release APKs.
    val releaseKeystore = System.getenv("PAPERCASE_KEYSTORE_FILE")
    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("PAPERCASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PAPERCASE_KEY_ALIAS")
                keyPassword = System.getenv("PAPERCASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Debug installs as a separate app (com.mattintech.papercase.debug, "Papercase Debug"),
        // so it can sit next to the Play / F-Droid version without touching its data.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // R8: shrink, optimize and obfuscate. mapping.txt is uploaded to Play for readable crash reports.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // F-Droid rejects the encrypted dependency metadata blob Google adds by default
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("app.keemobile:kotpass:0.13.0")
    implementation("com.squareup.okio:okio:3.15.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}