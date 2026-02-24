package com.suprbeta.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.server.application.*

class SupabaseSchemaRepository(
    private val supabase: SupabaseClient,
    application: Application
) {
    private val logger = application.log

    suspend fun createUserSchema(userId: String, vpsGatewayUrl: String, gatewayToken: String) {
        val schemaName = "user_" + userId.replace(Regex("[^a-zA-Z0-9]"), "_")
        supabase.postgrest.rpc("create_user_schema", mapOf("p_schema_name" to schemaName))
        supabase.from("user_droplets").insert(
            mapOf(
                "schema_name"     to schemaName,
                "vps_gateway_url" to vpsGatewayUrl,
                "gateway_token"   to gatewayToken
            )
        )
        logger.info("✅ Created Supabase schema + registered droplet: $schemaName for userId=$userId")
    }
}
