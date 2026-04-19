package dev.themajorones.servermanager.ssh

import dev.themajorones.servermanager.data.MachineEntity

interface RemoteCommandExecutor {
    suspend fun execute(machine: MachineEntity, command: String, timeoutSeconds: Long = 20): String

    suspend fun isReachable(machine: MachineEntity, timeoutSeconds: Long = 4): Boolean

    suspend fun invalidate(machineId: Long)
}