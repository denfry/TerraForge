// TerraForge-Core: pure Java domain layer.
// MUST NOT depend on Paper/Bukkit, Towny, BlueMap or any SQL driver.

plugins {
    `maven-publish`
}

description = "TerraForge Core -- coordinates, projection, config, service API"

val jtsVersion = providers.gradleProperty("jts_version").get()
val jacksonVersion = providers.gradleProperty("jackson_version").get()
val caffeineVersion = providers.gradleProperty("caffeine_version").get()

dependencies {
    api("org.locationtech.jts:jts-core:$jtsVersion")
    api("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    api("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    api("com.github.ben-manes.caffeine:caffeine:$caffeineVersion")
}

// Published to mavenLocal() so dependent plugins (e.g. NewTowny) can compile against the
// GeoBoundaryProjector / GeoPointResolver API without pulling in the full TerraForge plugin.
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
