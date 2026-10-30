plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json) // SimulatorConfig/MovementSpec JSON for :core-data ActiveSimRepository
    testImplementation(libs.junit)
}
