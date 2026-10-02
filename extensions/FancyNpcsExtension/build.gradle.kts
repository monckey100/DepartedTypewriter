repositories {}

dependencies {
    compileOnly(files("C:/Users/Admin/Desktop/Minecraft Mods/GIT SERVER/DepartedServer/plugins/FancyNpcs-2.9.2.337.jar"))
}

typewriter {
    namespace = "typewritermc"

    extension {
        name = "FancyNpcs"
        shortDescription = "Integrate FancyNpcs with Typewriter."
        description = """
            |The FancyNpcs Extension allows you to reference FancyNpcs NPCs,
            |start Typewriter entries when players interact with them, and control
            |their visibility or location from Typewriter actions.
        """.trimMargin()
        engineVersion = file("../../version.txt").readText().trim()
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE

        paper {
            dependency("FancyNpcs")
        }
    }
}
