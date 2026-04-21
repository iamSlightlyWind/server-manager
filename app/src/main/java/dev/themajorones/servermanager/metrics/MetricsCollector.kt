package dev.themajorones.servermanager.metrics

import dev.themajorones.servermanager.config.AppConstants
import dev.themajorones.servermanager.data.MachineEntity
import dev.themajorones.servermanager.data.StorageScopeMode
import dev.themajorones.servermanager.network.RouteKind
import dev.themajorones.servermanager.network.RouteResolution
import dev.themajorones.servermanager.network.RouteResolver
import dev.themajorones.servermanager.ssh.RemoteCommandExecutor
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MetricsCollector(
    private val routeResolver: RouteResolver,
    private val commandExecutor: RemoteCommandExecutor,
) {
    suspend fun collect(machine: MachineEntity): MachineMetricsSnapshot = withContext(Dispatchers.IO) {
        val route = routeResolver.resolve(machine)
        MachineMetricsSnapshot(
            cpu = collectCpuInternal(machine, route),
            ram = collectRamInternal(machine, route),
            gpus = collectGpusInternal(machine, route),
            networks = collectNetworksInternal(machine, route),
            storage = collectStorageInternal(machine, route),
            diagnostics = route.diagnostics + "storage_scope=${machine.storageScopeMode.name}",
        )
    }

    suspend fun resolveRoute(machine: MachineEntity): RouteResolution = withContext(Dispatchers.IO) {
        routeResolver.resolve(machine)
    }

    suspend fun collectCpu(machine: MachineEntity, route: RouteResolution? = null): CpuMetrics = withContext(Dispatchers.IO) {
        collectCpuInternal(machine, route ?: routeResolver.resolve(machine))
    }

    suspend fun collectRam(machine: MachineEntity, route: RouteResolution? = null): RamMetrics = withContext(Dispatchers.IO) {
        collectRamInternal(machine, route ?: routeResolver.resolve(machine))
    }

    suspend fun collectGpus(machine: MachineEntity, route: RouteResolution? = null): List<GpuMetric> = withContext(Dispatchers.IO) {
        collectGpusInternal(machine, route ?: routeResolver.resolve(machine))
    }

    suspend fun collectNetworks(machine: MachineEntity, route: RouteResolution? = null): List<NetworkMetric> = withContext(Dispatchers.IO) {
        collectNetworksInternal(machine, route ?: routeResolver.resolve(machine))
    }

    suspend fun collectStorage(machine: MachineEntity, route: RouteResolution? = null): StorageMetric = withContext(Dispatchers.IO) {
        collectStorageInternal(machine, route ?: routeResolver.resolve(machine))
    }

    private suspend fun collectCpuInternal(machine: MachineEntity, route: RouteResolution): CpuMetrics {
        if (route.kind == RouteKind.UNREACHABLE) return unavailableCpu("machine unreachable")

        val cpuBatch = command(
            machine,
                "echo NAME=\$(awk -F: '/model name/{print \$2; exit}' /proc/cpuinfo | xargs); echo THREADS=\$(nproc 2>/dev/null || lscpu 2>/dev/null | awk -F: '/^CPU\\(s\\):/{print \$2; exit}' | xargs); echo CORES_PER_SOCKET=\$(lscpu 2>/dev/null | awk -F: '/^Core\\(s\\) per socket:/{print \$2; exit}' | xargs); echo SOCKETS=\$(lscpu 2>/dev/null | awk -F: '/^Socket\\(s\\):/{print \$2; exit}' | xargs); echo CORES_FALLBACK=\$(awk -F: '/^cpu cores/{print \$2; exit}' /proc/cpuinfo | xargs); echo MHZ=\$(awk -F: '/cpu MHz/{print \$2; exit}' /proc/cpuinfo | xargs); echo USAGE=\$(top -bn1 | awk '/Cpu\\(s\\)/ {print \$2 + \$4}')",
            "cpu batch",
            timeoutSeconds = AppConstants.Metrics.CPU_BATCH_TIMEOUT_SECONDS,
        )
        val tempRaw = command(
            machine,
            "for h in /sys/class/hwmon/hwmon*; do name=\$(cat \"\$h/name\" 2>/dev/null); case \"\$name\" in k10temp|coretemp|zenpower|cpu_thermal|fam15h_power) for t in \"\$h\"/temp*_input; do [ -r \"\$t\" ] || continue; v=\$(cat \"\$t\" 2>/dev/null); case \"\$v\" in ''|*[!0-9]*) continue;; esac; [ \"\$v\" -gt 0 ] && echo \"\$v\" && exit 0; done ;; esac; done; for z in /sys/class/thermal/thermal_zone*/temp; do [ -r \"\$z\" ] || continue; v=\$(cat \"\$z\" 2>/dev/null); case \"\$v\" in ''|*[!0-9]*) continue;; esac; [ \"\$v\" -gt 0 ] && echo \"\$v\" && exit 0; done; for h in /sys/class/hwmon/hwmon*; do for t in \"\$h\"/temp*_input; do [ -r \"\$t\" ] || continue; v=\$(cat \"\$t\" 2>/dev/null); case \"\$v\" in ''|*[!0-9]*) continue;; esac; [ \"\$v\" -gt 0 ] && echo \"\$v\" && exit 0; done; done",
            "cpu temp",
            timeoutSeconds = AppConstants.Metrics.CPU_TEMP_TIMEOUT_SECONDS,
        )
        val values = parseKeyValue(cpuBatch)
        val cpuTempRaw = if (tempRaw.isBlank()) "UNAVAILABLE:cpu temp:empty" else tempRaw

        return CpuMetrics(
            name = fieldValue(values, "NAME", "cpu name").toField(),
                threads = toIntegerField(fieldValue(values, "THREADS", "cpu threads"), "cpu threads"),
                cores = totalCoresField(
                    coresPerSocketRaw = values["CORES_PER_SOCKET"],
                    socketsRaw = values["SOCKETS"],
                    fallbackRaw = values["CORES_FALLBACK"],
                ),
            clock = mhzToGhzField(fieldValue(values, "MHZ", "cpu clock"), "cpu clock"),
            usage = percentField(fieldValue(values, "USAGE", "cpu usage"), "cpu usage"),
            temperature = normalizeTemperature(cpuTempRaw),
        )
    }

    private suspend fun collectRamInternal(machine: MachineEntity, route: RouteResolution): RamMetrics {
        if (route.kind == RouteKind.UNREACHABLE) return unavailableRam("machine unreachable")

        val ramBatch = command(
            machine,
            "echo TOTAL_KB=$(awk '/MemTotal/ {print \$2}' /proc/meminfo); echo AVAIL_KB=$(awk '/MemAvailable/ {print \$2}' /proc/meminfo); echo SWAP_TOTAL_KB=$(awk '/SwapTotal/ {print \$2}' /proc/meminfo); echo SWAP_FREE_KB=$(awk '/SwapFree/ {print \$2}' /proc/meminfo)",
            "ram batch",
            timeoutSeconds = AppConstants.Metrics.RAM_BATCH_TIMEOUT_SECONDS,
        )
        val zramRaw = command(
            machine,
            "for z in /sys/block/zram*; do [ -r \"\$z/disksize\" ] || continue; total=$(cat \"\$z/disksize\" 2>/dev/null); [ -n \"\$total\" ] || continue; [ \"\$total\" -gt 0 ] || continue; used=$(awk '{print \$1}' \"\$z/mm_stat\" 2>/dev/null); [ -n \"\$used\" ] || used=$(cat \"\$z/orig_data_size\" 2>/dev/null); [ -n \"\$used\" ] || continue; echo \"\$(basename \"\$z\")|\$used|\$total\"; exit 0; done",
            "zram batch",
            timeoutSeconds = AppConstants.Metrics.RAM_BATCH_TIMEOUT_SECONDS,
        )
        val values = parseKeyValue(ramBatch)
        val totalKb = fieldValue(values, "TOTAL_KB", "ram total")
        val availKb = fieldValue(values, "AVAIL_KB", "ram available")
        val swapTotalKb = fieldValue(values, "SWAP_TOTAL_KB", "swap total")
        val swapFreeKb = fieldValue(values, "SWAP_FREE_KB", "swap free")

        return RamMetrics(
            amount = kibToGbField(totalKb, "ram total"),
            generation = unavailable("hidden"),
            speed = unavailable("hidden"),
            clock = unavailable("hidden"),
            usage = memoryUsage(totalKb, availKb),
            zram = zramUsage(zramRaw),
            swap = swapUsage(swapTotalKb, swapFreeKb),
        )
    }

    private suspend fun collectGpusInternal(machine: MachineEntity, route: RouteResolution): List<GpuMetric> {
        if (route.kind == RouteKind.UNREACHABLE) return listOf(unavailableGpu("machine unreachable"))

        val nvidiaText = command(
            machine,
            "nvidia-smi --query-gpu=name,clocks.current.graphics,memory.used,memory.total,utilization.gpu,power.draw --format=csv,noheader,nounits 2>/dev/null || true",
            "gpu nvidia",
            timeoutSeconds = AppConstants.Metrics.GPU_NVIDIA_TIMEOUT_SECONDS,
        )

        val inventoryText = command(
            machine,
            "echo __DRM__; for c in /sys/class/drm/card[0-9]*; do [ -e \"\$c/device/vendor\" ] || continue; slot=$(awk -F= '/^PCI_SLOT_NAME=/{print \$2; exit}' \"\$c/device/uevent\" 2>/dev/null); vendor=$(cat \"\$c/device/vendor\" 2>/dev/null); device=$(cat \"\$c/device/device\" 2>/dev/null); drv=$(basename \"$(readlink -f \"\$c/device/driver\" 2>/dev/null)\" 2>/dev/null); echo \"\$slot|\$vendor|\$device|\$drv\"; done; echo __LSPCI__; lspci -nn 2>/dev/null | grep -Ei '(vga compatible controller|3d controller|display controller)' || true",
            "gpu inventory",
            timeoutSeconds = AppConstants.Metrics.GPU_INVENTORY_TIMEOUT_SECONDS,
        )

        val inventoryLines = inventoryText.lines()
        val drmMarker = inventoryLines.indexOf("__DRM__")
        val lspciMarker = inventoryLines.indexOf("__LSPCI__")
        val drmText =
            if (drmMarker >= 0 && lspciMarker > drmMarker) {
                inventoryLines.subList(drmMarker + 1, lspciMarker).joinToString("\n")
            } else {
                ""
            }
        val lspciText =
            if (lspciMarker >= 0 && lspciMarker < inventoryLines.lastIndex) {
                inventoryLines.subList(lspciMarker + 1, inventoryLines.size).joinToString("\n")
            } else {
                ""
            }

        return parseGpuMetrics(nvidiaText = nvidiaText, drmText = drmText, lspciText = lspciText)
    }

    private suspend fun collectNetworksInternal(machine: MachineEntity, route: RouteResolution): List<NetworkMetric> {
        if (route.kind == RouteKind.UNREACHABLE) return emptyList()

        val networkSummary = command(
            machine,
            "ifaces=$(ip -o link show up | awk -F': ' '\$2 != \"lo\" {print \$2}'); t=$(mktemp); for i in \$ifaces; do iface=$(echo \"\$i\" | cut -d@ -f1); rx=$(cat /sys/class/net/\$iface/statistics/rx_bytes 2>/dev/null); tx=$(cat /sys/class/net/\$iface/statistics/tx_bytes 2>/dev/null); echo \"\$iface|\$rx|\$tx\" >> \"\$t\"; done; sleep 1; for i in \$ifaces; do iface=$(echo \"\$i\" | cut -d@ -f1); sp=$(cat /sys/class/net/\$iface/speed 2>/dev/null); mac=$(cat /sys/class/net/\$iface/address 2>/dev/null); ip=$(ip -4 addr show \$iface | awk '/inet / {print \$2; exit}'); rx2=$(cat /sys/class/net/\$iface/statistics/rx_bytes 2>/dev/null); tx2=$(cat /sys/class/net/\$iface/statistics/tx_bytes 2>/dev/null); p=$(grep \"^\$iface|\" \"\$t\" | head -n 1); rx1=$(echo \"\$p\" | cut -d'|' -f2); tx1=$(echo \"\$p\" | cut -d'|' -f3); [ -z \"\$rx1\" ] && rx1=0; [ -z \"\$tx1\" ] && tx1=0; drx=$((rx2-rx1)); dtx=$((tx2-tx1)); echo \"\$iface|\$sp|\$mac|\$ip|\$drx|\$dtx\"; done; rm -f \"\$t\"",
            "net summary",
            timeoutSeconds = AppConstants.Metrics.NETWORK_SUMMARY_TIMEOUT_SECONDS,
        )

        return parseNetworkSummary(networkSummary)
    }

    private suspend fun collectStorageInternal(machine: MachineEntity, route: RouteResolution): StorageMetric {
        if (route.kind == RouteKind.UNREACHABLE) {
            return StorageMetric(
                scope = machine.storageScopeMode,
                used = unavailable("machine unreachable"),
                total = unavailable("machine unreachable"),
                items = emptyList(),
            )
        }

        val aggregateAllCommand =
            "df -B1 -x tmpfs -x devtmpfs --output=used,size | awk 'NR>1 {u+=\$1; s+=\$2} END {print u\" \"s}'"
        val detailedCommand =
            "df -B1 -x tmpfs -x devtmpfs --output=source,used,size,target"
        val deviceMapCommand =
            "lsblk -P -rno NAME,PKNAME,TYPE 2>/dev/null"
        val summaryCommand = aggregateAllCommand
        val detailCommand =
            when (machine.storageScopeMode) {
                StorageScopeMode.ALL -> aggregateAllCommand
                StorageScopeMode.PER_DEVICE, StorageScopeMode.PER_MOUNT -> detailedCommand
            }

        val summaryResult = command(
            machine,
            summaryCommand,
            "storage",
            timeoutSeconds = AppConstants.Metrics.STORAGE_TIMEOUT_SECONDS,
        )
        val detailResult = command(
            machine,
            detailCommand,
            "storage",
            timeoutSeconds = AppConstants.Metrics.STORAGE_TIMEOUT_SECONDS,
        )
        val deviceMapResult = if (machine.storageScopeMode == StorageScopeMode.PER_DEVICE) {
            command(
                machine,
                deviceMapCommand,
                "storage device map",
                timeoutSeconds = AppConstants.Metrics.STORAGE_TIMEOUT_SECONDS,
            )
        } else {
            ""
        }
        val raw = if (summaryResult.isBlank()) {
            detailResult
        } else {
            summaryResult
        }
        val storageParts = raw.split(" ").filter { it.isNotBlank() }

        return StorageMetric(
            scope = machine.storageScopeMode,
            used = bytesToGbField(storageParts.getOrNull(0).orEmpty(), "storage used"),
            total = bytesToGbField(storageParts.getOrNull(1).orEmpty(), "storage total"),
            items = if (machine.storageScopeMode == StorageScopeMode.ALL) {
                emptyList()
            } else {
                parseStorageItems(
                    text = detailResult,
                    scopeMode = machine.storageScopeMode,
                    deviceParents = parseDeviceParents(deviceMapResult),
                )
            },
        )
    }

    private suspend fun command(
        machine: MachineEntity,
        cmd: String,
        label: String,
        timeoutSeconds: Long = AppConstants.Metrics.DEFAULT_COMMAND_TIMEOUT_SECONDS,
    ): String =
        runCatching { commandExecutor.execute(machine, cmd, timeoutSeconds = timeoutSeconds) }
            .getOrElse { "UNAVAILABLE:$label:${it.message.orEmpty()}" }
            .trim()

    private fun parseKeyValue(text: String): Map<String, String> {
        return text.lines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                key to value
            }
            .toMap()
    }

    private fun fieldValue(values: Map<String, String>, key: String, label: String): String {
        return values[key] ?: "UNAVAILABLE:$label:missing"
    }

    private fun parseStorageItems(
        text: String,
        scopeMode: StorageScopeMode,
        deviceParents: Map<String, String> = emptyMap(),
    ): List<StorageItemMetric> {
        val uniqueRows = linkedMapOf<String, StorageRow>()

        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Filesystem") && !it.startsWith("UNAVAILABLE:") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 4)
                if (parts.size < 4) return@mapNotNull null
                val rawSource = parts[0].trim()
                val source = normalizeStorageName(rawSource)
                val usedRaw = parts[1]
                val totalRaw = parts[2]
                val target = parts[3]

                // Device/partition views should only show block devices from /dev.
                if ((scopeMode == StorageScopeMode.PER_DEVICE || scopeMode == StorageScopeMode.PER_MOUNT) && !rawSource.startsWith("/dev/")) {
                    return@mapNotNull null
                }

                val usedBytes = usedRaw.toLongOrNull() ?: 0L
                val totalBytes = totalRaw.toLongOrNull() ?: 0L
                uniqueRows[source] = StorageRow(
                    source = source,
                    used = usedBytes,
                    total = totalBytes,
                    target = target,
                )
                when (scopeMode) {
                    StorageScopeMode.PER_DEVICE -> resolvePhysicalDeviceLabel(source, deviceParents)
                    StorageScopeMode.PER_MOUNT -> source
                    StorageScopeMode.ALL -> source
                }
                Unit
            }

        return when (scopeMode) {
            StorageScopeMode.PER_DEVICE -> {
                val aggregated = linkedMapOf<String, MutableLongPair>()
                uniqueRows.values.forEach { row ->
                    val label = resolvePhysicalDeviceLabel(row.source, deviceParents)
                    val current = aggregated.getOrPut(label) { MutableLongPair() }
                    current.used += row.used
                    current.total += row.total
                }
                aggregated.map { (label, totals) ->
                    val usedRaw = totals.used.toString()
                    val totalRaw = totals.total.toString()
                    StorageItemMetric(
                        label = label,
                        used = bytesToGbField(usedRaw, "storage used"),
                        total = bytesToGbField(totalRaw, "storage total"),
                        usage = storageUsageField(usedRaw, totalRaw),
                    )
                }
            }

            StorageScopeMode.PER_MOUNT -> uniqueRows.values.map { row ->
                val usedRaw = row.used.toString()
                val totalRaw = row.total.toString()
                StorageItemMetric(
                    label = row.source,
                    used = bytesToGbField(usedRaw, "storage used"),
                    total = bytesToGbField(totalRaw, "storage total"),
                    usage = storageUsageField(usedRaw, totalRaw),
                )
            }

            StorageScopeMode.ALL -> emptyList()
        }
    }

    private fun parseDeviceParents(text: String): Map<String, String> {
        val pairs = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("UNAVAILABLE:") }
            .mapNotNull { line ->
                val values = parseLsblkPairs(line)
                val name = normalizeStorageName(values["NAME"].orEmpty())
                val parent = normalizeStorageName(values["PKNAME"].orEmpty())
                if (name.isBlank() || parent.isBlank()) return@mapNotNull null
                name to parent
            }
        return pairs.toMap()
    }

    private fun parseLsblkPairs(line: String): Map<String, String> {
        val regex = Regex("([A-Z]+)=\"([^\"]*)\"")
        return regex.findAll(line).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }
    }

    private fun resolvePhysicalDeviceLabel(source: String, parents: Map<String, String>): String {
        val root = generateSequence(source) { parents[it] }
            .lastOrNull()
            .orEmpty()
        return if (root.isBlank()) {
            "/dev/$source"
        } else {
            "/dev/$root"
        }
    }

    private fun normalizeStorageName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        return trimmed.substringAfterLast('/')
    }

    private data class MutableLongPair(
        var used: Long = 0L,
        var total: Long = 0L,
    )

    private data class StorageRow(
        val source: String,
        val used: Long,
        val total: Long,
        val target: String,
    )

    private fun parseGpuMetrics(nvidiaText: String, drmText: String, lspciText: String): List<GpuMetric> {
        val result = mutableListOf<GpuMetric>()
        val seen = linkedSetOf<String>()

        fun add(metric: GpuMetric) {
            val key = metric.name.value.lowercase(Locale.US)
            if (seen.add(key)) {
                result += metric
            }
        }

        val nvidiaRows = nvidiaText.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("UNAVAILABLE:") }
        nvidiaRows.forEach { line ->
            val parts = line.split(",").map { it.trim() }
            if (parts.size >= 6) {
                val name = parts[0]
                val clock = parts[1]
                val vramUsed = parts[2]
                val vramTotal = parts[3]
                val usage = parts[4]
                val powerDraw = parts[5]
                add(
                    GpuMetric(
                        name = available(name),
                        clock = mhzToGhzField(clock, "gpu clock"),
                        vram = mibToGbField(vramTotal, "gpu vram"),
                        vramUsage = gpuVramUsageField(vramUsed, vramTotal),
                        generation = mapGpuGeneration(name),
                        speed = mhzToGhzField(clock, "gpu speed"),
                        usage = percentField(usage, "gpu usage"),
                        powerDraw = gpuPowerField(powerDraw),
                    ),
                )
            }
        }

        val lspciBySlot = mutableMapOf<String, String>()
        lspciText.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("UNAVAILABLE:") }
            .forEach { line ->
                val match = Regex("^([0-9A-Fa-f:.]+)\\s+(.+)$").find(line) ?: return@forEach
                val slot = normalizeSlot(match.groupValues[1]) ?: return@forEach
                val tail = match.groupValues[2]
                lspciBySlot[slot] = tail.substringAfter(": ", tail).trim()
            }

        val drmRows = drmText.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("UNAVAILABLE:") }
        drmRows.forEach { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@forEach
            val slot = normalizeSlot(parts[0].trim())
            val vendor = parts[1].trim().lowercase(Locale.US)

            // nvidia-smi already gives richer metrics, so skip duplicate NVIDIA rows from DRM.
            if (vendor == "0x10de" && nvidiaRows.isNotEmpty()) {
                return@forEach
            }

            val fallbackName = buildString {
                append(vendorLabel(vendor))
                append(" GPU")
                if (!slot.isNullOrBlank()) {
                    append(" ")
                    append(slot)
                }
            }
            val gpuName = slot?.let { lspciBySlot[it] } ?: fallbackName
            add(
                GpuMetric(
                    name = available(gpuName),
                    clock = unavailable("not exposed by driver"),
                    vram = unavailable("not exposed by driver"),
                    vramUsage = unavailable("not exposed by driver"),
                    generation = mapGpuGeneration(gpuName),
                    speed = unavailable("not exposed by driver"),
                    usage = unavailable("not exposed by driver"),
                    powerDraw = unavailable("not exposed by driver"),
                ),
            )
        }

        // Ensure non-NVIDIA adapters still appear even if DRM probing fails and NVIDIA metrics were populated.
        lspciBySlot.values.forEach { gpuName ->
            val isNvidia = gpuName.lowercase(Locale.US).contains("nvidia")
            if (isNvidia && nvidiaRows.isNotEmpty()) {
                return@forEach
            }
            add(
                GpuMetric(
                    name = available(gpuName),
                    clock = unavailable("clock unavailable"),
                    vram = unavailable("vram unavailable"),
                    vramUsage = unavailable("vram usage unavailable"),
                    generation = mapGpuGeneration(gpuName),
                    speed = unavailable("speed unavailable"),
                    usage = unavailable("usage unavailable"),
                    powerDraw = unavailable("power draw unavailable"),
                ),
            )
        }

        if (result.isEmpty()) {
            lspciBySlot.values.forEach { gpuName ->
                add(
                    GpuMetric(
                        name = available(gpuName),
                        clock = unavailable("clock unavailable"),
                        vram = unavailable("vram unavailable"),
                        vramUsage = unavailable("vram usage unavailable"),
                        generation = mapGpuGeneration(gpuName),
                        speed = unavailable("speed unavailable"),
                        usage = unavailable("usage unavailable"),
                        powerDraw = unavailable("power draw unavailable"),
                    ),
                )
            }
        }

        return if (result.isEmpty()) listOf(unavailableGpu("gpu info not available")) else result
    }

    private fun parseNetworkSummary(text: String): List<NetworkMetric> {
        return text.lines().mapNotNull { line ->
            val compact = line.trim()
            if (compact.isBlank() || compact.startsWith("UNAVAILABLE:")) return@mapNotNull null
            val parts = compact.split('|')
            if (parts.size < 6) return@mapNotNull null

            val name = parts[0].trim()
            val speed = parts[1].trim()
            val mac = parts[2].trim()
            val ip = parts[3].trim()
            val rx = parts[4].trim()
            val tx = parts[5].trim()

            NetworkMetric(
                name = name,
                maxBandwidth = linkSpeedField(speed),
                currentDown = throughputField(rx, "net rx"),
                currentUp = throughputField(tx, "net tx"),
                ip = ip.toField(),
                mac = mac.toField(),
            )
        }
    }

    private fun mapGpuGeneration(name: String): ResourceField {
        val n = name.lowercase()
        val generation = when {
            "rtx 20" in n || "turing" in n -> "Turing"
            "rtx 30" in n || "ampere" in n -> "Ampere"
            "rtx 40" in n || "ada" in n -> "Ada"
            "rdna3" in n || "rx 7" in n -> "RDNA3"
            "rdna2" in n || "rx 6" in n -> "RDNA2"
            "vega" in n -> "Vega"
            "xe" in n -> "Xe"
            "uhd" in n || "iris" in n -> "Intel Gen"
            else -> "Unknown"
        }
        return if (generation == "Unknown") unavailable("generation inference unavailable") else available(generation)
    }

    private fun vendorLabel(vendorHex: String): String =
        when (vendorHex.lowercase(Locale.US)) {
            "0x10de" -> "NVIDIA"
            "0x1002" -> "AMD"
            "0x8086" -> "Intel"
            else -> vendorHex
        }

    private fun normalizeSlot(slot: String?): String? {
        if (slot.isNullOrBlank()) return null
        return slot.removePrefix("0000:")
    }

    private fun memoryUsage(totalKb: String, availableKb: String): ResourceField {
        val total = totalKb.toLongOrNull()
        val avail = availableKb.toLongOrNull()
        if (total == null || avail == null || total <= 0) {
            return unavailable("memory usage unavailable")
        }
        val used = total - avail
        val percent = (used * 100.0 / total)
        val usedGb = used.toDouble() * 1024.0 / (1024.0 * 1024.0 * 1024.0)
        val totalGb = total.toDouble() * 1024.0 / (1024.0 * 1024.0 * 1024.0)
        return available(String.format(Locale.US, "%.2f%% (%.2f GB / %.2f GB)", percent, usedGb, totalGb))
    }

    private fun swapUsage(totalKb: String, freeKb: String): ResourceField {
        val total = totalKb.toLongOrNull()
        val free = freeKb.toLongOrNull()
        if (total == null || free == null || total <= 0) {
            return unavailable("swap usage unavailable")
        }
        val used = total - free
        return usageFieldFromKb(used, total, "swap usage")
    }

    private fun zramUsage(raw: String): ResourceField {
        if (raw.isBlank() || raw.startsWith("UNAVAILABLE:")) return unavailable("zram unavailable")
        val parts = raw.split("|")
        if (parts.size < 3) return unavailable("zram unavailable")
        val used = parts.getOrNull(1)?.toLongOrNull()
        val total = parts.getOrNull(2)?.toLongOrNull()
        if (used == null || total == null || total <= 0) return unavailable("zram unavailable")
        return usageFieldFromBytes(used, total, "zram usage")
    }

    private fun storageUsageField(usedRaw: String, totalRaw: String): ResourceField {
        val used = usedRaw.toLongOrNull()
        val total = totalRaw.toLongOrNull()
        if (used == null || total == null || total <= 0) {
            return unavailable("storage usage unavailable")
        }
        return available(String.format(Locale.US, "%.2f%%", (used * 100.0 / total)))
    }

    private fun gpuVramUsageField(usedRaw: String, totalRaw: String): ResourceField {
        val used = usedRaw.toDoubleOrNull()
        val total = totalRaw.toDoubleOrNull()
        if (used == null || total == null || total <= 0.0) {
            return unavailable("vram usage unavailable")
        }
        val percent = (used * 100.0 / total)
        return available(String.format(Locale.US, "%.2f%%", percent))
    }

    private fun gpuPowerField(raw: String): ResourceField {
        val value = raw.trim()
        if (value.isBlank()) return unavailable("power draw unavailable")
        if (value.equals("n/a", ignoreCase = true) || value.equals("[not supported]", ignoreCase = true)) {
            return unavailable("power draw unavailable")
        }
        val watts = value.toDoubleOrNull() ?: return unavailable("power draw unavailable")
        return available(String.format(Locale.US, "%.2f W", watts))
    }

    private fun usageFieldFromKb(usedKb: Long, totalKb: Long, label: String): ResourceField {
        if (totalKb <= 0) return unavailable("$label unavailable")
        val percent = (usedKb * 100.0 / totalKb)
        val usedGb = usedKb.toDouble() * 1024.0 / (1024.0 * 1024.0 * 1024.0)
        val totalGb = totalKb.toDouble() * 1024.0 / (1024.0 * 1024.0 * 1024.0)
        return available(String.format(Locale.US, "%.2f%% (%.2f GB / %.2f GB)", percent, usedGb, totalGb))
    }

    private fun usageFieldFromBytes(usedBytes: Long, totalBytes: Long, label: String): ResourceField {
        if (totalBytes <= 0) return unavailable("$label unavailable")
        val percent = (usedBytes * 100.0 / totalBytes)
        val usedGb = usedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val totalGb = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return available(String.format(Locale.US, "%.2f%% (%.2f GB / %.2f GB)", percent, usedGb, totalGb))
    }

    private fun normalizeTemperature(raw: String): ResourceField {
        if (raw.startsWith("UNAVAILABLE:")) return raw.toField()
        val value = raw.toLongOrNull()
        if (value == null) {
            return raw.toField()
        }
        return if (value > 1000) {
            available(String.format(Locale.US, "%.2f C", value / 1000.0))
        } else {
            available("$value C")
        }
    }

    private fun toIntegerField(raw: String, label: String): ResourceField {
        if (raw.startsWith("UNAVAILABLE:")) return raw.toField()
        val value = raw.trim().toIntOrNull() ?: return unavailable("$label unavailable")
        return available(value.toString())
    }

    private fun totalCoresField(
        coresPerSocketRaw: String?,
        socketsRaw: String?,
        fallbackRaw: String?,
    ): ResourceField {
        val coresPerSocket = coresPerSocketRaw?.trim()?.toLongOrNull()
        val sockets = socketsRaw?.trim()?.toLongOrNull()
        if (coresPerSocket != null && sockets != null && coresPerSocket > 0 && sockets > 0) {
            return available((coresPerSocket * sockets).toString())
        }

        val fallback = fallbackRaw?.trim()?.toLongOrNull()
        if (fallback != null && fallback > 0) {
            return available(fallback.toString())
        }

        return unavailable("cpu cores unavailable")
    }

    private fun percentField(raw: String, label: String): ResourceField {
        if (raw.startsWith("UNAVAILABLE:")) return raw.toField()
        val value = raw.trim().toDoubleOrNull() ?: return unavailable("$label unavailable")
        return available(String.format(Locale.US, "%.2f%%", value))
    }

    private fun mhzToGhzField(raw: String, label: String): ResourceField {
        if (raw.startsWith("UNAVAILABLE:")) return raw.toField()
        val value = raw.trim().toDoubleOrNull() ?: return unavailable("$label unavailable")
        return available(String.format(Locale.US, "%.2f GHz", value / 1000.0))
    }

    private fun kibToGbField(raw: String, label: String): ResourceField {
        if (raw.startsWith("UNAVAILABLE:")) return raw.toField()
        val value = raw.trim().toLongOrNull() ?: return unavailable("$label unavailable")
        val gb = value.toDouble() * 1024.0 / (1024.0 * 1024.0 * 1024.0)
        return available(String.format(Locale.US, "%.2f GB", gb))
    }

    private fun mibToGbField(raw: String, label: String): ResourceField {
        if (raw.startsWith("UNAVAILABLE:")) return raw.toField()
        val value = raw.trim().toLongOrNull() ?: return unavailable("$label unavailable")
        val gb = value.toDouble() / 1024.0
        return available(String.format(Locale.US, "%.2f GB", gb))
    }

    private fun bytesToGbField(raw: String, label: String): ResourceField {
        if (raw.startsWith("UNAVAILABLE:")) return raw.toField()
        val value = raw.trim().toLongOrNull() ?: return unavailable("$label unavailable")
        val gb = value.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return available(String.format(Locale.US, "%.2f GB", gb))
    }

    private fun throughputField(raw: String, label: String): ResourceField {
        if (raw.startsWith("UNAVAILABLE:")) return raw.toField()
        val bytesPerSecond = raw.trim().toLongOrNull() ?: return unavailable("$label unavailable")
        if (bytesPerSecond < 0) return unavailable("$label unavailable")

        val bitsPerSecond = bytesPerSecond.toDouble() * 8.0
        val rendered = when {
            bitsPerSecond >= 1_000_000_000.0 -> String.format(Locale.US, "%.2f Gbps", bitsPerSecond / 1_000_000_000.0)
            bitsPerSecond >= 1_000_000.0 -> String.format(Locale.US, "%.2f Mbps", bitsPerSecond / 1_000_000.0)
            bitsPerSecond >= 1_000.0 -> String.format(Locale.US, "%.2f Kbps", bitsPerSecond / 1_000.0)
            else -> String.format(Locale.US, "%.0f bps", bitsPerSecond)
        }
        return available(rendered)
    }

    private fun linkSpeedField(raw: String): ResourceField {
        if (raw.startsWith("UNAVAILABLE:")) return raw.toField()
        val mbps = raw.trim().toLongOrNull() ?: return unavailable("link speed unavailable")
        if (mbps <= 0) return unavailable("link speed unavailable")
        return if (mbps >= 1000) {
            available(String.format(Locale.US, "%.2f Gbps", mbps / 1000.0))
        } else {
            available("$mbps Mbps")
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = -1
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index += 1
        }
        val unit = units[max(index, 0)]
        return String.format(Locale.US, "%.2f %s", value, unit)
    }

    private fun unavailableCpu(reason: String): CpuMetrics = CpuMetrics(
        name = unavailable(reason),
        threads = unavailable(reason),
        cores = unavailable(reason),
        clock = unavailable(reason),
        usage = unavailable(reason),
        temperature = unavailable(reason),
    )

    private fun unavailableRam(reason: String): RamMetrics = RamMetrics(
        amount = unavailable(reason),
        generation = unavailable(reason),
        speed = unavailable(reason),
        clock = unavailable(reason),
        usage = unavailable(reason),
        zram = unavailable(reason),
        swap = unavailable(reason),
    )

    private fun unavailableGpu(reason: String): GpuMetric = GpuMetric(
        name = unavailable(reason),
        clock = unavailable(reason),
        vram = unavailable(reason),
        vramUsage = unavailable(reason),
        generation = unavailable(reason),
        speed = unavailable(reason),
        usage = unavailable(reason),
        powerDraw = unavailable(reason),
    )

    private fun String.toField(): ResourceField {
        if (isBlank()) {
            return unavailable("empty")
        }
        if (startsWith("UNAVAILABLE:")) {
            return unavailable(removePrefix("UNAVAILABLE:"))
        }
        return available(this)
    }
}
