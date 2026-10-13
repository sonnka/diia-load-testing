plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "diia-load-testing"

include(":loadtest-core", ":loadtest-grpc", ":loadtest-gradle-plugin")
