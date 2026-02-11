package com.suprbeta

import io.github.cdimascio.dotenv.dotenv
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CreateDropletInput(
    val name: String,
    val region: String
)

@Serializable
data class DropletRequest(
    val name: String,
    val region: String,
    val size: String,
    val image: String,
    val user_data: String? = null,
    val tags: List<String>? = null
)

fun Application.configureRouting() {
    val dotenv = dotenv()
    val doApiKey = dotenv["DIGITALOCEAN_API_KEY"]
    val googleApiKey = dotenv["GOOGLE_API_KEY"]
    val aiProvider = dotenv["AI_PROVIDER"] ?: "gemini"
    val aiModel = dotenv["AI_MODEL"] ?: "gemini-2.0-flash-exp"
    val snapshotId = dotenv["OPENCLAW_SNAPSHOT_ID"] ?: "ubuntu-24-04-x64"

    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }

    routing {
        post("/hello") {
            try {
                val input = call.receive<CreateDropletInput>()

                // Generate user_data script from template
                val userData = loadUserDataScript(input.name, googleApiKey, aiProvider, aiModel)

                val dropletRequest = DropletRequest(
                    name = input.name,
                    region = input.region,
                    size = "s-1vcpu-2gb",
                    image = snapshotId,
                    user_data = userData,
                    tags = listOf("openclaw", "bot:${input.name}")
                )

                val response: HttpResponse = client.post("https://api.digitalocean.com/v2/droplets") {
                    header("Authorization", "Bearer $doApiKey")
                    contentType(ContentType.Application.Json)
                    setBody(dropletRequest)
                }

                val responseBody = response.bodyAsText()
                call.respondText(responseBody, ContentType.Application.Json, response.status)
            } catch (e: Exception) {
                call.respondText("Error calling DigitalOcean API: ${e.message}", status = HttpStatusCode.InternalServerError)
            }
        }
    }
}

fun loadUserDataScript(botName: String, googleApiKey: String?, aiProvider: String, aiModel: String): String {
    // Load the template from resources
    val template = object {}.javaClass.getResourceAsStream("/openclaw-setup.sh")
        ?.bufferedReader()
        ?.readText()
        ?: throw IllegalStateException("openclaw-setup.sh not found in resources")

    // Replace placeholders with actual values
    return template
        .replace("{{BOT_NAME}}", botName)
        .replace("{{GOOGLE_API_KEY}}", googleApiKey ?: "")
        .replace("{{AI_PROVIDER}}", aiProvider)
        .replace("{{AI_MODEL}}", aiModel)
}
