import java.util.jar.JarFile

val kotlin_version: String by project
val logback_version: String by project
val supabase_version: String by project

plugins {
    kotlin("jvm") version "2.3.0"
    id("io.ktor.plugin") version "3.4.0"
    kotlin("plugin.serialization") version "2.3.0"
}

group = "com.suprbeta"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("org.openfolder:kotlin-asyncapi-ktor:3.1.3")
    implementation("io.ktor:ktor-server-default-headers")
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-openapi")
    implementation("io.ktor:ktor-server-routing-openapi")
    implementation("io.ktor:ktor-server-swagger")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-client-websockets")
    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")
    implementation("com.hierynomus:sshj:0.39.0")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation("com.google.firebase:firebase-admin:9.7.1")
    implementation(platform("io.github.jan-tennert.supabase:bom:$supabase_version"))
    implementation("io.github.jan-tennert.supabase:supabase-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("org.postgresql:postgresql:42.7.4")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    mergeServiceFiles()
    // ensure all grpc services are kept
    transform(com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer::class.java)

    manifest {
        attributes["Main-Class"] = application.mainClass
    }
}

/**
 * Verifies the shadow JAR contains everything required for a successful startup:
 *   - Correct Main-Class manifest entry
 *   - PostgreSQL JDBC driver class (regression for "no suitable driver" fat-JAR bug)
 *   - Kotlin stdlib
 *   - sshj transport classes (used for VPS provisioning SSH)
 *   - Merged META-INF/services (so ServiceLoader-based libs still work)
 *
 * Run with: ./gradlew verifyFatJar
 */
tasks.register("verifyFatJar") {
    dependsOn("shadowJar")
    doLast {
        val shadowJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get()
        val jar = shadowJar.archiveFile.get().asFile

        JarFile(jar).use { jarFile ->
            // 1. Main-Class manifest
            val mainClass = jarFile.manifest.mainAttributes.getValue("Main-Class")
            check(mainClass == "io.ktor.server.netty.EngineMain") {
                "Fat JAR Main-Class is '$mainClass', expected 'io.ktor.server.netty.EngineMain'"
            }

            // 2. PostgreSQL JDBC driver (regression: must be loadable without DriverManager)
            checkNotNull(jarFile.getJarEntry("org/postgresql/Driver.class")) {
                "Fat JAR is missing org/postgresql/Driver.class — JDBC will fail at runtime"
            }

            // 3. Kotlin stdlib
            checkNotNull(
                jarFile.getJarEntry("kotlin/KotlinVersion.class")
                    ?: jarFile.getJarEntry("kotlin/Unit.class")
            ) { "Fat JAR appears to be missing the Kotlin stdlib" }

            // 4. sshj (used for VPS SSH provisioning and self-hosted Supabase SSH)
            checkNotNull(jarFile.getJarEntry("net/schmizz/sshj/SSHClient.class")) {
                "Fat JAR is missing net/schmizz/sshj/SSHClient.class — SSH provisioning will fail"
            }

            // 5. Merged services file — confirms mergeServiceFiles() ran
            val services = jarFile.getJarEntry("META-INF/services/java.sql.Driver")
            if (services != null) {
                val content = jarFile.getInputStream(services).bufferedReader().readText()
                check("org.postgresql" in content) {
                    "META-INF/services/java.sql.Driver exists but does not list org.postgresql driver"
                }
            }
            // Note: absence of the services file is acceptable because we bypass DriverManager
            // and use org.postgresql.Driver() directly.
        }

        println("✅ Fat JAR verified: ${jar.name} (${jar.length() / 1024}KB)")
        println("   Main-Class : io.ktor.server.netty.EngineMain")
        println("   PostgreSQL : org/postgresql/Driver.class ✓")
        println("   Kotlin     : kotlin stdlib ✓")
        println("   sshj       : net/schmizz/sshj/SSHClient.class ✓")
    }
}
