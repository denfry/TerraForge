// TerraForge-Generator: terrain pipeline + Paper ChunkGenerator adapter.

description = "TerraForge Generator -- terrain shaping and Paper chunk generation"

val paperVersion = providers.gradleProperty("paper_version").get()

dependencies {
    api(project(":terraforge-core"))
    implementation(project(":terraforge-geo"))
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    testCompileOnly("io.papermc.paper:paper-api:$paperVersion")
    testRuntimeOnly("io.papermc.paper:paper-api:$paperVersion")
}
