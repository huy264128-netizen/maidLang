plugins {
    kotlin("multiplatform") version "2.4.21"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val hostOs = System.getProperty("os.name")
val hostArch = System.getProperty("os.arch")
val isArm64 = hostArch == "aarch64" || hostArch == "arm64"
val isX64 = hostArch == "amd64" || hostArch == "x86_64"

kotlin {
    val hostTarget = when {
        hostOs == "Linux" && isArm64 -> linuxArm64("host")
        hostOs == "Linux" && isX64 -> linuxX64("host")
        hostOs == "Mac OS X" && isArm64 -> macosArm64("host")
        hostOs.startsWith("Windows") && isX64 -> mingwX64("host")
        else -> error("Unsupported Kotlin/Native build host: $hostOs ($hostArch)")
    }
    hostTarget.binaries.executable {
        baseName = "maidLang"
        entryPoint = "main"
    }

    sourceSets.commonMain {
        kotlin.srcDir("src/main/kotlin")
    }
}
