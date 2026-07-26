plugins {
    java
}

group = "com.aetheria"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // La API de Paper la aporta el servidor en runtime (incluye Gson en el classpath).
    // Los esquematicos se manejan DESPACHANDO comandos de WorldEdit/FAWE (sin dependencia
    // de compilacion): asi no hace falta enlazar con la API de WorldEdit al construir.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filteringCharset = "UTF-8"
    // Sustituye ${version} en plugin.yml por la version del proyecto.
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveBaseName.set("aetheria-plugin")
}
