import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        // Root endpoint
        get("/") {
            call.respondText(
                "SuprClaw Backend - WebSocket Proxy Active",
                ContentType.Text.Plain,
                HttpStatusCode.OK
            )
        }

        // Health check endpoint
        get("/health") {
            call.respondText(
                "OK",
                ContentType.Text.Plain,
                HttpStatusCode.OK
            )
        }
    }
}
