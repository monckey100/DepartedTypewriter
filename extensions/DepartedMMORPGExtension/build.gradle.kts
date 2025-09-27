//plugins {
//    kotlin("jvm") version "2.2.10"
//    id("com.typewritermc.module-plugin") version "2.0.0"
//}

// Replace with your own information
group = "gg.departed"
version = "0.0.1"

repositories { }
dependencies {
    implementation("com.mthaler:aparser:0.4.0")
}

typewriter {
    namespace = "departed"

    extension {
        name = "DepartedMMORPG"
        shortDescription = "An extension that contains quality of life features"
        description = "Quality of life features for Typewriter not present in basic extension.".trimMargin()
        engineVersion = file("../../version.txt").readText().trim()

        paper()
    }
}
kotlin {
    jvmToolchain(21)
}