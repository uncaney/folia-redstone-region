plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("com.gradleup.shadow") version "8.3.5"
}

repositories {
    maven("https://repo.bluecolored.de/releases")
    maven("https://repo.helpch.at/releases/")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    paperweight.foliaDevBundle("1.21.11-R0.1-SNAPSHOT")
    // fastutil is shipped with Paper at runtime — provided
    compileOnly("it.unimi.dsi:fastutil:8.5.13")

    // soft-dep integrations — all compileOnly, runtime guarded by Bukkit.getPluginManager().isPluginEnabled
    compileOnly("de.bluecolored:bluemap-api:2.7.8")
    compileOnly("me.clip:placeholderapi:2.11.7")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.19-SNAPSHOT")
}

paperweight {
    reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}

tasks {
    assemble {
        dependsOn(reobfJar)
    }
    shadowJar {
        archiveClassifier.set("")
        // fastutil is provided by Paper at runtime; do not relocate or shade
    }
    jar {
        archiveClassifier.set("plain")
    }
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching(listOf("paper-plugin.yml", "plugin.yml")) {
            expand(props)
        }
    }
}
