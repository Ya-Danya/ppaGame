plugins {
    application
    id("org.openjfx.javafxplugin")
}

dependencies {
    implementation(project(":common"))
}

javafx {
    version = "17.0.10"
    modules = listOf("javafx.controls", "javafx.graphics")
}

application { mainClass.set("com.example.paperfx.client.ClientMain") }
