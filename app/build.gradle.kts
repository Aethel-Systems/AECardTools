/*
 * Copyright (C) 2025-2026  Aethel-Systems
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.chaquo.python") version "17.0.0"
}

import java.util.Properties

val versionLabel = "1.0"
val releaseKeystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use(::load)
    }
}

fun Project.readBuildSetting(propertyName: String, vararg envNames: String): String? =
    providers.gradleProperty(propertyName).orNull
        ?: envNames.firstNotNullOfOrNull { providers.environmentVariable(it).orNull }
        ?: releaseKeystoreProperties.getProperty(propertyName)

android {
    namespace = "com.aethel.aecardtools"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.aethel.aecardtools"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = versionLabel

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("arm64") {
            dimension = "distribution"
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a")
            }
        }
        create("universal") {
            dimension = "distribution"
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
        }
    }

    val releaseStoreFile = project.readBuildSetting(
        "release.storeFile",
        "AETHEL_KEYSTORE_PATH",
        "AECARDTOOLS_RELEASE_STORE_FILE"
    )
    val releaseStorePassword = project.readBuildSetting(
        "release.storePassword",
        "AETHEL_KEYSTORE_PASSWORD",
        "AECARDTOOLS_RELEASE_STORE_PASSWORD"
    )
    val releaseKeyAlias = project.readBuildSetting(
        "release.keyAlias",
        "AETHEL_KEY_ALIAS",
        "AECARDTOOLS_RELEASE_KEY_ALIAS"
    )
    val releaseKeyPassword = project.readBuildSetting(
        "release.keyPassword",
        "AETHEL_KEY_PASSWORD",
        "AECARDTOOLS_RELEASE_KEY_PASSWORD"
    )
    val hasReleaseSigning =
        !releaseStoreFile.isNullOrBlank() &&
            !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() &&
            !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                project.file("proguard-rules.pro")
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/proguard/androidx-*.pro"
        }
    }

}

chaquopy {
    defaultConfig {
        buildPython = listOf(
            project.readBuildSetting("chaquopy.buildPython", "CHAQUOPY_BUILD_PYTHON") ?: "python3"
        )
        withGroovyBuilder {
            "pyc" {
                setProperty("src", false)
            }
        }
        pip {
            install("argon2-cffi")
            install("cryptography")
            install("lz4")
        }
    }
}

dependencies {
    // Core Android & Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // ViewModel and Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.6.1")

    // Material 3 Icons Extended
    implementation("androidx.compose.material:material-icons-extended:1.5.4")

    // NFC 支持 (系统框架内置，无需额外依赖)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Serialization
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.json:json:20231013")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        freeCompilerArgs.set(listOf(
            "-Xsuppress-version-warnings",
            "-Xno-warn-unchecked-cast"
        ))
    }
}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        val isArm64Flavor = variantBuilder.productFlavors.any { (dimension, flavor) ->
            dimension == "distribution" && flavor == "arm64"
        }
        if (variantBuilder.buildType == "debug" && isArm64Flavor) {
            variantBuilder.enable = false
        }
    }
}

val openSourceOutputDir = layout.buildDirectory.dir("outputs/open-source")

val collectDebugApk by tasks.registering(Copy::class) {
    dependsOn("assembleUniversalDebug")
    from(layout.buildDirectory.file("outputs/apk/universal/debug/app-universal-debug.apk"))
    into(openSourceOutputDir)
    rename { "AECardTools-v$versionLabel-debug.apk" }
}

val collectArm64ReleaseApk by tasks.registering(Copy::class) {
    dependsOn("assembleArm64Release")
    from(layout.buildDirectory.file("outputs/apk/arm64/release/app-arm64-release.apk"))
    into(openSourceOutputDir)
    rename { "AECardTools-v$versionLabel-arm64-release.apk" }
}

val collectUniversalReleaseApk by tasks.registering(Copy::class) {
    dependsOn("assembleUniversalRelease")
    from(layout.buildDirectory.file("outputs/apk/universal/release/app-universal-release.apk"))
    into(openSourceOutputDir)
    rename { "AECardTools-v$versionLabel-Universal-release.apk" }
}

tasks.register("assembleOpenSourceEdition") {
    group = "build"
    description = "Builds the recommended open-source APK set and collects them with release-ready names."
    dependsOn(collectDebugApk, collectArm64ReleaseApk, collectUniversalReleaseApk)
}
