plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spotless)
    `maven-publish`
}

dependencies {
    api(project(":loadtest-core"))

    api(platform(libs.grpc.bom))
    api(libs.grpc.api)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.protobuf.java)
    api(libs.kotlinx.serialization.json)
    runtimeOnly(libs.grpc.netty.shaded)

    testImplementation(kotlin("test"))
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.grpc.services)
}
