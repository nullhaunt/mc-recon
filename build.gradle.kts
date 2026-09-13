import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "dev.mcrecon"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
    // Kotlin 2.2 emits Java 24 classfiles while using the requested JDK 25 toolchain.
    // Those classfiles run normally on Java 25; the CLI does not need Java 25 APIs yet.
    compilerOptions.jvmTarget.set(JvmTarget.JVM_24)
}

java {
    sourceCompatibility = JavaVersion.VERSION_24
    targetCompatibility = JavaVersion.VERSION_24
}

application {
    mainClass.set("dev.mcrecon.cli.MainKt")
    applicationName = "mc-recon"
}

tasks.test {
    useJUnitPlatform()
}
