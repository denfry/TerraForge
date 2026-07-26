// TerraForge-Geo: GIS implementation layer (DEM tiles, spatial index, SQLite).
// MUST NOT depend on Paper/Bukkit.

description = "TerraForge Geo -- DEM store, spatial index, geographic database"

val sqliteVersion = providers.gradleProperty("sqlite_version").get()
val hikaricpVersion = providers.gradleProperty("hikaricp_version").get()

dependencies {
    api(project(":terraforge-core"))
    implementation("org.xerial:sqlite-jdbc:$sqliteVersion")
    implementation("com.zaxxer:HikariCP:$hikaricpVersion")
}
