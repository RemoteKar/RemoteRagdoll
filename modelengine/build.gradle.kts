plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.0.2"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
}

group = "org.EIQUI.RemoteRagdoll"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("26.3.build.5-alpha")
    compileOnly(fileTree("libs") { include("*.jar") })
    implementation("com.github.stephengold:jolt-jni-Windows64:1.0.0")
    runtimeOnly("com.github.stephengold:jolt-jni-Windows64:1.0.0:ReleaseSp")
    runtimeOnly("com.github.stephengold:jolt-jni-Linux64:1.0.0:ReleaseSp")
    testImplementation(platform("org.junit:junit-bom:5.10.5"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation(files("libs/packetevents-spigot-2.14.0.jar"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    test {
        useJUnitPlatform()
    }
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
        // Plugins are loaded by the server, so its JVM also needs the flag documented in PORTING.md.
        manifest.attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
    assemble {
        dependsOn(shadowJar)
    }
}
