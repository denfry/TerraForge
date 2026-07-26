rootProject.name = "TerraForge"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://repo.glaremasters.me/repository/towny/") { name = "towny" }
        maven("https://repo.bluecolored.de/releases") { name = "bluecolored" }
    }
}

include(
    "terraforge-core",
    "terraforge-geo",
    "terraforge-generator",
    "terraforge-towny",
    "terraforge-bluemap",
    "terraforge-plugin",
    "terraforge-cli",
    "terraforge-benchmark",
)
