plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    implementation(project(":common"))
}

javafx {
    version = "21.0.4"
    modules = listOf("javafx.controls", "javafx.graphics")
}

application {
    mainClass.set("com.example.paperfx.client.ClientMain")
}
