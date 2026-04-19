package dev.themajorones.servermanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.themajorones.servermanager.AppContainer
import dev.themajorones.servermanager.data.AuthMode
import dev.themajorones.servermanager.data.MachineDraft
import dev.themajorones.servermanager.data.MachineEntity
import dev.themajorones.servermanager.data.StorageScopeMode
import dev.themajorones.servermanager.metrics.CpuMetrics
import dev.themajorones.servermanager.metrics.GpuMetric
import dev.themajorones.servermanager.metrics.NetworkMetric
import dev.themajorones.servermanager.metrics.RamMetrics
import dev.themajorones.servermanager.metrics.StorageMetric
import dev.themajorones.servermanager.metrics.unavailable
import dev.themajorones.servermanager.services.ServiceItem
import kotlin.math.max
import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val machines by container.machineRepository.observeMachines().collectAsStateCompat(emptyList())
    val scope = rememberCoroutineScope()

    LaunchedEffect(machines.map { it.id }) {
        while (true) {
            machines.forEach { machine ->
                scope.launch {
                    val reachable = container.sshSessionManager.isReachable(machine)
                    container.machineRepository.updateReachability(machine.id, reachable)
                    if (reachable) {
                        val hostname = runCatching { container.sshSessionManager.execute(machine, "hostname") }.getOrNull()
                        val os = runCatching {
                            container.sshSessionManager.execute(
                                machine,
                                "cat /etc/os-release | awk -F= '/^PRETTY_NAME=/{gsub(/\"/,\"\",\$2);print \$2}'",
                            )
                        }.getOrNull()
                        val uptime = runCatching {
                            container.sshSessionManager.execute(machine, "cut -d. -f1 /proc/uptime").toLongOrNull()
                        }.getOrNull()
                        container.machineRepository.updateDiscovery(machine.id, hostname, os, uptime)
                    }
                }
            }
            delay(5_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Machine List") },
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
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(machine.discoveredHostname ?: machine.host, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(machine.discoveredOs ?: machine.osName ?: "OS unknown")
                            Text(formatUptime(machine.uptimeSeconds))
                            Text(if (machine.isReachable) "Online" else "Offline")
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onOpenDetail(machine.id) }) { Text("Details") }
                                TextButton(onClick = { onOpenSettings(machine.id) }) { Text("Settings") }
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
            TopAppBar(title = { Text(if (machineId == 0L) "Add Machine" else "Machine Settings") })
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
                Text("Parent Machines", style = MaterialTheme.typography.titleMedium)
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
                    Text("Save")
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
    val scope = rememberCoroutineScope()

    LaunchedEffect(machine?.id, machine?.storageScopeMode) {
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
        refreshError = null

        while (true) {
            val cycleStartedAt = System.currentTimeMillis()
            val route = try {
                container.metricsCollector.resolveRoute(current)
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
                delay(max(0L, 5_000L - elapsed))
                continue
            }

            refreshError = null

            cpu = try {
                container.metricsCollector.collectCpu(current, route)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                unavailableCpuUi(error.message ?: "cpu collection failed")
            }
            cpuLoading = false

            ram = try {
                container.metricsCollector.collectRam(current, route)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                unavailableRamUi(error.message ?: "ram collection failed")
            }
            ramLoading = false

            gpus = try {
                container.metricsCollector.collectGpus(current, route)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                listOf(unavailableGpuUi(error.message ?: "gpu collection failed"))
            }
            gpuLoading = false

            networks = try {
                container.metricsCollector.collectNetworks(current, route)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                refreshError = "network: ${error.message ?: "collection failed"}"
                emptyList()
            }
            networkLoading = false

            storage = try {
                container.metricsCollector.collectStorage(current, route)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                StorageMetric(
                    scope = current.storageScopeMode,
                    used = unavailable(error.message ?: "storage collection failed"),
                    total = unavailable(error.message ?: "storage collection failed"),
                )
            }
            storageLoading = false

            val elapsed = System.currentTimeMillis() - cycleStartedAt
            delay(max(0L, 5_000L - elapsed))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(machine?.discoveredHostname ?: machine?.host ?: "Machine") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Settings, contentDescription = "Back")
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
                refreshError?.let { error ->
                    Text("Metrics error: $error")
                }
            }

            item {
                if (cpuLoading && cpu == null) {
                    SkeletonSectionCard("CPU", lines = 5)
                } else {
                    SectionCard("CPU") {
                        val cpuData = cpu ?: unavailableCpuUi("cpu unavailable")
                        FieldLine("Name", cpuData.name)
                        FieldLine("Threads/Core", cpuData.threadsPerCore)
                        FieldLine("Clock", cpuData.clock)
                        FieldLine("Usage", cpuData.usage)
                        FieldLine("Temp", cpuData.temperature)
                    }
                }
            }

            item {
                if (ramLoading && ram == null) {
                    SkeletonSectionCard("RAM", lines = 2)
                } else {
                    SectionCard("RAM") {
                        val ramData = ram ?: unavailableRamUi("ram unavailable")
                        FieldLine("Amount", ramData.amount)
                        FieldLine("Usage", ramData.usage)
                    }
                }
            }

            item {
                if (gpuLoading && gpus == null) {
                    SkeletonSectionCard("GPU", lines = 5)
                } else {
                    SectionCard("GPU") {
                        val gpuData = gpus ?: listOf(unavailableGpuUi("gpu unavailable"))
                        gpuData.forEachIndexed { index, gpu ->
                            Text("GPU ${index + 1}", fontWeight = FontWeight.Bold)
                            FieldLine("Name", gpu.name)
                            GpuFieldLine("Clock", gpu.clock)
                            GpuFieldLine("VRAM", gpu.vram)
                            GpuFieldLine("Speed", gpu.speed)
                            GpuFieldLine("Usage", gpu.usage)
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
                        FieldLine("Used", storageData.used)
                        FieldLine("Total", storageData.total)
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
    var services by remember { mutableStateOf<List<ServiceItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    fun reload() {
        val current = machine ?: return
        scope.launch {
            loading = true
            services = runCatching { container.servicesRepository.listServices(current) }.getOrElse {
                snackbarHost.showSnackbar("Failed to load services: ${it.message}")
                emptyList()
            }
            loading = false
        }
    }

    LaunchedEffect(machine?.id) {
        reload()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Services") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
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
        if (loading) {
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

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(services, key = { it.name }) { service ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(service.name, fontWeight = FontWeight.Bold)
                        Text("${service.loadState} / ${service.activeState} / ${service.subState}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { container.servicesRepository.start(current, service.name) }
                                    reload()
                                }
                            }) { Text("Start") }
                            TextButton(onClick = {
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageScopePicker(scopeMode: StorageScopeMode, onChange: (StorageScopeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = scopeMode.name,
            onValueChange = {},
            readOnly = true,
            label = { Text("Storage scope") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StorageScopeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.name) },
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
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
    Text("$label: ${field.value}$suffix")
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
    threadsPerCore = unavailable(reason),
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
)

private fun unavailableGpuUi(reason: String): GpuMetric = GpuMetric(
    name = unavailable(reason),
    clock = unavailable(reason),
    vram = unavailable(reason),
    generation = unavailable(reason),
    speed = unavailable(reason),
    usage = unavailable(reason),
)

private fun hideMaxBandwidthForInterface(interfaceName: String): Boolean {
    val normalized = interfaceName.lowercase()
    return normalized.startsWith("tailscale") || normalized.startsWith("docker") || normalized.startsWith("br-")
}
