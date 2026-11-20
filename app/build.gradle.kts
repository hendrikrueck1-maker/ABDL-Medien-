plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// versionCode = CI-Laufnummer + fester Offset, damit ein Neustart nie einen Downgrade erzeugt.
val runNumber = System.getenv("RUN_NUMBER")?.toIntOrNull() ?: 1
val versionCodeOffset = 100
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

// Room exportiert das Schema fuer Migrationstests ins Repo.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "de.hr.mediathek"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.hr.mediathek"
        minSdk = 30
        targetSdk = 35
        versionCode = runNumber + versionCodeOffset
        versionName = "0.1.$runNumber"
        manifestPlaceholders["appLabel"] = "ABDL Medien"
        buildConfigField("int", "RUN_NUMBER", "$runNumber")
        buildConfigField("String", "UPDATE_REPO", "\"hendrikrueck1-maker/ABDL-Medien-\"")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Eigene applicationId und damit eigene Datenbank, parallel zur Release-App installierbar.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "ABDL Medien (Debug)"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.room.paging)
    implementation(libs.pdfbox.android)
    implementation(libs.snakeyaml.engine)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    // Audit: echte FTS4-Engine fuer den Such-Fuzz-Test (nur Testcode, Branch audit/tests)
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
