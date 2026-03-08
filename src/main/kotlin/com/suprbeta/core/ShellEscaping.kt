package com.suprbeta.core

import java.util.UUID

object ShellEscaping {
    fun singleQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun requireUuid(value: String, label: String = "UUID"): String =
        runCatching { UUID.fromString(value).toString() }
            .getOrElse { throw IllegalArgumentException("Invalid $label: $value") }
}
