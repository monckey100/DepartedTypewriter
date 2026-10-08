repositories {
    mavenLocal()
    maven("https://mvn.lumine.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.lumine:Mythic-Dist:5.12.0")
    compileOnly("me.clip:placeholderapi:2.11.6")
    // Hard: collect/deliver objectives count and take items only through DepartedItemCore.
    compileOnly("dev.departed:DepartedItemCore:0.1.0") { isTransitive = false }
    compileOnly(project(":BasicExtension"))
    compileOnly(files("C:/Users/Admin/Desktop/Minecraft Mods/GIT SERVER/DepartedServer/jars/DepartedNPC-1.0.0.jar"))
    compileOnly(files("C:/Users/Admin/Desktop/Minecraft Mods/GIT SERVER/DepartedServer/jars/MythicDungeons-2.0.1-SNAPSHOT.jar"))
    compileOnly(files("C:/Users/Admin/Desktop/Minecraft Mods/GIT SERVER/DepartedServer/jars/ModelEngine-R4.1.0.jar"))
    compileOnly(files("C:/Users/Admin/Documents/minecraftplugins/DepartedCore/departedrpg/build/libs/DepartedRPG.jar"))
    compileOnly(files("C:/Users/Admin/Desktop/Minecraft Mods/GIT SERVER/DepartedServer/jars/DepartedLanguage-1.0.0.jar"))
}

typewriter {
    namespace = "departed"

    extension {
        name = "DepartedObjectives"
        shortDescription = "Departed quest objectives and global objective guidance."
        description = """
            DepartedObjectives adds objective-owned quest progress, completion triggers,
            DepartedRPG quest integration, and a simple %objective% PlaceholderAPI hook.
        """.trimIndent()
        engineVersion = file("../../version.txt").readText().trim()
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE

        dependencies {
            dependency("typewritermc", "Basic")
        }

        paper {
            dependency("PlaceholderAPI")
            dependency("DepartedRPG")
            dependency("MythicMobs")
            dependency("MythicDungeons")
            dependency("DepartedNPC")
            dependency("ModelEngine")
            dependency("DepartedItemCore")
        }
    }
}

kotlin {
    jvmToolchain(21)
}
