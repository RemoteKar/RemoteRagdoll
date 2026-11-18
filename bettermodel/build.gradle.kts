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

dependencies {
    // Server and BetterModel are provided at runtime; only Jolt goes into the jar.
    compileOnly("io.papermc.paper:paper-api:26.3.build.205-stable")
    compileOnly("io.github.toxicity188:bettermodel-bukkit-api:3.5.1")
    implementation("com.github.stephengold:jolt-jni-Windows64:1.0.0")
    runtimeOnly("com.github.stephengold:jolt-jni-Windows64:1.0.0:ReleaseSp")
    runtimeOnly("com.github.stephengold:jolt-jni-Linux64:1.0.0:ReleaseSp")
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
