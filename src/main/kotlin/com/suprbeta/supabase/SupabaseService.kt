package com.suprbeta.supabase

import io.github.cdimascio.dotenv.dotenv
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.server.application.*

class SupabaseService(
    private val application: Application
) {
    private val dotenv = dotenv {
        ignoreIfMissing = true
        directory = "."
    }

    private val supabaseUrl = dotenv["SUPABASE_URL"]
        ?: throw IllegalStateException("SUPABASE_URL not found in environment")

    private val supabaseKey = dotenv["SUPABASE_KEY"]
        ?: throw IllegalStateException("SUPABASE_KEY not found in environment")

    val client: SupabaseClient

    init {
        application.log.info("Initializing Supabase client for: $supabaseUrl")

        try {
            client = createSupabaseClient(
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseKey
            ) {
                install(Postgrest)
            }
            application.log.info("Supabase client initialized successfully")
        } catch (e: Exception) {
            application.log.error("Failed to initialize Supabase client", e)
            throw e
        }
    }

    suspend fun close() {
        try {
            application.log.info("Closing Supabase client")
            client.close()
            application.log.info("Supabase client closed")
        } catch (e: Exception) {
            application.log.error("Error closing Supabase client", e)
        }
    }
}
