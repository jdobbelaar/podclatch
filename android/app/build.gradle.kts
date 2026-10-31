plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.podclatch"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.podclatch"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes only from environment variables (set by the Release APK workflow). Without
    // all four, no signing configuration is attached and every other build is unchanged: the release APK
    // is simply unsigned. The keystore and its passwords are never in the repository.
    val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH").orEmpty()
    val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD").orEmpty()
    val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS").orEmpty()
    val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD").orEmpty()
    val hasReleaseSigning = listOf(
        releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword,
    ).all { it.isNotBlank() }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")

    lint {
        // Voice search in the car is deliberately not offered (US-16: no search, no text or voice input while driving).
        disable += "MissingIntentFilterForMediaSearch"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ManifestTest inspects the merged manifests, so the unit tests need them built first.
tasks.matching { it.name == "testDebugUnitTest" || it.name == "testReleaseUnitTest" }.configureEach {
    dependsOn("processDebugManifest", "processReleaseManifest")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.graphics.path)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.reorderable)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
