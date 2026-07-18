plugins {
    id("com.gradleup.shadow") version "9.3.1"
    java
}

repositories {
    // (no extra repos beyond mavenCentral inherited from rootProject)
}

dependencies {
    compileOnly(files("../vendor/lophine-server-26.2.jar"))
    compileOnly(files("../vendor/lophine-api-26.2.jar"))
    compileOnly("com.mojang:brigadier:1.3.10")
    compileOnly("net.kyori:adventure-api:5.2.0")
    compileOnly("net.kyori:adventure-text-serializer-legacy:5.2.0")
    compileOnly("com.google.guava:guava:33.5.0-jre")
    compileOnly("com.mojang:datafixerupper:9.0.19")
    compileOnly("net.md-5:bungeecord-chat:1.21-R0.2-deprecated+build.21")
    compileOnly(project(":plugin"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.encoding = "UTF-8"
    }
    shadowJar {
        archiveClassifier.set("")
    }
    jar {
        archiveClassifier.set("plain")
    }
    assemble {
        dependsOn(shadowJar)
    }
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
