plugins {
    id("com.gradleup.shadow") version "9.6.1" apply false
}

val javaVersion = providers.gradleProperty("java_version").get()

allprojects {
    group = rootProject.property("group") as String
    version = rootProject.property("version") as String
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion.toInt()))
        withSourcesJar()
    }

    repositories {
        // Repositories are declared centrally in settings.gradle.kts.
    }

    dependencies {
        val junitVersion = rootProject.property("junit_version") as String
        val mockitoVersion = rootProject.property("mockito_version") as String
        val assertjVersion = rootProject.property("assertj_version") as String

        "testImplementation"(platform("org.junit:junit-bom:$junitVersion"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core:$assertjVersion")
        "testImplementation"("org.mockito:mockito-core:$mockitoVersion")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(javaVersion.toInt())
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-parameters"))
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
