package dev.themajorones.servermanager.data

import dev.themajorones.servermanager.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class MachineDraft(
    val id: Long? = null,
    val host: String,
    val osName: String?,
    val username: String,
    val port: Int,
    val authMode: AuthMode,
    val password: String?,
    val privateKey: String?,
    val parentIds: List<Long>,
)

class MachineRepository(
    private val credentialStore: CredentialStore,
) {
    private val mutex = Mutex()
    private val machines = MutableStateFlow<List<MachineEntity>>(emptyList())
    private val parentLinks = MutableStateFlow<List<MachineParentLinkEntity>>(emptyList())

    fun observeMachines(): Flow<List<MachineEntity>> = machines

    fun observeMachine(machineId: Long): Flow<MachineEntity?> = machines.map { list ->
        list.firstOrNull { it.id == machineId }
    }

    suspend fun getMachine(machineId: Long): MachineEntity? = machines.value.firstOrNull { it.id == machineId }

    suspend fun getAllMachines(): List<MachineEntity> = machines.value

    suspend fun getParentIds(machineId: Long): List<Long> =
        parentLinks.value.filter { it.childMachineId == machineId }.map { it.parentMachineId }

    suspend fun saveMachine(draft: MachineDraft): Long = mutex.withLock {
        val now = System.currentTimeMillis()
        val current = machines.value.toMutableList()
        val targetId = draft.id?.takeIf { it > 0 } ?: ((current.maxOfOrNull { it.id } ?: 0L) + 1L)

        val existing = current.indexOfFirst { it.id == targetId }
        val updated = MachineEntity(
            id = targetId,
            host = draft.host.trim(),
            osName = draft.osName?.trim().orEmpty().ifBlank { null },
            username = draft.username.trim(),
            port = draft.port,
            authMode = draft.authMode,
            lastCheckedAt = now,
            discoveredHostname = if (existing >= 0) current[existing].discoveredHostname else null,
            discoveredOs = if (existing >= 0) current[existing].discoveredOs else null,
            uptimeSeconds = if (existing >= 0) current[existing].uptimeSeconds else null,
            isReachable = if (existing >= 0) current[existing].isReachable else false,
            storageScopeMode = if (existing >= 0) current[existing].storageScopeMode else StorageScopeMode.ALL,
            storageTarget = if (existing >= 0) current[existing].storageTarget else null,
        )

        if (existing >= 0) {
            current[existing] = updated
        } else {
            current += updated
        }
        machines.value = current

        val existingLinks = parentLinks.value.filter { it.childMachineId != targetId }.toMutableList()
        existingLinks += draft.parentIds.filter { it != targetId }.distinct().map {
            MachineParentLinkEntity(childMachineId = targetId, parentMachineId = it)
        }
        parentLinks.value = existingLinks

        when (draft.authMode) {
            AuthMode.PASSWORD -> {
                credentialStore.clearCredentials(targetId)
                if (!draft.password.isNullOrBlank()) {
                    credentialStore.savePassword(targetId, draft.password)
                }
            }

            AuthMode.SSH_KEY -> {
                credentialStore.clearCredentials(targetId)
                if (!draft.privateKey.isNullOrBlank()) {
                    credentialStore.savePrivateKey(targetId, draft.privateKey)
                }
            }
        }

        targetId
    }

    suspend fun ensureSeedMachines(seed: List<MachineDraft>) {
        if (machines.value.isNotEmpty()) {
            return
        }
        seed.forEach { saveMachine(it) }
    }

    suspend fun updateReachability(machineId: Long, isReachable: Boolean) = mutex.withLock {
        machines.value = machines.value.map {
            if (it.id == machineId) it.copy(isReachable = isReachable, lastCheckedAt = System.currentTimeMillis()) else it
        }
    }

    suspend fun updateDiscovery(machineId: Long, hostname: String?, osName: String?, uptimeSeconds: Long?) = mutex.withLock {
        machines.value = machines.value.map {
            if (it.id == machineId) {
                it.copy(
                    discoveredHostname = hostname?.ifBlank { null } ?: it.discoveredHostname,
                    discoveredOs = osName?.ifBlank { null } ?: it.discoveredOs,
                    uptimeSeconds = uptimeSeconds ?: it.uptimeSeconds,
                )
            } else {
                it
            }
        }
    }

    suspend fun updateStorageScope(machineId: Long, scopeMode: StorageScopeMode, target: String?) = mutex.withLock {
        machines.value = machines.value.map {
            if (it.id == machineId) it.copy(storageScopeMode = scopeMode, storageTarget = target) else it
        }
    }

    fun getPassword(machineId: Long): String? = credentialStore.getPassword(machineId)

    fun getPrivateKey(machineId: Long): String? = credentialStore.getPrivateKey(machineId)
}
