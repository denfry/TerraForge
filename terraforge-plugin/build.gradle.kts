// TerraForge-Plugin: the Paper entry point. Produces the shaded, relocated plugin jar.

plugins {
    id("com.gradleup.shadow")
    id("com.modrinth.minotaur")
}

description = "TerraForge Plugin -- Paper bootstrap, commands, events, service wiring"

val paperVersion = providers.gradleProperty("paper_version").get()
val minecraftVersion = providers.gradleProperty("minecraft_version").get()

dependencies {
    implementation(project(":terraforge-core"))
    implementation(project(":terraforge-geo"))
    implementation(project(":terraforge-generator"))
    implementation(project(":terraforge-towny"))
    implementation(project(":terraforge-bluemap"))

    implementation("org.bstats:bstats-bukkit:3.0.2")

    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    testCompileOnly("io.papermc.paper:paper-api:$paperVersion")
    testRuntimeOnly("io.papermc.paper:paper-api:$paperVersion")
}

// --- Build provenance ------------------------------------------------------
// A shipped jar that names no commit cannot be traced back to reviewed source, which is exactly how
// a jar built from an uncommitted working tree reached production unnoticed. Record the commit and a
// dirty flag -- and nothing else: the root build script pins archives to reproducible output, so a
// build timestamp would make every rebuild differ.
val repoRoot = rootProject.layout.projectDirectory.asFile

// providers.exec (not "...".execute() or project.exec) so this stays configuration-cache compatible.
fun gitOutput(vararg arguments: String): String? {
    val output = providers.exec {
        workingDir = repoRoot
        commandLine(listOf("git") + arguments)
        isIgnoreExitValue = true
    }
    // git may be missing from PATH, or this may be a source tarball with no .git directory. Neither
    // is a broken build, so provenance degrades to "unknown" instead of failing.
    return runCatching {
        if (output.result.get().exitValue == 0) output.standardOutput.asText.get() else null
    }.getOrNull()
}

val gitCommit = gitOutput("rev-parse", "HEAD")?.trim()?.takeIf { it.length == 40 } ?: "unknown"
// Non-empty porcelain output means tracked changes or untracked files -- either way the tree does
// not match gitCommit. A failed `git status` stays "unknown" rather than claiming a clean tree.
val gitDirty = gitOutput("status", "--porcelain")?.let { it.isNotBlank().toString() } ?: "unknown"

tasks.named<ProcessResources>("processResources") {
    val props = mapOf(
        "version" to project.version.toString(),
        "apiVersion" to minecraftVersion.substringBeforeLast('.'),
        "commit" to gitCommit,
        "dirty" to gitDirty,
    )
    inputs.properties(props)
    filesMatching(listOf("plugin.yml", "paper-plugin.yml", "terraforge-build.properties")) { expand(props) }
}

tasks.shadowJar {
    archiveBaseName.set("TerraForge")
    archiveClassifier.set("")

    from(rootProject.file("LICENSE")) {
        into("META-INF/terraforge")
    }

    // Relocate every shaded library so TerraForge cannot clash with other plugins.
    listOf(
        "org.locationtech.jts" to "jts",
        "com.fasterxml.jackson" to "jackson",
        "com.github.benmanes.caffeine" to "caffeine",
        "com.zaxxer.hikari" to "hikari",
        "org.yaml.snakeyaml" to "snakeyaml",
        "org.sqlite" to "sqlite",
        "org.bstats" to "bstats",
    ).forEach { (pkg, alias) -> relocate(pkg, "dev.terraforge.libs.$alias") }

    // Service files are merged by the transformer, so duplicates must reach it.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    // Leaf/Paper's remapper rejects duplicate ZIP entries. Dependency licence notices are not runtime
    // resources, while service descriptors above are merged deliberately.
    exclude(
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/maven/**",
    )
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}

modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set(providers.environmentVariable("MODRINTH_PROJECT_ID"))
    versionNumber.set(project.version.toString())
    versionName.set("TerraForge ${project.version}")
    versionType.set(if (project.version.toString().contains('-')) "beta" else "release")
    uploadFile.set(tasks.shadowJar)
    gameVersions.add(minecraftVersion)
    loaders.add("paper")
    changelog.set(providers.provider {
        val header = "## [${project.version}]"
        val source = rootProject.file("CHANGELOG.md").readText()
        require(source.contains(header)) {
            "CHANGELOG.md has no release section for ${project.version}"
        }
        source.substringAfter(header).substringBefore("\n## [").trim()
    })
}
