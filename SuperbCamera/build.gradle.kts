import java.time.Instant

plugins {
    eclipse
    idea
    id("net.neoforged.moddev.legacyforge") version "2.0.91"
    id("org.jetbrains.kotlin.jvm") version "2.0.0"
}

version = "${project.property("mod_version")}-mc${project.property("minecraft_version")}"
group = "com.teamuhi.superbcamera"

base {
    archivesName.set(project.property("mod_id").toString())
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)
}

legacyForge {
    version = "${project.property("minecraft_version")}-${project.property("forge_version")}"

    parchment {
        minecraftVersion = project.property("minecraft_version").toString()
        mappingsVersion = project.property("parchment_mappings").toString()
    }

    mods {
        create(project.property("mod_id").toString()) {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        configureEach {
            gameDirectory = project.file("run")
            systemProperty("forge.logging.markers", "REGISTRIES")
            systemProperty("forge.logging.console.level", "info")
            systemProperty("geckolib.disable_examples", "true")
        }

        create("client") {
            client()
        }

        create("server") {
            server()
        }
    }
}

repositories {
    mavenCentral()
    flatDir {
        // The Superb Warfare jar is shared with SuperbEnemies instead of being duplicated.
        dir("../SuperbEnemies/libs")
    }
    maven {
        name = "Kotlin for Forge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
    }
    maven {
        name = "GeckoLib"
        url = uri("https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/")
        content {
            includeGroupByRegex("software\\.bernie.*")
            includeGroup("com.eliotlash.mclib")
        }
    }
    maven {
        url = uri("https://maven.theillusivec4.top/")
        content {
            includeGroup("top.theillusivec4.curios")
        }
    }
    maven {
        url = uri("https://jitpack.io")
        content {
            includeGroup("com.github.mcmodderanchor")
        }
    }
    maven {
        url = uri("https://maven.sighs.cc/repository/maven-public/")
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Superb Warfare dependency (optional at runtime): any ../SuperbEnemies/libs/superbwarfare*.jar is picked up
// automatically. The jar is NOT committed (see .gitignore); see SuperbEnemies/libs/README.md.
// ---------------------------------------------------------------------------------------------------------------------
val sbwJars = fileTree("../SuperbEnemies/libs") {
    include("superbwarfare*.jar")
    exclude("*-sources.jar", "*-javadoc.jar")
}
val sbwRemap = (findProperty("sbw_remap") ?: "true").toString().toBoolean()
val sbwExtraCompileDeps = (findProperty("sbw_extra_compile_deps") ?: "true").toString().toBoolean()

if (sbwJars.isEmpty) {
    logger.warn(
        "WARNING: no superbwarfare*.jar found in SuperbEnemies/libs/ (see SuperbEnemies/libs/README.md). " +
                "Compilation will fail with unresolved references until then."
    )
}

dependencies {
    implementation("thedarkcolour:kotlinforforge:${project.property("kotlin_for_forge_version")}")

    // Mixin annotation processor: generates superbcamera.refmap.json for the Camera accessor.
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")

    if (!sbwJars.isEmpty) {
        if (sbwRemap) {
            // mod* configurations remap the release (SRG) jar to the dev (Mojang/Parchment) names. They only accept
            // module coordinates (not file collections), so each jar is referenced through the libs/ flatDir repository:
            // "superbwarfare-<version>.jar" <-> local:superbwarfare:<version> (the version is whatever follows the dash).
            sbwJars.files.forEach { jar ->
                val version = jar.nameWithoutExtension.removePrefix("superbwarfare").removePrefix("-")
                val notation = if (version.isEmpty()) "local:superbwarfare" else "local:superbwarfare:$version"
                modCompileOnly(notation)
                modRuntimeOnly(notation)
            }
        } else {
            val sbwJarFiles = files(sbwJars.files.toList())
            compileOnly(sbwJarFiles)
            runtimeOnly(sbwJarFiles)
        }
    }

    // Types that appear in the signatures of the Superb Warfare classes we extend / call. The release jar bundles
    // them via Jar-in-Jar at runtime, but the Kotlin compiler needs them visible at compile time.
    if (sbwExtraCompileDeps) {
        modCompileOnly("software.bernie.geckolib:geckolib-forge-1.20.1:4.4.6")
        compileOnly("com.eliotlash.mclib:mclib:20")
        modCompileOnly("top.theillusivec4.curios:curios-forge:5.14.1+1.20.1")
        modCompileOnly("com.github.mcmodderanchor:simplebedrockmodel:2.6.0-forge-mc1.20.1")
        compileOnly("com.maydaymemory:mae:1.1.2") {
            exclude("com.google.code.findbugs", "jsr305")
            exclude("it.unimi.dsi", "fastutil")
            exclude("org.joml", "joml")
        }
    }
}

mixin {
    add(sourceSets.main.get(), "superbcamera.refmap.json")
    config("superbcamera.mixins.json")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.named<ProcessResources>("processResources") {
    val replaceProperties = mapOf(
        "minecraft_version_range" to project.property("minecraft_version_range"),
        "forge_version_range" to project.property("forge_version_range"),
        "loader_version_range" to project.property("loader_version_range"),
        "mod_id" to project.property("mod_id"),
        "mod_name" to project.property("mod_name"),
        "mod_license" to project.property("mod_license"),
        "mod_version" to project.property("mod_version"),
        "mod_authors" to project.property("mod_authors"),
        "mod_description" to project.property("mod_description"),
        "superbwarfare_version_range" to project.property("superbwarfare_version_range")
    )
    inputs.properties(replaceProperties)
    filesMatching("META-INF/mods.toml") {
        expand(replaceProperties)
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Specification-Title" to project.property("mod_id"),
            "Specification-Vendor" to project.property("mod_authors"),
            "Specification-Version" to "1",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to project.property("mod_authors"),
            "Implementation-Timestamp" to Instant.now().toString(),
            "MixinConfigs" to "superbcamera.mixins.json"
        )
    }
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}
