// TerraForge-CLI: offline data preparation (DEM -> .tfdem tiles, GeoJSON -> terraforge.db).
// This is where the heavy GIS parsing lives -- the server runtime never reads GeoTIFF.

plugins {
    id("com.gradleup.shadow")
}

description = "TerraForge CLI -- offline DEM and geodata preparation"

val picocliVersion = providers.gradleProperty("picocli_version").get()
val imageioVersion = providers.gradleProperty("imageio_version").get()

dependencies {
    implementation(project(":terraforge-core"))
    implementation(project(":terraforge-geo"))

    implementation("info.picocli:picocli:$picocliVersion")
    annotationProcessor("info.picocli:picocli-codegen:$picocliVersion")

    // GeoTIFF / TIFF decoding happens offline only.
    implementation("com.twelvemonkeys.imageio:imageio-tiff:$imageioVersion")
    implementation("com.twelvemonkeys.imageio:imageio-metadata:$imageioVersion")
}

tasks.shadowJar {
    archiveBaseName.set("terraforge-cli")
    archiveClassifier.set("")
    manifest {
        attributes(
            "Main-Class" to "dev.terraforge.cli.TerraForgeCli",
            "Implementation-Title" to "TerraForge CLI",
            "Implementation-Version" to project.version.toString(),
        )
    }
    // Service files are merged by the transformer, so duplicates must reach it.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    // Licences are not runtime resources; excluding them prevents duplicate ZIP entries while
    // preserving deliberately merged service descriptors.
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
