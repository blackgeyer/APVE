import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.7.0")
    implementation(files("libs/FoliaLib.jar"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

fun getGitHash(): String {
    return try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = stdout
        }
        stdout.toString().trim()
    } catch (e: Exception) {
        "unknown"
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("com.tcoded.folialib", "org.apve.folialib")

    manifest {
        attributes(
            "Git-Commit-Hash" to getGitHash(),
            "Build-Time" to SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(Date())
        )
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}