// TerraForge-BlueMap: optional soft-dependency bridge. Never loaded when BlueMap is absent.

description = "TerraForge BlueMap -- geographic markers on the BlueMap web map"

val paperVersion = providers.gradleProperty("paper_version").get()
val bluemapApiVersion = providers.gradleProperty("bluemap_api_version").get()

dependencies {
    api(project(":terraforge-core"))
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("de.bluecolored:bluemap-api:$bluemapApiVersion")
    testCompileOnly("io.papermc.paper:paper-api:$paperVersion")
    // On the test runtime path, not just the compile path: the marker-set mapping is exercised
    // against the real BlueMap marker classes, which are plain POJOs and need no server.
    testImplementation("de.bluecolored:bluemap-api:$bluemapApiVersion")
}
