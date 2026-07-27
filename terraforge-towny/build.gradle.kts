// TerraForge-Towny: optional soft-dependency bridge. Never loaded when Towny is absent.

description = "TerraForge Towny -- geographic metadata for Towny towns and nations"

val paperVersion = providers.gradleProperty("paper_version").get()
val townyVersion = providers.gradleProperty("towny_version").get()

dependencies {
    api(project(":terraforge-core"))
    implementation(project(":terraforge-geo"))
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("com.palmergames.bukkit.towny:towny:$townyVersion")
    // On the test runtime path, not only the compile path: the bridge's own classes carry Towny and
    // Bukkit types in their signatures, so verifying them needs the classes present. Towny's Town
    // itself is never constructed in a test -- its static initialiser requires a running server.
    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    testImplementation("com.palmergames.bukkit.towny:towny:$townyVersion")
    // The async store is tested against a real SQLite file; the driver is not exposed by
    // terraforge-geo's implementation dependency, so the test runtime needs it explicitly.
    testRuntimeOnly("org.xerial:sqlite-jdbc:${providers.gradleProperty("sqlite_version").get()}")
}
