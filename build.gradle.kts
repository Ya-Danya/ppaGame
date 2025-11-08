import org.gradle.api.plugins.JavaPluginExtension

plugins {
    id("org.openjfx.javafxplugin") version "0.1.0" apply false
}

allprojects {
    group = "com.example"
    version = "0.7.0"
    repositories { mavenCentral() }
}

subprojects {
    plugins.apply("java")
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(17)
    }
}
