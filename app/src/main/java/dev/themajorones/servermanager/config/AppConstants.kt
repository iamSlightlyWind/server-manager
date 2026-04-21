package dev.themajorones.servermanager.config

object AppConstants {
    object Refresh {
        const val MACHINE_LIST_INTERVAL_MS: Long = 1_000L
        const val MACHINE_DETAIL_INTERVAL_MS: Long = 1_000L
        const val MACHINE_LIST_DISCOVERY_INTERVAL_MS: Long = 15_000L
    }

    object Graph {
        const val WINDOW_SECONDS: Int = 60
        const val HISTORY_MAX_POINTS: Int = WINDOW_SECONDS
    }

    object Routing {
        const val MAX_PARENT_DEPTH: Int = 5
        const val SOCKET_CONNECT_TIMEOUT_MS: Int = 1_500
    }

    object Ssh {
        const val DEFAULT_COMMAND_TIMEOUT_SECONDS: Long = 20L
        const val DEFAULT_REACHABILITY_TIMEOUT_SECONDS: Long = 4L
        const val MACHINE_LIST_REACHABILITY_TIMEOUT_SECONDS: Long = 1L
        const val MACHINE_LIST_DISCOVERY_TIMEOUT_SECONDS: Long = 3L
        const val SESSION_CONNECT_TIMEOUT_MS: Int = 8_000
        const val COMMAND_PREVIEW_MAX_LENGTH: Int = 120
    }

    object Metrics {
        const val DEFAULT_COMMAND_TIMEOUT_SECONDS: Long = 6L
        const val CPU_BATCH_TIMEOUT_SECONDS: Long = 7L
        const val CPU_TEMP_TIMEOUT_SECONDS: Long = 5L
        const val RAM_BATCH_TIMEOUT_SECONDS: Long = 6L
        const val GPU_NVIDIA_TIMEOUT_SECONDS: Long = 6L
        const val GPU_INVENTORY_TIMEOUT_SECONDS: Long = 6L
        const val NETWORK_SUMMARY_TIMEOUT_SECONDS: Long = 12L
        const val STORAGE_TIMEOUT_SECONDS: Long = 8L
    }
}