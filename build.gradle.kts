import org.gradle.internal.os.OperatingSystem

val groupId = "org.hyperledger.identus"
val os: OperatingSystem = OperatingSystem.current()

plugins {
    id("com.android.library") version "8.1.4" apply false
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
    id("org.jetbrains.dokka") version "1.9.20"
    id("org.jetbrains.kotlin.kapt") version "2.1.0"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

buildscript {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        gradlePluginPortal()
    }
    dependencies {
//        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
        classpath("com.google.protobuf:protobuf-gradle-plugin:0.9.1")
        classpath("com.squareup.sqldelight:gradle-plugin:1.5.5")
        classpath("org.jetbrains.kotlinx:atomicfu-gradle-plugin:0.23.1")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

allprojects {
    this.group = groupId

    repositories {
        mavenLocal()
        mavenCentral()
        google()
        maven("https://plugins.gradle.org/m2/")
        // Needed for Kotlin coroutines that support new memory management mode
        maven {
            url = uri("https://maven.pkg.jetbrains.space/public/p/kotlinx-coroutines/maven")
        }
        maven {
//            setUrl("https://maven.pkg.github.com/hyperledger/aries-uniffi-wrappers")
//            credentials {
//                username = System.getenv("GITHUB_ACTOR")
//                password = System.getenv("GITHUB_TOKEN")
//            }
            setUrl("https://maven.pkg.github.com/LF-Decentralized-Trust-labs/aries-uniffi-wrappers")
            credentials {
                username = "abraham0929"
                password = "xxxx"
            }
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/abraham0929/sdk-kmp")
            credentials {
                username = "abraham0929"
                password = "xxxx"
            }
        }
    }

    configurations.all {
        resolutionStrategy {
            // Force JNA to specific version to avoid conflicts
            force("net.java.dev.jna:jna:5.17.0")
            eachDependency {
                if (requested.group == "org.bouncycastle") {
                    when (requested.name) {
                        "bcprov-jdk15on", "bcprov-jdk15to18" -> {
                            useTarget("org.bouncycastle:bcprov-jdk15on:1.68")
                        }
                    }
                } else if (requested.group == "com.nimbusds") {
                    // Making sure we are using the latest version of `nimbus-jose-jwt` instead if 9.25.6
                    useTarget("com.nimbusds:nimbus-jose-jwt:9.39")
                } else if (requested.group == "com.google.protobuf") {
                    // Because of Duplicate Classes issue happening on the sampleapp module
                    if (requested.name == "protobuf-javalite" || requested.name == "protobuf-java") {
                        useTarget("com.google.protobuf:protobuf-java:3.14.0")
                    }
                } else if (requested.group == "net.java.dev.jna") {
                    // Force JNA to use the latest version and ensure it's resolved as JAR, not AAR
                    useTarget("net.java.dev.jna:jna:5.17.0")
                } else if (requested.group == "org.jetbrains.kotlin") {
                    // Force consistent Kotlin version to avoid compatibility issues
                    if (requested.name.startsWith("kotlin-stdlib")) {
                        useTarget("org.jetbrains.kotlin:${requested.name}:2.1.0")
                    }
                } else if (requested.group == "org.jetbrains.kotlinx") {
                    // Force compatible kotlinx-serialization version with Kotlin 2.1.0
                    if (requested.name.startsWith("kotlinx-serialization")) {
                        useTarget("org.jetbrains.kotlinx:${requested.name}:1.6.3")
                    }
                }
            }
        }
    }
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    ktlint {
        verbose.set(true)
        outputToConsole.set(true)
        filter {
            exclude(
                "build/generated-src/**",
                "**/generated/**",
                "**/generated-src/**",
                "build/**",
                "build/generated/**"
            )
            exclude {
                it.file.path.contains("generated-src") ||
                    it.file.toString().contains("generated") ||
                    it.file.path.contains("generated")
            }
        }
    }
}

//nexusPublishing {
//    repositories {
//        sonatype {
//            nexusUrl.set(uri("https://oss.sonatype.org/service/local/"))
//            snapshotRepositoryUrl.set(uri("https://oss.sonatype.org/content/repositories/snapshots/"))
//            username.set(System.getenv("OSSRH_USERNAME"))
//            password.set(System.getenv("OSSRH_PASSWORD"))
//        }
//    }
//}
