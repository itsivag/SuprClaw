import com.suprbeta.firebase.FirestoreRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting(firestoreRepository: FirestoreRepository? = null) {
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

        // Test Firestore endpoint - writes test data to verify gRPC fix works
        get("/test-firestore") {
            if (firestoreRepository == null) {
                call.respondText(
                    "Firestore repository not initialized",
                    ContentType.Text.Plain,
                    HttpStatusCode.ServiceUnavailable
                )
                return@get
            }

            try {
                // Write test data to Firestore
                val testData = firestoreRepository.writeHealthCheck()

                call.respondText(
                    "✅ Firestore write successful!\nTest data written to health_checks/latest\nData: $testData",
                    ContentType.Text.Plain,
                    HttpStatusCode.OK
                )
            } catch (e: Exception) {
                log.error("Firestore test failed", e)
                call.respondText(
                    "❌ Firestore write failed: ${e.message}\n\nStack trace: ${e.stackTraceToString().take(500)}",
                    ContentType.Text.Plain,
                    HttpStatusCode.InternalServerError
                )
            }
        }
    }
}
