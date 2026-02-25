package com.suprbeta.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import java.util.concurrent.ConcurrentHashMap

class UserSupabaseClientProvider {
    private val clients = ConcurrentHashMap<String, SupabaseClient>()

    fun getClient(supabaseUrl: String, serviceKey: String): SupabaseClient =
        clients.getOrPut(supabaseUrl) {
            createSupabaseClient(supabaseUrl = supabaseUrl, supabaseKey = serviceKey) {
                install(Postgrest)
            }
        }
}
