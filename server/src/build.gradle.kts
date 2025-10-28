plugins { application }
dependencies { implementation(project(":common")) }
application { mainClass.set("com.example.paperfx.server.ServerMain") }
