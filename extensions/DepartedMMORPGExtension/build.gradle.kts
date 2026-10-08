//plugins {
//    kotlin("jvm") version "2.2.10"
//    id("com.typewritermc.module-plugin") version "2.0.0"
//}

// Replace with your own information
group = "gg.departed"
version = "0.0.1"

repositories {
    mavenLocal()
}
dependencies {
    implementation("com.mthaler:aparser:0.4.0")
    // Hard: resource_node_zone loot is a DepartedItemCore ground item.
    compileOnly("dev.departed:DepartedItemCore:0.1.0") { isTransitive = false }
    compileOnly(files("C:/Users/Admin/Desktop/Minecraft Mods/GIT SERVER/DepartedServer/jars/DepartedNPC-1.0.0.jar"))
    compileOnly(files("C:/Users/Admin/Desktop/Minecraft Mods/GIT SERVER/DepartedServer/jars/DepartedRPG.jar"))
    compileOnly(files("C:/Users/Admin/Desktop/Minecraft Mods/GIT SERVER/DepartedServer/jars/DepartedLanguage-1.0.0.jar"))
}

typewriter {
    namespace = "departed"

    extension {
        name = "DepartedMMORPG"
        shortDescription = "An extension that contains quality of life features"
        description = """
            Quality of life features for Typewriter not present in basic extension.
            |Currently only features WASD dialogue option but new features will be 
            |added as the need arises.""".trimMargin()
        engineVersion = file("../../version.txt").readText().trim()

        paper {
            dependency("DepartedNPC")
            dependency("DepartedRPG")
            dependency("DepartedItemCore")
        }
    }
}
kotlin {
    jvmToolchain(21)
}
