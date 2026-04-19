package dev.themajorones.servermanager.ssh

import dev.themajorones.servermanager.config.AppConstants
import dev.themajorones.servermanager.data.MachineEntity

interface RemoteCommandExecutor {
    suspend fun execute(
        machine: MachineEntity,
        command: String,
        timeoutSeconds: Long = AppConstants.Ssh.DEFAULT_COMMAND_TIMEOUT_SECONDS,
    ): String

    suspend fun isReachable(
        machine: MachineEntity,
        timeoutSeconds: Long = AppConstants.Ssh.DEFAULT_REACHABILITY_TIMEOUT_SECONDS,
    ): Boolean

    suspend fun invalidate(machineId: Long)
}