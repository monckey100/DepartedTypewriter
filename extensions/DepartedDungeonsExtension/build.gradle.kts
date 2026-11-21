repositories {
    mavenLocal()
}

dependencies {
    // The run API: where a map coordinate stands in a run's copy, and the scene service a run asks for.
    compileOnly(files("C:/Users/Admin/Documents/minecraftplugins/DepartedCore/DepartedDungeons/target/DepartedDungeons-0.1.0-SNAPSHOT.jar"))
}

typewriter {
    namespace = "departed"

    extension {
        name = "DepartedDungeons"
        shortDescription = "Pages play in a dungeon run's copy of the map."
        description = """
            |A dungeon run of DepartedDungeons plays in a copy of its map placed elsewhere. This extension makes every
            |position a page writes in the player's world (the player world position variable, recorded entity
            |cinematics) land in the copy of the run the player is in, and plays a dungeon's scene steps as ordinary
            |cinematic pages.
        """.trimMargin()
        engineVersion = file("../../version.txt").readText().trim()
        channel = com.typewritermc.moduleplugin.ReleaseChannel.NONE

        paper {
            dependency("DepartedDungeons")
        }
    }
}

kotlin {
    jvmToolchain(21)
}
