repositories {
    mavenLocal()
}

dependencies {
    // The mob-defeated event of the native mob runtime. The jar is the plugin's own, so nothing is bundled.
    compileOnly("dev.departed:DepartedItemCore:0.1.0") { isTransitive = false }
    // The mob runtime (spawn, remove, count) and the model engine (the cinematic actor).
    compileOnly(files("C:/Users/Admin/Documents/minecraftplugins/DepartedCore/DepartedMobs/target/DepartedMobs-1.0.0.jar"))
    compileOnly("com.departed:DepartedModels:0.1.0") { isTransitive = false }
}

typewriter {
    namespace = "departed"

    extension {
        name = "DepartedMobs"
        shortDescription = "The MythicMobs entries, backed by the native DepartedMobs runtime."
        description = """
            |DepartedMobs keeps every entry the retired MythicMobs extension defined, under the same ids and fields,
            |so stored pages load unchanged. Kill events are driven by the native mob-defeated event; entries the
            |native runtime has no equivalent for load and log one warning the first time they run.
        """.trimMargin()
        engineVersion = file("../../version.txt").readText().trim()
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE

        paper {
            dependency("DepartedMobs")
            dependency("DepartedItemCore")
            dependency("DepartedModels")
        }
    }
}

kotlin {
    jvmToolchain(21)
}
