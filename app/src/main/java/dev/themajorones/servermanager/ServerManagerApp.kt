package dev.themajorones.servermanager

import android.app.Application
import dev.themajorones.servermanager.data.AuthMode
import dev.themajorones.servermanager.data.MachineDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ServerManagerApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        if (BuildConfig.DEBUG) {
            appScope.launch {
                container.machineRepository.ensureSeedMachines(
                    listOf(
                        MachineDraft(
                            host = "100.115.157.114",
                            osName = null,
                            username = "slightlywind",
                            port = 22,
                            authMode = AuthMode.PASSWORD,
                            password = "301203",
                            privateKey = null,
                            parentIds = emptyList(),
                        ),
                        MachineDraft(
                            host = "100.71.26.123",
                            osName = null,
                            username = "slightlywind",
                            port = 22,
                            authMode = AuthMode.PASSWORD,
                            password = "301203",
                            privateKey = null,
                            parentIds = emptyList(),
                        ),
                        MachineDraft(
                            host = "100.70.48.57",
                            osName = null,
                            username = "slightlywind",
                            port = 22,
                            authMode = AuthMode.PASSWORD,
                            password = "301203",
                            privateKey = null,
                            parentIds = emptyList(),
                        ),
                    ),
                )
            }
        }
    }
}
