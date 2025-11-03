import java.util.Properties

plugins { application }

dependencies {
    implementation(project(":common"))
    implementation("org.postgresql:postgresql:42.7.4")
}

application { mainClass.set("com.example.paperfx.server.ServerMain") }

/** Simple .env loader for gradle :server:run (host launch). */
fun loadDotEnv(): Map<String, String> {
    val f = rootProject.file(".env")
    if (!f.exists()) return emptyMap()
    val props = Properties()
    f.forEachLine { line ->
        val s = line.trim()
        if (s.isBlank() || s.startsWith("#") || !s.contains("=")) return@forEachLine
        val (k, v) = s.split("=", limit = 2)
        props[k.trim()] = v.trim()
    }
    return props.entries.associate { it.key.toString() to it.value.toString() }
}

tasks.withType<JavaExec>().configureEach {
    val env = loadDotEnv()
    environment("DB_URL", env["DB_URL"] ?: System.getenv("DB_URL") ?: "jdbc:postgresql://127.0.0.1:5433/paperfx")
    environment("DB_USER", env["DB_USER"] ?: System.getenv("DB_USER") ?: "paperfx")
    environment("DB_PASS", env["DB_PASS"] ?: System.getenv("DB_PASS") ?: "paperfx")
}
