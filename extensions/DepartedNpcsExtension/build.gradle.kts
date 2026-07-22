repositories {}

dependencies {
    compileOnly(files("C:/Users/Admin/Desktop/Minecraft Mods/GIT SERVER/DepartedServer/plugins/DepartedNPC-1.0.0.jar"))
    // Quest-offer gate: reads quest state from DepartedRPG and inspects entries owned by these
    // sibling extensions (console_run_command, wasd_option). All extensions share one runtime
    // classloader, so compileOnly is enough.
    compileOnly(files("C:/Users/Admin/Desktop/Minecraft Mods/GIT SERVER/DepartedServer/plugins/DepartedRPG.jar"))
    compileOnly(project(":BasicExtension"))
    compileOnly(project(":DepartedMMORPGExtension"))
}

typewriter {
    namespace = "typewritermc"

    extension {
        name = "DepartedNpcs"
        shortDescription = "Integrate DepartedNPC with Typewriter."
        description = """
            |The DepartedNpcs Extension lets you reference DepartedNPC NPCs, start Typewriter entries
            |when players interact with them, and control their visibility or location from Typewriter
            |actions — the native, packet-based replacement for the FancyNpcs integration.
        """.trimMargin()
        engineVersion = file("../../version.txt").readText().trim()
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE

        paper {
            dependency("DepartedNPC")
        }
    }
}
