plugins {
    id("com.gradleup.shadow") version "9.3.1"
    java
}

// Compile against the Luminol 26.1.2 server jar directly. paperweight-userdev
// 2.0.0-beta.21 doesn't support the new paperclip format yet, and Folia
// officiel hasn't published a 26.x dev-bundle. The Luminol server jar bundles
// every NMS class we need (mojmap), the Bukkit/Paper API, and the Folia
// threadedregions API — so a single compileOnly file dependency does the job.
//
// To regenerate: clone https://github.com/LuminolMC/Luminol -b dev/26.1.x and
// run ./gradlew :luminol-server:jar. Output at
// luminol-server/build/libs/luminol-server-*-SNAPSHOT.jar — copy it to
// vendor/ in this project.

repositories {
    maven("https://repo.bluecolored.de/releases")
    maven("https://repo.helpch.at/releases/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    // Luminol's full server classpath (NMS mojmap + Folia threadedregions impl).
    compileOnly(files("../vendor/luminol-server-26.1.2.jar"))
    // Luminol's API jar (Bukkit + Paper API + Folia scheduler interfaces).
    compileOnly(files("../vendor/luminol-api-26.1.2.jar"))
    // Brigadier (commands) — bundled with Paper at runtime.
    compileOnly("com.mojang:brigadier:1.3.10")
    // Adventure (chat components) — bundled with Paper at runtime.
    compileOnly("net.kyori:adventure-api:4.26.1")
    compileOnly("net.kyori:adventure-text-serializer-legacy:4.26.1")
    compileOnly("net.kyori:adventure-text-serializer-plain:4.26.1")
    // Transitively referenced in luminol-api type annotations
    compileOnly("com.google.guava:guava:33.5.0-jre")
    compileOnly("com.mojang:datafixerupper:9.0.19")
    compileOnly("net.md-5:bungeecord-chat:1.21-R0.2-deprecated+build.21")

    // fastutil is shipped with Paper at runtime — provided
    compileOnly("it.unimi.dsi:fastutil:8.5.13")

    // soft-dep integrations — all compileOnly, runtime guarded by Bukkit.getPluginManager().isPluginEnabled
    compileOnly("de.bluecolored:bluemap-api:2.7.8")
    compileOnly("me.clip:placeholderapi:2.11.7")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.19-SNAPSHOT")

    // bStats — bundled into the shadow jar, relocated to avoid clashes with other plugins
    implementation("org.bstats:bstats-bukkit:3.2.1")
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
        archiveBaseName.set("RedstoneRegions")
        archiveClassifier.set("")
        // bStats is the only thing we shade — relocate it under our package
        // so it doesn't clash with other plugins shipping their own copy.
        relocate("org.bstats", "${project.group}.bstats")
        dependencies {
            include(dependency("org.bstats:.*"))
        }
    }
    jar {
        archiveBaseName.set("RedstoneRegions")
        archiveClassifier.set("plain")
    }
    // The reobfJar task came from paperweight-userdev; in 1.20.5+ runtime is
    // already mojmap, so the shadow jar is what we ship.
    assemble {
        dependsOn(shadowJar)
    }
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching(listOf("paper-plugin.yml", "plugin.yml")) {
            expand(props)
        }
    }
}
