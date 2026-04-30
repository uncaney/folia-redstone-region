plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("com.gradleup.shadow") version "8.3.5"
}

dependencies {
    paperweight.foliaDevBundle("1.21.11-R0.1-SNAPSHOT")
    // fastutil is shipped with Paper at runtime — provided
    compileOnly("it.unimi.dsi:fastutil:8.5.13")
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
