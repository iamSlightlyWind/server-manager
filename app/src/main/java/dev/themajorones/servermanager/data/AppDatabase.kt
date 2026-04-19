package dev.themajorones.servermanager.data

enum class AuthMode {
    PASSWORD,
    SSH_KEY,
}

enum class StorageScopeMode {
    ALL,
    PER_DEVICE,
    PER_MOUNT,
}

data class MachineEntity(
    val id: Long = 0,
    val host: String,
    val osName: String? = null,
    val username: String,
    val port: Int = 22,
    val authMode: AuthMode = AuthMode.PASSWORD,
    val storageScopeMode: StorageScopeMode = StorageScopeMode.ALL,
    val storageTarget: String? = null,
    val discoveredHostname: String? = null,
    val discoveredOs: String? = null,
    val uptimeSeconds: Long? = null,
    val isReachable: Boolean = false,
    val lastCheckedAt: Long? = null,
)

data class MachineParentLinkEntity(
    val childMachineId: Long,
    val parentMachineId: Long,
)
