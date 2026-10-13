plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.spotless) apply false
}

val diiaMavenUrl = providers.gradleProperty("diiaMavenUrl").orElse("https://gitlab.diia.org.ua/api/v4/projects/193/packages/maven")
val mavenToken = providers.systemProperty("mavenToken").orElse(providers.environmentVariable("MAVEN_TOKEN"))
val javaVersion =
    libs.versions.java
        .get()
        .toInt()
val ktlintVersion = libs.versions.ktlint.get()
val junitBom = libs.junit.bom
val junitLauncher = libs.junit.platform.launcher

System.getenv("CURRENT_VERSION")?.let { ciVersion -> allprojects { version = ciVersion } }

subprojects {
    repositories {
        mavenCentral()
    }

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }
        dependencies {
            "testImplementation"(platform(junitBom))
            "testRuntimeOnly"(junitLauncher)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(javaVersion)
            explicitApi()
        }
    }

    pluginManager.withPlugin("com.diffplug.spotless") {
        extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            kotlin { ktlint(ktlintVersion) }
            kotlinGradle { ktlint(ktlintVersion) }
        }
    }

    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            if (!pluginManager.hasPlugin("java-gradle-plugin")) {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["java"])
                    }
                }
            }
            repositories {
                maven {
                    name = "diia"
                    url = uri(diiaMavenUrl.get())
                    credentials(HttpHeaderCredentials::class.java) {
                        name = "Private-Token"
                        value = mavenToken.orNull
                    }
                    authentication {
                        create<HttpHeaderAuthentication>("header")
                    }
                }
            }
        }
    }
}
