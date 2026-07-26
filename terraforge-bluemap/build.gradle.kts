// TerraForge-BlueMap: optional soft-dependency bridge. Never loaded when BlueMap is absent.

description = "TerraForge BlueMap -- geographic markers on the BlueMap web map"

val paperVersion = providers.gradleProperty("paper_version").get()
val bluemapApiVersion = providers.gradleProperty("bluemap_api_version").get()

dependencies {
    api(project(":terraforge-core"))
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("de.bluecolored:bluemap-api:$bluemapApiVersion")
    testCompileOnly("io.papermc.paper:paper-api:$paperVersion")
    testCompileOnly("de.bluecolored:bluemap-api:$bluemapApiVersion")
}
