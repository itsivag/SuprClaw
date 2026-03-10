package com.suprbeta.connector

import com.suprbeta.firebase.authenticated
import com.suprbeta.firebase.firebaseUserKey
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureConnectorRoutes(connectorService: ConnectorService) {
    routing {
        authenticated {
            route("/api/connectors") {
                get {
                    val user = call.attributes[firebaseUserKey]
                    val connectors = connectorService.listConnectors(user.uid)
                    call.respond(HttpStatusCode.OK, ConnectorListResponse(connectors))
                }

                post("/apps/session") {
                    val user = call.attributes[firebaseUserKey]
                    try {
                        val session = connectorService.startConnectorSession(user.uid)
                        call.respond(HttpStatusCode.OK, session)
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "Connector session flow is not configured")))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to start connector session for user ${user.uid}", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to start connector session")))
                    }
                }

                get("/apps/session/{sessionId}") {
                    val user = call.attributes[firebaseUserKey]
                    val sessionId = call.parameters["sessionId"].orEmpty()
                    if (sessionId.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId"))
                        return@get
                    }
                    try {
                        val status = connectorService.getConnectorSessionStatus(user.uid, sessionId)
                        call.respond(HttpStatusCode.OK, status)
                    } catch (e: NoSuchElementException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Session not found")))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to get connector session status for user ${user.uid}", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to get session status")))
                    }
                }

                put("{provider}/policy") {
                    val user = call.attributes[firebaseUserKey]
                    val provider = call.parameters["provider"].orEmpty()
                    if (provider.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing provider"))
                        return@put
                    }
                    try {
                        val request = call.receive<UpdateConnectorPolicyRequest>()
                        val connector = connectorService.updateConnectorPolicy(user.uid, provider, request.allowedAgents)
                        call.respond(HttpStatusCode.OK, connector)
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                    } catch (e: NoSuchElementException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Connector not found")))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to update connector policy for user ${user.uid}", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to update policy")))
                    }
                }

                delete("{provider}") {
                    val user = call.attributes[firebaseUserKey]
                    val provider = call.parameters["provider"].orEmpty()
                    if (provider.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing provider"))
                        return@delete
                    }
                    try {
                        connectorService.disconnectConnector(user.uid, provider)
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Connector disconnected"))
                    } catch (e: Exception) {
                        call.application.log.error("Failed to disconnect connector '$provider' for user ${user.uid}", e)
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to disconnect connector")))
                    }
                }
            }
        }

        route("/api/connectors/apps") {
            get("/connect/{sessionId}") {
                val sessionId = call.parameters["sessionId"].orEmpty()
                val state = call.request.queryParameters["state"].orEmpty()
                if (sessionId.isBlank() || state.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sessionId or state"))
                    return@get
                }
                try {
                    val page = connectorService.getSessionConnectPage(sessionId, state)
                    call.respondText(
                        text = buildConnectorEmbedHtml(page),
                        contentType = ContentType.Text.Html
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Session not found")))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid session state")))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to (e.message ?: "Connector embed flow is not configured")))
                } catch (e: Exception) {
                    call.application.log.error("Failed to render connector connect page", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to render connect page")))
                }
            }

            get("/callback") {
                call.handleConnectorCallback(connectorService)
            }

            post("/callback") {
                call.handleConnectorCallback(connectorService)
            }
        }
    }
}

