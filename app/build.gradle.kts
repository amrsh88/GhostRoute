import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is read from a developer-local keystore.properties file.
// Keep that file and the private keystore out of source control and shared bundles.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.isNotEmpty()

android {
    namespace = "app.ghostroute"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.ghostroute"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.5.3"
    }

    // Pure Compose app: JDK 21 toolchain end-to-end, no appcompat/material XML libs.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        // P1-3b: About sheet reads the real versionName/versionCode from
        // BuildConfig (generated constants — no hand-maintained copy).
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Fallback: debug-signed so the APK still installs on a machine
                // without keystore.properties. Never ship this publicly.
                signingConfig = signingConfigs.getByName("debug")
                logger.lifecycle("app:release — keystore.properties missing, falling back to DEBUG signing")
            }
        }
    }

    // Robolectric (Task 4): let unit tests load app resources (strings.xml etc.),
    // and open JDK internals the Robolectric sandbox needs on JDK 17+ (FileDescriptor
    // interceptor touches jdk.internal.access.SharedSecrets).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                )
            }
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose) // Task 6: MapViewModel
    implementation(libs.maplibre) // Task 6: MapLibre map (style ledger: OpenFreeMap "dark", keyless)
    implementation(libs.coroutines.android) // MockService ticker + StateFlow state holder

    implementation(project(":engine"))
    implementation(project(":core-data"))

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test) // Task 6: Main-dispatcher control + advanceUntilIdle
}
