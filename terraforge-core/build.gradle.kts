// TerraForge-Core: pure Java domain layer.
// MUST NOT depend on Paper/Bukkit, Towny, BlueMap or any SQL driver.

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