private fun buildConnectorEmbedHtml(page: ConnectorConnectPage): String {
    val state = page.state.jsQuote()
    val embedId = page.embedId.jsQuote()
    val callbackUrl = page.callbackUrl.jsQuote()
    val title = "Connect Apps".jsQuote()
    val sessionLabel = page.sessionId.jsQuote()
    return """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>$title</title>
  <style>
    :root {
      color-scheme: light;
      --bg: #f7fafc;
      --fg: #111827;
      --muted: #6b7280;
      --panel: #ffffff;
      --accent: #0f766e;
      --border: #d1d5db;
    }
    body {
      margin: 0;
      font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
      background: radial-gradient(circle at top, #e6fffa 0%, var(--bg) 45%);
      color: var(--fg);
      min-height: 100vh;
      display: flex;
      justify-content: center;
      align-items: flex-start;
    }
    .wrap {
      width: min(980px, 96vw);
      margin: 24px auto 40px;
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 16px;
      box-shadow: 0 10px 30px rgba(0, 0, 0, 0.06);
      padding: 20px;
    }
    h1 {
      margin: 0 0 8px;
      font-size: 22px;
    }
    p {
      margin: 0 0 14px;
      color: var(--muted);
      font-size: 14px;
    }
    #status {
      margin-bottom: 12px;
      color: var(--accent);
      font-weight: 600;
      font-size: 13px;
    }
    .meta {
      font-size: 12px;
      color: var(--muted);
      margin-bottom: 14px;
    }
    zapier-mcp {
      display: block;
      width: 100%;
      min-height: 620px;
      border: 1px solid var(--border);
      border-radius: 12px;
      overflow: hidden;
    }
  </style>
  <script src="https://mcp.zapier.com/embed/v1/mcp.js" defer></script>
</head>
<body>
  <main class="wrap">
    <h1>Connect Your Apps</h1>
    <p>Authorize app access to enable connector tools for your workspace.</p>
    <div id="status">Initializing secure connection…</div>
    <div class="meta">Session: $sessionLabel</div>
    <zapier-mcp id="zapierMcp" embed-id="$embedId"></zapier-mcp>
  </main>
  <script>
    (function () {
      const statusEl = document.getElementById("status");
      const mcpEl = document.getElementById("zapierMcp");
      const callbackUrl = "$callbackUrl";
      const state = "$state";
      let finalized = false;

      function setStatus(message, isError) {
        statusEl.textContent = message;
        statusEl.style.color = isError ? "#b91c1c" : "#0f766e";
      }

      function finalizeWithServer(mcpServerUrl) {
        if (finalized) return;
        finalized = true;
        const url = callbackUrl + "?state=" + encodeURIComponent(state) + "&mcpServerUrl=" + encodeURIComponent(mcpServerUrl);
        window.location.href = url;
      }

      mcpEl.addEventListener("mcp-server-url", function (event) {
        const mcpServerUrl = event && event.detail && (event.detail.serverUrl || event.detail);
        if (!mcpServerUrl) {
          setStatus("Authorization completed but no MCP server URL was returned.", true);
          return;
        }
        setStatus("Authorization completed. Finalizing…", false);
        finalizeWithServer(mcpServerUrl);
      });

      mcpEl.addEventListener("error", function () {
        setStatus("Failed to initialize app connection. Please retry.", true);
      });

      setStatus("Continue in the embedded flow to connect apps.", false);
    })();
  </script>
</body>
</html>
""".trimIndent()
}

private fun String.jsQuote(): String = buildString(length + 8) {
    for (ch in this@jsQuote) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\'' -> append("\\'")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.handleConnectorCallback(connectorService: ConnectorService) {
    val query = request.queryParameters
    val form: Parameters = runCatching { receiveParameters() }.getOrDefault(Parameters.Empty)
    val state = query["state"] ?: form["state"] ?: ""
    val mcpServerUrl = query["mcpServerUrl"]
        ?: query["serverUrl"]
        ?: query["mcp_server_url"]
        ?: query["server_url"]
        ?: form["mcpServerUrl"]
        ?: form["serverUrl"]
        ?: form["mcp_server_url"]
        ?: form["server_url"]

    if (state.isBlank()) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing state"))
        return
    }

    try {
        val callback = connectorService.finalizeConnectorCallback(state, mcpServerUrl)
        val redirect = connectorService.callbackRedirectUrl(callback)
        if (redirect != null) {
            respondRedirect(redirect, permanent = false)
        } else {
            respond(HttpStatusCode.OK, callback)
        }
    } catch (e: NoSuchElementException) {
        respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "Session not found")))
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid callback request")))
    } catch (e: Exception) {
        application.log.error("Failed to finalize connector callback", e)
        respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to finalize callback")))
    }
}
