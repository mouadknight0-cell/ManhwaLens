import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing material lives ONLY in local.properties (gitignored) and
// the keystore is kept out of version control (.gitignore: keystore/).
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStore = keystoreProps.getProperty("release.keystore")
val hasReleaseKeystore = releaseStore != null && rootProject.file(releaseStore).exists()

android {
    namespace = "com.manhwalens.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.manhwalens.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "0.10.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseStore!!)
                storePassword = keystoreProps.getProperty("release.storePassword")
                keyAlias = keystoreProps.getProperty("release.keyAlias")
                keyPassword = keystoreProps.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // NOTE(2026-11-11, build-box constraint): the reference box is 2 vCPU / 1.9 GB RAM.
            // minifyReleaseWithR8 reproducibly OOM-kills the Gradle daemon here (even with
            // -Xmx1400m and -dontoptimize). This release is signed with the release keystore but
            // NOT minified on this box. ProGuard rules are prepared in proguard-rules.pro and
            // :app:lintVitalRelease is green. On a >=4 GB machine/CI, run:
            //   ./gradlew :app:assembleRelease -Prelease.minify=true
            val minify = (findProperty("release.minify") as String?) == "true"
            isMinifyEnabled = minify
            isShrinkResources = minify
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Safety (2026-11-12): release builds NEVER run lint automatically on this
    // low-RAM box (a lintVitalRelease run starved the whole sandbox). Lint is
    // opt-in only: ./gradlew :app:lintDebug --offline, 600 s cap.
    lint {
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Android 9+ packages .so UNcompressed by default (faster installs, bigger APK).
    // Arena conversation storage is ~120 MB, so compress native libs into the APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.translate)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
