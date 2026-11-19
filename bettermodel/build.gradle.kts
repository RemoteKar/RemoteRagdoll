plugins {
    java
    id("com.gradleup.shadow") version "9.0.2"
}

group = "kr.gcb.ragdoll"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Every platform jolt-jni publishes a native for (the same list as the Fabric port).
// The "_fma" / "_avx2" variants are optional faster builds for the same OS and CPU; see JoltNatives.
val joltPlatforms = listOf(
    "Linux64", "Linux64_fma", "Linux_ARM32hf", "Linux_ARM64",
    "MacOSX64", "MacOSX_ARM64",
    "Windows64", "Windows64_avx2",
)

dependencies {
    // Server and BetterModel are provided at runtime; only Jolt goes into the jar.
    compileOnly("io.papermc.paper:paper-api:26.3.build.205-stable")
    compileOnly("io.github.toxicity188:bettermodel-bukkit-api:3.5.1")
    // The Java classes are the same in every platform artifact, so they are taken once;
    // each platform's native library (single precision, release) goes into the jar next to them.
    implementation("com.github.stephengold:jolt-jni-Windows64:1.0.0")
    joltPlatforms.forEach { runtimeOnly("com.github.stephengold:jolt-jni-$it:1.0.0:ReleaseSp") }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    compileJava {
        options.release = 25
        options.encoding = "UTF-8"
    }
    processResources {
        inputs.property("version", project.version)
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    jar {
        archiveClassifier.set("plain")
    }
    shadowJar {
        archiveClassifier.set("")
        exclude("module-info.class", "testjoltjni/**")
        // Jolt is a native library: the server JVM needs --enable-native-access=ALL-UNNAMED as well.
        manifest.attributes["Enable-Native-Access"] = "ALL-UNNAMED"
        manifest.attributes["paperweight-mappings-namespace"] = "mojang"
    }
    assemble {
        dependsOn(shadowJar)
    }
}
