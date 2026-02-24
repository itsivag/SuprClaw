package com.suprbeta.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.server.application.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseSchemaRepository(
    private val supabase: SupabaseClient,
    application: Application
) {
    private val logger = application.log

    suspend fun createUserSchema(userId: String, vpsGatewayUrl: String, gatewayToken: String) {
        val schemaName = schemaName(userId)
        supabase.postgrest.rpc("create_user_schema", buildJsonObject {
            put("p_schema_name", schemaName)
        })
        supabase.postgrest.rpc("insert_user_droplet", buildJsonObject {
            put("p_schema_name", schemaName)
            put("p_vps_gateway_url", vpsGatewayUrl)
            put("p_gateway_token", gatewayToken)
        })
        logger.info("✅ Created Supabase schema + registered droplet: $schemaName for userId=$userId")
    }

    suspend fun deleteUserSchema(userId: String) {
        val schemaName = schemaName(userId)
        supabase.postgrest.rpc("drop_user_schema", buildJsonObject {
            put("p_schema_name", schemaName)
        })
        supabase.postgrest.rpc("delete_user_droplet", buildJsonObject {
            put("p_schema_name", schemaName)
        })
        logger.info("🗑️ Dropped Supabase schema + removed user_droplets row: $schemaName for userId=$userId")
    }

    private fun schemaName(userId: String) = "user_" + userId.replace(Regex("[^a-zA-Z0-9]"), "_")
}
