plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spotless)
    `maven-publish`
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.hdrhistogram)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
}
