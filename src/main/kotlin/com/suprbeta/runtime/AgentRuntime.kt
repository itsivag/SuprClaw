package com.suprbeta.runtime

import kotlinx.serialization.Serializable

@Serializable
enum class AgentRuntime(val wireValue: String) {
    PICOCLAW("picoclaw");

    companion object {
        fun fromWireValue(raw: String?): AgentRuntime = PICOCLAW
    }
}

object RuntimePaths {
    const val runtimeUser = "picoclaw"
    const val runtimeHome = "/home/picoclaw/.picoclaw"
    const val leadWorkspace = "$runtimeHome/workspace"
    const val picoclawConfig = "$runtimeHome/picoclaw.json"
}
