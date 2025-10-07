plugins {
    id("net.neoforged.moddev") version "2.0.112"
    id("java")
}

group = "gay.mona"
version = "1.0.0"

neoForge {
    // Look for versions on https://projects.neoforged.net/neoforged/neoform
    neoFormVersion = "1.21.9-20250930.151910"

    parchment {
        minecraftVersion = "1.21.9"
        mappingsVersion = "2025.10.05"
    }

    accessTransformers.from("src/main/accesstransformer.cfg")

    runs {
        create("data") {
            mainClass = "gay.mona.model.converter.Main"
            client()

            programArgument("--input")
            programArgument(project.layout.projectDirectory.dir("src/input").asFile.toPath().toAbsolutePath().toString())
            programArgument("--output")
            programArgument(project.layout.projectDirectory.dir("out").asFile.toPath().toAbsolutePath().toString())
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}