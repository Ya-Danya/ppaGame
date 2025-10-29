import org.gradle.api.tasks.JavaExec

plugins {
    application
    id("org.openjfx.javafxplugin")
}

dependencies { implementation(project(":common")) }

javafx {
    version = "17.0.17"
    modules = listOf("javafx.controls", "javafx.graphics")
}

application { mainClass.set("com.example.paperfx.client.ClientMain") }

tasks.withType<JavaExec>().configureEach {
    doFirst {
        val cp = configurations.getByName("runtimeClasspath")
        jvmArgs(
            "--module-path", cp.asPath,
            "--add-modules", "javafx.controls,javafx.graphics"
        )
    }
}
