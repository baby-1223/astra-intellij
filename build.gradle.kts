plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

import org.gradle.api.tasks.testing.Test
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

group = "com.astra"
version = "0.3.9"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.4")
        pluginVerifier()
        testFramework(TestFrameworkType.JUnit5)
    }
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.mozilla:rhino:1.7.15")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

val envCertificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
val envPrivateKey = providers.environmentVariable("PRIVATE_KEY")
val envPrivateKeyPassword = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
val envPublishToken = providers.environmentVariable("PUBLISH_TOKEN")

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
        }
    }

    // Signing is applied only when the CI secrets are present, so local builds stay unsigned.
    if (envCertificateChain.isPresent && envPrivateKey.isPresent) {
        signing {
            certificateChain = envCertificateChain
            privateKey = envPrivateKey
            password = envPrivateKeyPassword
        }
    }

    // Publishing to JetBrains Marketplace runs only with a token, e.g. on version tags in CI.
    if (envPublishToken.isPresent) {
        publishing {
            token = envPublishToken
            channels = listOf("default")
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
    named<Test>("test") {
        useJUnitPlatform()
    }
    register<Test>("unitTest") {
        group = "verification"
        description = "Runs plain JUnit 5 tests without IntelliJ Platform sandbox."
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform()
    }
    buildPlugin {
        archiveFileName.set("astra-intellij-${project.version}.zip")
    }
    wrapper {
        gradleVersion = "9.3.1"
        distributionType = Wrapper.DistributionType.ALL
    }
}
