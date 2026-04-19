package dev.themajorones.servermanager

import android.content.Context
import dev.themajorones.servermanager.data.MachineRepository
import dev.themajorones.servermanager.metrics.MetricsCollector
import dev.themajorones.servermanager.network.RouteResolver
import dev.themajorones.servermanager.network.SmartRouter
import dev.themajorones.servermanager.security.CredentialStore
import dev.themajorones.servermanager.services.ServicesRepository
import dev.themajorones.servermanager.ssh.RemoteCommandExecutor
import dev.themajorones.servermanager.ssh.SshSessionManager

class AppContainer(context: Context) {
    private val credentialStore = CredentialStore(context)

    val machineRepository = MachineRepository(credentialStore)
    val sshSessionManager = SshSessionManager(credentialStore, context.cacheDir)
    val smartRouter = SmartRouter(context, machineRepository)

    private val commandExecutor: RemoteCommandExecutor = sshSessionManager
    private val routeResolver: RouteResolver = smartRouter

    val metricsCollector = MetricsCollector(routeResolver, commandExecutor)
    val servicesRepository = ServicesRepository(commandExecutor)
}
