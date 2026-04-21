package dev.themajorones.servermanager.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.themajorones.servermanager.AppContainer
import dev.themajorones.servermanager.config.AppConstants
import dev.themajorones.servermanager.data.AuthMode
import dev.themajorones.servermanager.data.MachineDraft
import dev.themajorones.servermanager.data.MachineEntity
import dev.themajorones.servermanager.data.StorageScopeMode
import dev.themajorones.servermanager.metrics.CpuMetrics
import dev.themajorones.servermanager.metrics.GpuMetric
import dev.themajorones.servermanager.metrics.NetworkMetric
import dev.themajorones.servermanager.metrics.RamMetrics
import dev.themajorones.servermanager.metrics.StorageMetric
import dev.themajorones.servermanager.metrics.StorageItemMetric
import dev.themajorones.servermanager.metrics.unavailable
import dev.themajorones.servermanager.services.ServiceItem
import dev.themajorones.servermanager.ui.charts.DonutUsageChart
import dev.themajorones.servermanager.ui.charts.SparklineChart
import java.util.Locale
import kotlin.math.max
import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope

@Composable
fun ServerManagerNavHost(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "machines") {
        composable("machines") {
            MachineListScreen(
                container = container,
                onOpenSettings = { machineId -> navController.navigate("machine_settings/$machineId") },
                onOpenDetail = { machineId -> navController.navigate("machine_detail/$machineId") },
                onCreate = { navController.navigate("machine_settings/0") },
            )
        }

        composable(
            route = "machine_settings/{machineId}",
            arguments = listOf(navArgument("machineId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val machineId = backStackEntry.arguments?.getLong("machineId") ?: 0
            MachineSettingsScreen(
                container = container,
                machineId = machineId,
                onSaved = { navController.popBackStack() },
            )
        }

        composable(
            route = "machine_detail/{machineId}",
            arguments = listOf(navArgument("machineId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val machineId = backStackEntry.arguments?.getLong("machineId") ?: 0
            MachineDetailScreen(
                container = container,
                machineId = machineId,
                onOpenServices = { navController.navigate("machine_services/$machineId") },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = "machine_services/{machineId}",
            arguments = listOf(navArgument("machineId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val machineId = backStackEntry.arguments?.getLong("machineId") ?: 0
            ServicesScreen(
                container = container,
                machineId = machineId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MachineListScreen(
    container: AppContainer,
    onOpenSettings: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onCreate: () -> Unit,
) {
    val tag = "MachineListScreen"
    val machines by container.machineRepository.observeMachines().collectAsStateCompat(emptyList())
    val scope = rememberCoroutineScope()
    val lastDiscoveryAt = remember { mutableMapOf<Long, Long>() }
    val inFlight = remember { mutableMapOf<Long, Boolean>() }

    LaunchedEffect(machines.map { it.id }) {
        while (true) {
            machines.forEach { machine ->
                scope.launch {
                    if (inFlight[machine.id] == true) {
                        return@launch
                    }
                    inFlight[machine.id] = true
                    try {
                        val reachStartedAt = System.nanoTime()
                        val reachable = container.sshSessionManager.isReachable(
                            machine,
                            timeoutSeconds = AppConstants.Ssh.MACHINE_LIST_REACHABILITY_TIMEOUT_SECONDS,
                        )
                        container.machineRepository.updateReachability(machine.id, reachable)
                        val reachElapsedSeconds = (System.nanoTime() - reachStartedAt) / 1_000_000_000.0
                        Log.d(tag, "Reachability cost=${String.format(Locale.US, "%.2f", reachElapsedSeconds)}s machine=${machine.id}:${machine.host} reachable=$reachable")

                        if (reachable) {
                            val now = System.currentTimeMillis()
                            val lastAt = lastDiscoveryAt[machine.id] ?: 0L
                            val shouldRefreshDiscovery =
                                machine.discoveredHostname.isNullOrBlank() ||
                                    machine.discoveredOs.isNullOrBlank() ||
                                    machine.uptimeSeconds == null ||
                                    (now - lastAt) >= AppConstants.Refresh.MACHINE_LIST_DISCOVERY_INTERVAL_MS

                            if (shouldRefreshDiscovery) {
                                val discovery = runCatching {
                                    container.sshSessionManager.execute(
                                        machine,
                                        "echo HOSTNAME=\$(hostname); echo OS=\$(cat /etc/os-release | awk -F= '/^PRETTY_NAME=/{gsub(/\"/,\"\",\$2);print \$2}' | head -n 1); echo UPTIME=\$(cut -d. -f1 /proc/uptime)",
                                        timeoutSeconds = AppConstants.Ssh.MACHINE_LIST_DISCOVERY_TIMEOUT_SECONDS,
                                    )
                                }.getOrNull()
                                val values = discovery
                                    ?.lines()
                                    ?.mapNotNull { line ->
                                        val separator = line.indexOf('=')
                                        if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                                    }
                                    ?.toMap()
                                    .orEmpty()
                                val hostname = values["HOSTNAME"]
                                val os = values["OS"]
                                val uptime = values["UPTIME"]?.toLongOrNull()
                                container.machineRepository.updateDiscovery(machine.id, hostname, os, uptime)
                                lastDiscoveryAt[machine.id] = now
                            }
                        }
                    } finally {
                        inFlight[machine.id] = false
                    }
                }
            }
            delay(AppConstants.Refresh.MACHINE_LIST_INTERVAL_MS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Fleet", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onCreate) {
                        Icon(Icons.Default.Add, contentDescription = "Add machine")
                    }
                },
            )
        },
    ) { padding ->
        if (machines.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No machines yet")
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onCreate) { Text("Add machine") }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(machines, key = { it.id }) { machine ->
                    val online = machine.isReachable
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (online) {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(machine.discoveredHostname ?: machine.host, style = MaterialTheme.typography.titleMedium)
                                AssistChip(
                                    onClick = {},
                                    label = { Text(if (online) "Online" else "Offline") },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (online) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.errorContainer
                                        },
                                    ),
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(machine.discoveredOs ?: machine.osName ?: "OS unknown", style = MaterialTheme.typography.bodyMedium)
                            Text(formatUptime(machine.uptimeSeconds), style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { onOpenDetail(machine.id) }) { Text("Dashboard") }
                                TextButton(onClick = { onOpenSettings(machine.id) }) { Text("Configure") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MachineSettingsScreen(
    container: AppContainer,
    machineId: Long,
    onSaved: () -> Unit,
) {
    val allMachines by container.machineRepository.observeMachines().collectAsStateCompat(emptyList())
    val machine by container.machineRepository.observeMachine(machineId).collectAsStateCompat(null)

    var host by remember(machineId, machine?.id) { mutableStateOf(machine?.host.orEmpty()) }
    var osName by remember(machineId, machine?.id) { mutableStateOf(machine?.osName.orEmpty()) }
    var username by remember(machineId, machine?.id) { mutableStateOf(machine?.username.orEmpty()) }
    var port by remember(machineId, machine?.id) { mutableStateOf((machine?.port ?: 22).toString()) }
    var authMode by remember(machineId, machine?.id) { mutableStateOf(machine?.authMode ?: AuthMode.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }

    val selectedParents = remember { mutableStateListOf<Long>() }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(machineId, machine?.id) {
        if (machineId > 0) {
            selectedParents.clear()
            selectedParents.addAll(container.machineRepository.getParentIds(machineId))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (machineId == 0L) "Add Machine" else "Edit Machine", style = MaterialTheme.typography.titleLarge) })
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHost) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Connection", style = MaterialTheme.typography.titleMedium)
            }
            item {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Hostname or IP") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = osName,
                    onValueChange = { osName = it },
                    label = { Text("OS (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text("Authentication", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { authMode = AuthMode.PASSWORD },
                        enabled = authMode != AuthMode.PASSWORD,
                    ) { Text("Password") }
                    Button(
                        onClick = { authMode = AuthMode.SSH_KEY },
                        enabled = authMode != AuthMode.SSH_KEY,
                    ) { Text("SSH Key") }
                }
            }
            item {
                if (authMode == AuthMode.PASSWORD) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text("Private key text") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                    )
                }
            }
            item {
                Text("Routing Parents", style = MaterialTheme.typography.titleMedium)
            }
            items(allMachines.filter { it.id != machineId }, key = { it.id }) { candidate ->
                val checked = selectedParents.contains(candidate.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = checked,
                            onClick = {
                                if (checked) selectedParents.remove(candidate.id) else selectedParents.add(candidate.id)
                            },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = checked,
                        onCheckedChange = {
                            if (it) selectedParents.add(candidate.id) else selectedParents.remove(candidate.id)
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(candidate.discoveredHostname ?: candidate.host)
                }
            }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            if (host.isBlank() || username.isBlank()) {
                                snackbarHost.showSnackbar("Hostname/IP and username are required")
                                return@launch
                            }
                            val savedId = container.machineRepository.saveMachine(
                                MachineDraft(
                                    id = machine?.id,
                                    host = host,
                                    osName = osName,
                                    username = username,
                                    port = port.toIntOrNull() ?: 22,
                                    authMode = authMode,
                                    password = password,
                                    privateKey = privateKey,
                                    parentIds = selectedParents.toList(),
                                ),
                            )
                            snackbarHost.showSnackbar("Saved machine #$savedId")
                            onSaved()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save Machine")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MachineDetailScreen(
    container: AppContainer,
    machineId: Long,
    onOpenServices: () -> Unit,
    onBack: () -> Unit,
) {
    val machine by container.machineRepository.observeMachine(machineId).collectAsStateCompat(null)
    var refreshError by remember { mutableStateOf<String?>(null) }
    var cpu by remember { mutableStateOf<CpuMetrics?>(null) }
    var ram by remember { mutableStateOf<RamMetrics?>(null) }
    var gpus by remember { mutableStateOf<List<GpuMetric>?>(null) }
    var networks by remember { mutableStateOf<List<NetworkMetric>?>(null) }
    var storage by remember { mutableStateOf<StorageMetric?>(null) }
    var cpuLoading by remember { mutableStateOf(true) }
    var ramLoading by remember { mutableStateOf(true) }
    var gpuLoading by remember { mutableStateOf(true) }
    var networkLoading by remember { mutableStateOf(true) }
    var storageLoading by remember { mutableStateOf(true) }
    val cpuHistory = remember { mutableStateListOf<Float>() }
    val ramHistory = remember { mutableStateListOf<Float>() }
    val gpuUsageHistory = remember { mutableStateListOf<Float>() }
    val gpuVramUsedHistory = remember { mutableStateListOf<Float>() }
    val gpuVramUsageHistory = remember { mutableStateListOf<Float>() }
    val gpuPowerDrawHistory = remember { mutableStateListOf<Float>() }
    val networkDownHistory = remember { mutableStateListOf<Float>() }
    val networkUpHistory = remember { mutableStateListOf<Float>() }
    val storageHistory = remember { mutableStateListOf<Float>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(machine?.id) {
        val current = machine ?: return@LaunchedEffect

        cpu = null
        ram = null
        gpus = null
        networks = null
        storage = null
        cpuLoading = true
        ramLoading = true
        gpuLoading = true
        networkLoading = true
        storageLoading = true
        cpuHistory.clear()
        ramHistory.clear()
        gpuUsageHistory.clear()
        gpuVramUsedHistory.clear()
        gpuVramUsageHistory.clear()
        gpuPowerDrawHistory.clear()
        networkDownHistory.clear()
        networkUpHistory.clear()
        storageHistory.clear()
        refreshError = null

        while (true) {
            val cycleStartedAt = System.currentTimeMillis()
            val activeMachine = container.machineRepository.getMachine(current.id) ?: current
            val route = try {
                container.metricsCollector.resolveRoute(activeMachine)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                refreshError = error.message ?: "route resolution failed"
                if (cpu == null) cpu = unavailableCpuUi(refreshError.orEmpty())
                if (ram == null) ram = unavailableRamUi(refreshError.orEmpty())
                if (gpus == null) gpus = listOf(unavailableGpuUi(refreshError.orEmpty()))
                if (networks == null) networks = emptyList()
                if (storage == null) {
                    storage = StorageMetric(
                        scope = current.storageScopeMode,
                        used = unavailable(refreshError.orEmpty()),
                        total = unavailable(refreshError.orEmpty()),
                    )
                }
                cpuLoading = false
                ramLoading = false
                gpuLoading = false
                networkLoading = false
                storageLoading = false
                null
            }

            if (route == null) {
                val elapsed = System.currentTimeMillis() - cycleStartedAt
                delay(max(0L, AppConstants.Refresh.MACHINE_DETAIL_INTERVAL_MS - elapsed))
                continue
            }

            refreshError = null

            supervisorScope {
                val cpuJob = async {
                    cpu = try {
                        container.metricsCollector.collectCpu(activeMachine, route)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        unavailableCpuUi(error.message ?: "cpu collection failed")
                    }
                    parsePercentValue(cpu?.usage?.value)?.let { appendHistory(cpuHistory, it) }
                    cpuLoading = false
                }

                val ramJob = async {
                    ram = try {
                        container.metricsCollector.collectRam(activeMachine, route)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        unavailableRamUi(error.message ?: "ram collection failed")
                    }
                    parsePercentValue(ram?.usage?.value)?.let { appendHistory(ramHistory, it) }
                    ramLoading = false
                }

                val gpuJob = async {
                    gpus = try {
                        container.metricsCollector.collectGpus(activeMachine, route)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        listOf(unavailableGpuUi(error.message ?: "gpu collection failed"))
                    }
                    val primaryGpu = gpus?.firstOrNull()
                    parsePercentValue(primaryGpu?.usage?.value)?.let { appendHistory(gpuUsageHistory, it) }
                    parseGbValue(primaryGpu?.vram?.value)?.let { totalGb ->
                        parsePercentValue(primaryGpu?.vramUsage?.value)?.let { percent ->
                            appendHistory(gpuVramUsedHistory, totalGb * (percent / 100f))
                        }
                    }
                    parsePercentValue(primaryGpu?.vramUsage?.value)?.let { appendHistory(gpuVramUsageHistory, it) }
                    parseNumericValue(primaryGpu?.powerDraw?.value)?.let { appendHistory(gpuPowerDrawHistory, it) }
                    gpuLoading = false
                }

                val networkJob = async {
                    networks = try {
                        container.metricsCollector.collectNetworks(activeMachine, route)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        refreshError = "network: ${error.message ?: "collection failed"}"
                        emptyList()
                    }
                    val networkDownTotal = sumThroughputMbps(networks?.map { it.currentDown.value })
                    val networkUpTotal = sumThroughputMbps(networks?.map { it.currentUp.value })
                    networkDownTotal?.let {
                        appendHistory(networkDownHistory, it, maxPoints = AppConstants.Graph.HISTORY_MAX_POINTS)
                    }
                    networkUpTotal?.let {
                        appendHistory(networkUpHistory, it, maxPoints = AppConstants.Graph.HISTORY_MAX_POINTS)
                    }
                    networkLoading = false
                }

                val storageJob = async {
                    storage = try {
                        container.metricsCollector.collectStorage(activeMachine, route)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        StorageMetric(
                            scope = activeMachine.storageScopeMode,
                            used = unavailable(error.message ?: "storage collection failed"),
                            total = unavailable(error.message ?: "storage collection failed"),
                        )
                    }
                    parseStorageUsagePercent(storage)?.let { appendHistory(storageHistory, it) }
                    storageLoading = false
                }

                joinAll(cpuJob, ramJob, gpuJob, networkJob, storageJob)
            }

            val elapsed = System.currentTimeMillis() - cycleStartedAt
            delay(max(0L, AppConstants.Refresh.MACHINE_DETAIL_INTERVAL_MS - elapsed))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(machine?.discoveredHostname ?: machine?.host ?: "Machine") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onOpenServices) { Text("Services") }
                },
            )
        },
    ) { padding ->
        val current = machine
        if (current == null) {
            BoxedMessage("Machine not found", Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("Overview") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(
                            onClick = {},
                            label = { Text(if (current.isReachable) "Online" else "Offline") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (current.isReachable) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                },
                            ),
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("Refresh ~${AppConstants.Refresh.MACHINE_DETAIL_INTERVAL_MS / 1_000}s") },
                        )
                        AssistChip(onClick = {}, label = { Text(storageScopeLabel(current.storageScopeMode)) })
                    }
                }
            }

            item {
                refreshError?.let { error ->
                    SectionCard("Connectivity Notice") {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item {
                if (cpuLoading && cpu == null) {
                    SkeletonSectionCard("CPU", lines = 5)
                } else {
                    SectionCard("CPU") {
                        val cpuData = cpu ?: unavailableCpuUi("cpu unavailable")
                        val cpuValue = parsePercentValue(cpuData.usage.value) ?: 0f
                        Text(
                            text = "${String.format(Locale.US, "%.1f", cpuValue)}% live load",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SparklineChart(
                            values = if (cpuHistory.isEmpty()) listOf(0f, 0f) else cpuHistory.toList(),
                            color = MaterialTheme.colorScheme.primary,
                            min = 0f,
                            max = 100f,
                            showStats = true,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        FieldLine("Name", cpuData.name)
                        FieldLine("Threads", cpuData.threads)
                        FieldLine("Cores", cpuData.cores)
                        FieldLine("Clock", cpuData.clock)
                        FieldLine("Usage", cpuData.usage)
                        FieldLine("Temp", cpuData.temperature)
                    }
                }
            }

            item {
                if (ramLoading && ram == null) {
                    SkeletonSectionCard("RAM", lines = 4)
                } else {
                    SectionCard("RAM") {
                        val ramData = ram ?: unavailableRamUi("ram unavailable")
                        val ramValue = parsePercentValue(ramData.usage.value) ?: 0f
                        val zramValue = parsePercentValue(ramData.zram.value) ?: 0f
                        val swapValue = parsePercentValue(ramData.swap.value) ?: 0f
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RingMetricCard(
                                title = "Memory",
                                value = ramData.usage.value,
                                percent = ramValue,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            if (ramData.zram.isAvailable) {
                                RingMetricCard(
                                    title = "ZRAM",
                                    value = ramData.zram.value,
                                    percent = zramValue,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (ramData.swap.isAvailable) {
                                RingMetricCard(
                                    title = "Swap",
                                    value = ramData.swap.value,
                                    percent = swapValue,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        SparklineChart(
                            values = if (ramHistory.isEmpty()) listOf(0f, 0f) else ramHistory.toList(),
                            color = MaterialTheme.colorScheme.secondary,
                            min = 0f,
                            max = 100f,
                            showStats = true,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        FieldLine("Amount", ramData.amount)
                        FieldLine("Usage", ramData.usage)
                    }
                }
            }

            item {
                if (gpuLoading && gpus == null) {
                    SkeletonSectionCard("GPU", lines = 8)
                } else {
                    SectionCard("GPU") {
                        val gpuData = gpus ?: listOf(unavailableGpuUi("gpu unavailable"))
                        val primaryGpu = gpuData.firstOrNull()

                        if (gpuUsageHistory.isNotEmpty()) {
                            Text("GPU usage trend", style = MaterialTheme.typography.labelLarge)
                            SparklineChart(
                                values = gpuUsageHistory.toList(),
                                color = MaterialTheme.colorScheme.primary,
                                min = 0f,
                                max = 100f,
                                showStats = true,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        if (gpuVramUsageHistory.isNotEmpty()) {
                            Text("GPU VRAM usage trend", style = MaterialTheme.typography.labelLarge)
                            SparklineChart(
                                values = if (gpuVramUsedHistory.isEmpty()) listOf(0f, 0f) else gpuVramUsedHistory.toList(),
                                color = MaterialTheme.colorScheme.secondary,
                                min = 0f,
                                max = chartUpperBound(gpuVramUsedHistory),
                                showStats = true,
                                valueFormatter = { value -> String.format(Locale.US, "%.2f GB", value) },
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        if (primaryGpu?.powerDraw?.isAvailable == true || gpuPowerDrawHistory.isNotEmpty()) {
                            Text("GPU power draw trend", style = MaterialTheme.typography.labelLarge)
                            SparklineChart(
                                values = if (gpuPowerDrawHistory.isEmpty()) listOf(0f, 0f) else gpuPowerDrawHistory.toList(),
                                color = MaterialTheme.colorScheme.tertiary,
                                min = 0f,
                                max = chartUpperBound(gpuPowerDrawHistory),
                                showStats = true,
                                valueFormatter = { value -> String.format(Locale.US, "%.2fW", value) },
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        gpuData.forEachIndexed { index, gpu ->
                            Text("GPU ${index + 1}", fontWeight = FontWeight.Bold)
                            FieldLine("Name", gpu.name)
                            GpuFieldLine("Clock", gpu.clock)
                            GpuFieldLine("VRAM", gpu.vram)
                            GpuFieldLine("VRAM Usage", gpu.vramUsage)
                            GpuFieldLine("Speed", gpu.speed)
                            GpuFieldLine("Usage", gpu.usage)
                            GpuFieldLine("Power Draw", gpu.powerDraw)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            item {
                if (networkLoading && networks == null) {
                    SkeletonSectionCard("Network", lines = 5)
                } else {
                    SectionCard("Network") {
                        val networkData = networks ?: emptyList()
                        Text("Download trend", style = MaterialTheme.typography.labelLarge)
                        SparklineChart(
                            values = if (networkDownHistory.isEmpty()) listOf(0f, 0f) else networkDownHistory.toList(),
                            color = MaterialTheme.colorScheme.tertiary,
                            min = 0f,
                            max = maxThroughput(networkDownHistory, networkUpHistory),
                            showStats = true,
                            valueFormatter = { value -> formatThroughputMbps(value) },
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Upload trend", style = MaterialTheme.typography.labelLarge)
                        SparklineChart(
                            values = if (networkUpHistory.isEmpty()) listOf(0f, 0f) else networkUpHistory.toList(),
                            color = MaterialTheme.colorScheme.primary,
                            min = 0f,
                            max = maxThroughput(networkDownHistory, networkUpHistory),
                            showStats = true,
                            valueFormatter = { value -> formatThroughputMbps(value) },
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        if (networkData.isEmpty()) {
                            FieldLine("Interfaces", unavailable("none detected"))
                        }
                        networkData.forEach { net ->
                            Text(net.name, fontWeight = FontWeight.Bold)
                            if (!hideMaxBandwidthForInterface(net.name)) {
                                FieldLine("Max bandwidth", net.maxBandwidth)
                            }
                            FieldLine("Current down", net.currentDown)
                            FieldLine("Current up", net.currentUp)
                            FieldLine("IP", net.ip)
                            FieldLine("MAC", net.mac)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            item {
                SectionCard("Storage") {
                    StorageScopePicker(
                        scopeMode = current.storageScopeMode,
                        onChange = {
                            scope.launch {
                                container.machineRepository.updateStorageScope(current.id, it, null)
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (storageLoading && storage == null) {
                        SkeletonLine()
                        Spacer(modifier = Modifier.height(6.dp))
                        SkeletonLine()
                    } else {
                        val storageData = storage
                            ?: StorageMetric(
                                scope = current.storageScopeMode,
                                used = unavailable("storage unavailable"),
                                total = unavailable("storage unavailable"),
                            )
                        if (storageData.items.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                storageData.items.forEachIndexed { index, item ->
                                    StorageRingCard(
                                        item = item,
                                        index = index,
                                        totalCount = storageData.items.size,
                                    )
                                }
                            }
                        } else {
                            val usagePercent = parseStorageUsagePercent(storageData) ?: 0f
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DonutUsageChart(
                                    usedPercent = usagePercent,
                                    usedColor = MaterialTheme.colorScheme.secondary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                                Column {
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", usagePercent)}% used",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text("Trend", style = MaterialTheme.typography.labelLarge)
                                    SparklineChart(
                                        values = if (storageHistory.isEmpty()) listOf(usagePercent, usagePercent) else storageHistory.toList(),
                                        color = MaterialTheme.colorScheme.secondary,
                                        min = 0f,
                                        max = 100f,
                                        modifier = Modifier.width(160.dp),
                                        height = 56.dp,
                                        showStats = false,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            FieldLine("Used", storageData.used)
                            FieldLine("Total", storageData.total)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServicesScreen(
    container: AppContainer,
    machineId: Long,
    onBack: () -> Unit,
) {
    val machine by container.machineRepository.observeMachine(machineId).collectAsStateCompat(null)
    var loading by remember { mutableStateOf(false) }
    var loadedOnce by remember { mutableStateOf(false) }
    var services by remember { mutableStateOf<List<ServiceItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val reloadMutex = remember { Mutex() }

    fun reload() {
        val current = machine ?: return
        scope.launch {
            reloadMutex.withLock {
                loading = true
                services = runCatching { container.servicesRepository.listServices(current) }.getOrElse {
                    snackbarHost.showSnackbar("Failed to load services: ${it.message}")
                    services
                }
                loadedOnce = true
                loading = false
            }
        }
    }

    LaunchedEffect(machine?.id) {
        if (machine != null) {
            while (true) {
                reload()
                delay(AppConstants.Refresh.MACHINE_DETAIL_INTERVAL_MS)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Service Control", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        val current = machine
        if (current == null) {
            BoxedMessage("Machine not found", Modifier.padding(padding))
            return@Scaffold
        }
        if (loading && !loadedOnce) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(services, key = { it.name }) { service ->
                    val active = service.activeState == "active"
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (active) {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(service.name, fontWeight = FontWeight.Bold)
                                AssistChip(
                                    onClick = {},
                                    label = { Text(service.activeState.uppercase()) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (active) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    ),
                                )
                            }
                            Text("${service.loadState} / ${service.activeState} / ${service.subState}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = {
                                    scope.launch {
                                        runCatching { container.servicesRepository.start(current, service.name) }
                                        reload()
                                    }
                                }) { Text("Start") }
                                FilledTonalButton(onClick = {
                                    scope.launch {
                                        runCatching { container.servicesRepository.stop(current, service.name) }
                                        reload()
                                    }
                                }) { Text("Stop") }
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { container.servicesRepository.enable(current, service.name) }
                                        reload()
                                    }
                                }) { Text("Enable") }
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { container.servicesRepository.disable(current, service.name) }
                                        reload()
                                    }
                                }) { Text("Disable") }
                            }
                        }
                    }
                }
            }

            if (loading && loadedOnce) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageScopePicker(scopeMode: StorageScopeMode, onChange: (StorageScopeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = storageScopeLabel(scopeMode),
            onValueChange = {},
            readOnly = true,
            label = { Text("Storage scope") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StorageScopeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(storageScopeLabel(mode)) },
                    onClick = {
                        expanded = false
                        onChange(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

private fun storageScopeLabel(scopeMode: StorageScopeMode): String {
    return when (scopeMode) {
        StorageScopeMode.ALL -> "All"
        StorageScopeMode.PER_DEVICE -> "Per device"
        StorageScopeMode.PER_MOUNT -> "Per partition"
    }
}

@Composable
private fun SkeletonSectionCard(title: String, lines: Int) {
    SectionCard(title) {
        repeat(lines) {
            SkeletonLine()
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SkeletonLine() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            ),
    )
}

@Composable
private fun FieldLine(label: String, field: dev.themajorones.servermanager.metrics.ResourceField) {
    val suffix = if (field.isAvailable) "" else " (${field.reason ?: "unavailable"})"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${field.value}$suffix", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GpuFieldLine(label: String, field: dev.themajorones.servermanager.metrics.ResourceField) {
    if (!field.isAvailable && field.reason == "not exposed by driver") {
        return
    }
    FieldLine(label, field)
}

@Composable
private fun BoxedMessage(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message)
    }
}

private fun formatUptime(seconds: Long?): String {
    if (seconds == null) return "Uptime unknown"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val mins = (seconds % 3_600) / 60
    return "Uptime ${days}d ${hours}h ${mins}m"
}

private fun unavailableCpuUi(reason: String): CpuMetrics = CpuMetrics(
    name = unavailable(reason),
    threads = unavailable(reason),
    cores = unavailable(reason),
    clock = unavailable(reason),
    usage = unavailable(reason),
    temperature = unavailable(reason),
)

private fun unavailableRamUi(reason: String): RamMetrics = RamMetrics(
    amount = unavailable(reason),
    generation = unavailable(reason),
    speed = unavailable(reason),
    clock = unavailable(reason),
    usage = unavailable(reason),
    zram = unavailable(reason),
    swap = unavailable(reason),
)

private fun unavailableGpuUi(reason: String): GpuMetric = GpuMetric(
    name = unavailable(reason),
    clock = unavailable(reason),
    vram = unavailable(reason),
    vramUsage = unavailable(reason),
    generation = unavailable(reason),
    speed = unavailable(reason),
    usage = unavailable(reason),
    powerDraw = unavailable(reason),
)

private fun hideMaxBandwidthForInterface(interfaceName: String): Boolean {
    val normalized = interfaceName.lowercase()
    return normalized.startsWith("tailscale") || normalized.startsWith("docker") || normalized.startsWith("br-")
}

private fun appendHistory(
    history: MutableList<Float>,
    value: Float,
    maxPoints: Int = AppConstants.Graph.HISTORY_MAX_POINTS,
) {
    history += value
    while (history.size > maxPoints) {
        history.removeAt(0)
    }
}

private fun parsePercentValue(text: String?): Float? {
    if (text.isNullOrBlank()) return null
    val candidate = text.substringBefore('%').trim()
    return candidate.toFloatOrNull()
}

private fun parseThroughputMbps(text: String?): Float? {
    if (text.isNullOrBlank()) return null
    val value = text.substringBefore(' ').trim().toFloatOrNull() ?: return null
    return when {
        text.contains("Gbps", ignoreCase = true) -> value * 1000f
        text.contains("Mbps", ignoreCase = true) -> value
        text.contains("Kbps", ignoreCase = true) -> value / 1000f
        text.contains("bps", ignoreCase = true) -> value / 1_000_000f
        else -> null
    }
}

private fun formatThroughputMbps(value: Float): String =
    when {
        value >= 1_000f -> String.format(Locale.US, "%.2f Gbps", value / 1_000f)
        value >= 1f -> String.format(Locale.US, "%.2f Mbps", value)
        value >= 0.001f -> String.format(Locale.US, "%.2f Kbps", value * 1_000f)
        else -> String.format(Locale.US, "%.0f bps", value * 1_000_000f)
    }

private fun sumThroughputMbps(values: List<String?>?): Float? {
    if (values.isNullOrEmpty()) return null
    val sum = values.mapNotNull { parseThroughputMbps(it) }.sum()
    return if (sum > 0f) sum else null
}

private fun parseNumericValue(text: String?): Float? {
    if (text.isNullOrBlank()) return null
    val candidate = text.substringBefore(' ').replace("[^0-9.]".toRegex(), "")
    return candidate.toFloatOrNull()
}

private fun parseGbValue(text: String?): Float? {
    if (text.isNullOrBlank()) return null
    val candidate = text.substringBefore(' ').replace("[^0-9.]".toRegex(), "")
    return candidate.toFloatOrNull()
}

private fun parseStorageUsagePercent(storage: StorageMetric?): Float? {
    val used = parseNumericValue(storage?.used?.value) ?: return null
    val total = parseNumericValue(storage?.total?.value) ?: return null
    if (total <= 0f) return null
    return ((used / total) * 100f).coerceIn(0f, 100f)
}

private fun maxThroughput(down: List<Float>, up: List<Float>): Float {
    val peak = (down + up).maxOrNull() ?: 1f
    return if (peak < 10f) 10f else peak * 1.1f
}

private fun chartUpperBound(values: List<Float>): Float {
    val peak = values.maxOrNull() ?: 1f
    val padded = peak * 1.1f
    return if (padded < 1f) 1f else padded
}

@Composable
private fun RingMetricCard(
    title: String,
    value: String,
    percent: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                DonutUsageChart(
                    usedPercent = percent,
                    usedColor = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    size = 72.dp,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StorageRingCard(
    item: StorageItemMetric,
    index: Int,
    totalCount: Int,
) {
    val percent = parsePercentValue(item.usage.value) ?: 0f
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DonutUsageChart(
                usedPercent = percent,
                usedColor = when (index % 3) {
                    0 -> MaterialTheme.colorScheme.primary
                    1 -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.tertiary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                size = 72.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.titleMedium)
                Text(item.usage.value, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${item.used.value} / ${item.total.value}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (totalCount > 1) {
                    Text(
                        text = "Item ${index + 1} of $totalCount",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
