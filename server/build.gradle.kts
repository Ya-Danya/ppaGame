plugins { application }

dependencies {
    implementation(project(":common"))
    implementation("org.postgresql:postgresql:42.7.4")
}

application { mainClass.set("com.example.paperfx.server.ServerMain") }
