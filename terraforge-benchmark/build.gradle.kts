// TerraForge-Benchmark: JMH harness for the chunk generation pipeline.
// Not shipped with the plugin; run manually (see docs/performance.md).

description = "TerraForge Benchmark -- JMH measurements for the generation pipeline"

val jmhVersion = providers.gradleProperty("jmh_version").get()

dependencies {
    implementation(project(":terraforge-core"))
    implementation(project(":terraforge-geo"))
    implementation(project(":terraforge-generator"))

    // The geography benchmark builds and loads a real SQLite database; the driver is not exposed
    // by terraforge-geo's implementation dependency.
    runtimeOnly("org.xerial:sqlite-jdbc:${providers.gradleProperty("sqlite_version").get()}")

    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the TerraForge JMH benchmarks"
    mainClass.set("org.openjdk.jmh.Main")
    classpath = project.the<SourceSetContainer>()["main"].runtimeClasspath
}
