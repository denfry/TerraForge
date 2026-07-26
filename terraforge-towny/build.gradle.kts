// TerraForge-Towny: optional soft-dependency bridge. Never loaded when Towny is absent.

description = "TerraForge Towny -- geographic metadata for Towny towns and nations"

val paperVersion = providers.gradleProperty("paper_version").get()
val townyVersion = providers.gradleProperty("towny_version").get()

dependencies {
    api(project(":terraforge-core"))
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("com.palmergames.bukkit.towny:towny:$townyVersion")
    testCompileOnly("io.papermc.paper:paper-api:$paperVersion")
    testCompileOnly("com.palmergames.bukkit.towny:towny:$townyVersion")
}
