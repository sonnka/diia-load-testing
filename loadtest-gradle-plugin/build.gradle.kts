plugins {
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.spotless)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

gradlePlugin {
    plugins {
        create("loadTest") {
            id = "ua.gov.diia.loadtest"
            implementationClass = "ua.gov.diia.loadtest.gradle.LoadTestPlugin"
            displayName = "diia-load-testing"
            description = "Adds a loadTest source set wired to diia-load-testing and a runLoadTest task."
        }
    }
}

val versionProperties by tasks.registering(WriteProperties::class) {
    destinationFile = layout.buildDirectory.file("loadtest/loadtest-plugin.properties")
    property("version", provider { project.version.toString() })
}

tasks.processResources {
    from(versionProperties)
}

dependencies {
    testImplementation(libs.junit.jupiter)
}
