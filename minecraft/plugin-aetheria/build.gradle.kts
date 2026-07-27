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
    maven("https://repo.codemc.io/repository/maven-releases/")   // packetevents
}

dependencies {
    // La API de Paper la aporta el servidor en runtime (incluye Gson en el classpath).
    // Los esquematicos se manejan DESPACHANDO comandos de WorldEdit/FAWE (sin dependencia
    // de compilacion): asi no hace falta enlazar con la API de WorldEdit al construir.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    // PacketEvents: SKINS HUMANAS de los NPC en Java 25 (LibsDisguises/ProtocolLib no van en J25).
    // Solo compileOnly: el plugin PacketEvents lo aporta el servidor en runtime.
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
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
