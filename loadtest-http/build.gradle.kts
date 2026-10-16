plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spotless)
    `maven-publish`
}

dependencies {
    api(project(":loadtest-core"))

    testImplementation(kotlin("test"))
}
