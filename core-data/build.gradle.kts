plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization) // brief-mandated; JSON itself arrives via :engine api
    alias(libs.plugins.ksp) // Task 8: Room compiler (catalog ruling: room 2.8.5 + ksp 2.2.0-2.0.2 pairing verified by real compile)
}

android {
    namespace = "app.ghostroute.coredata"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // P1-2b: exported schemas ride the TEST classpath assets so
    // MigrationTestHelper can build a real v1 database from the committed
    // 1.json baseline (Robolectric merges test assets with
    // includeAndroidResources=true above).
    sourceSets {
        getByName("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    // Robolectric unit tests (Task 5): load android resources + JDK opens for the sandbox.
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

// P1-2a: Room schema export — version JSONs land in /schemas, committed to VCS
// as the migration-history baseline (1.json BEFORE any v2 migration exists).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":engine")) // SimulatorConfig/MovementSpec for ActiveSimRepository
    implementation(libs.coroutines.android)
    // api (not implementation): DataStore<Preferences> is in the public repo ctor
    // signatures, so consumers (:app Task 6 tests/factories) must see the type.
    api(libs.datastore.preferences)
    implementation(libs.okhttp)

    // Task 8: Room DB v1 (Spot/Folder/Recent per DESIGN §6 data model block).
    api(libs.room.runtime) // api: entity types cross into :app's SpotsSheet via SpotRepository
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing) // P1-2b: MigrationTestHelper (schema-JSON-driven v1->v2 proof)
}