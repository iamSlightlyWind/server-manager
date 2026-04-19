package dev.themajorones.servermanager.services

import dev.themajorones.servermanager.data.MachineEntity
import dev.themajorones.servermanager.ssh.RemoteCommandExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ServiceItem(
    val name: String,
    val loadState: String,
    val activeState: String,
    val subState: String,
)

class ServicesRepository(
    private val commandExecutor: RemoteCommandExecutor,
) {
    suspend fun listServices(machine: MachineEntity): List<ServiceItem> = withContext(Dispatchers.IO) {
        val output = commandExecutor.execute(
            machine,
            "systemctl list-units --type=service --all --no-pager --no-legend --plain",
        )
        output.lines()
            .mapNotNull { line ->
                val compact = line.trim().replace(Regex("\\s+"), " ")
                if (compact.isBlank()) return@mapNotNull null
                val parts = compact.split(" ")
                if (parts.size < 4) return@mapNotNull null
                ServiceItem(
                    name = parts[0],
                    loadState = parts[1],
                    activeState = parts[2],
                    subState = parts[3],
                )
            }
    }

    suspend fun start(machine: MachineEntity, serviceName: String) {
        commandExecutor.execute(machine, "sudo -n systemctl start $serviceName || systemctl start $serviceName")
    }

    suspend fun stop(machine: MachineEntity, serviceName: String) {
        commandExecutor.execute(machine, "sudo -n systemctl stop $serviceName || systemctl stop $serviceName")
    }

    suspend fun enable(machine: MachineEntity, serviceName: String) {
        commandExecutor.execute(machine, "sudo -n systemctl enable $serviceName || systemctl enable $serviceName")
    }

    suspend fun disable(machine: MachineEntity, serviceName: String) {
        commandExecutor.execute(machine, "sudo -n systemctl disable $serviceName || systemctl disable $serviceName")
    }
}
