package dev.themajorones.servermanager.metrics

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
            "echo NAME=\$(awk -F: '/model name/{print \$2; exit}' /proc/cpuinfo | xargs); echo THREADS=\$(lscpu 2>/dev/null | awk -F: '/Thread\\(s\\) per core/{print \$2; exit}' | xargs); echo MHZ=\$(awk -F: '/cpu MHz/{print \$2; exit}' /proc/cpuinfo | xargs); echo USAGE=\$(top -bn1 | awk '/Cpu\\(s\\)/ {print \$2 + \$4}')",
            "cpu batch",
            timeoutSeconds = 7,
        )
        val tempRaw = command(
            machine,
            "for h in /sys/class/hwmon/hwmon*; do name=\$(cat \"\$h/name\" 2>/dev/null); case \"\$name\" in k10temp|coretemp|zenpower|cpu_thermal|fam15h_power) for t in \"\$h\"/temp*_input; do [ -r \"\$t\" ] || continue; v=\$(cat \"\$t\" 2>/dev/null); case \"\$v\" in ''|*[!0-9]*) continue;; esac; [ \"\$v\" -gt 0 ] && echo \"\$v\" && exit 0; done ;; esac; done; for z in /sys/class/thermal/thermal_zone*/temp; do [ -r \"\$z\" ] || continue; v=\$(cat \"\$z\" 2>/dev/null); case \"\$v\" in ''|*[!0-9]*) continue;; esac; [ \"\$v\" -gt 0 ] && echo \"\$v\" && exit 0; done; for h in /sys/class/hwmon/hwmon*; do for t in \"\$h\"/temp*_input; do [ -r \"\$t\" ] || continue; v=\$(cat \"\$t\" 2>/dev/null); case \"\$v\" in ''|*[!0-9]*) continue;; esac; [ \"\$v\" -gt 0 ] && echo \"\$v\" && exit 0; done; done",
            "cpu temp",
            timeoutSeconds = 5,
        )
        val values = parseKeyValue(cpuBatch)
        val cpuTempRaw = if (tempRaw.isBlank()) "UNAVAILABLE:cpu temp:empty" else tempRaw

        return CpuMetrics(
            name = fieldValue(values, "NAME", "cpu name").toField(),
            threadsPerCore = toIntegerField(fieldValue(values, "THREADS", "cpu threads/core"), "cpu threads/core"),
            clock = mhzToGhzField(fieldValue(values, "MHZ", "cpu clock"), "cpu clock"),
            usage = percentField(fieldValue(values, "USAGE", "cpu usage"), "cpu usage"),
            temperature = normalizeTemperature(cpuTempRaw),
        )
    }

    private suspend fun collectRamInternal(machine: MachineEntity, route: RouteResolution): RamMetrics {
        if (route.kind == RouteKind.UNREACHABLE) return unavailableRam("machine unreachable")

        val ramBatch = command(
            machine,
            "echo TOTAL_KB=$(awk '/MemTotal/ {print \$2}' /proc/meminfo); echo AVAIL_KB=$(awk '/MemAvailable/ {print \$2}' /proc/meminfo)",
            "ram batch",
            timeoutSeconds = 6,
        )
        val values = parseKeyValue(ramBatch)
        val totalKb = fieldValue(values, "TOTAL_KB", "ram total")
        val availKb = fieldValue(values, "AVAIL_KB", "ram available")

        return RamMetrics(
            amount = kibToGbField(totalKb, "ram total"),
            generation = unavailable("hidden"),
            speed = unavailable("hidden"),
            clock = unavailable("hidden"),
            usage = memoryUsage(totalKb, availKb),
        )
    }

    private suspend fun collectGpusInternal(machine: MachineEntity, route: RouteResolution): List<GpuMetric> {
        if (route.kind == RouteKind.UNREACHABLE) return listOf(unavailableGpu("machine unreachable"))

        val nvidiaText = command(
            machine,
            "nvidia-smi --query-gpu=name,clocks.current.graphics,memory.total,utilization.gpu --format=csv,noheader,nounits 2>/dev/null || true",
            "gpu nvidia",
            timeoutSeconds = 6,
        )

        val inventoryText = command(
            machine,
            "echo __DRM__; for c in /sys/class/drm/card[0-9]*; do [ -e \"\$c/device/vendor\" ] || continue; slot=$(awk -F= '/^PCI_SLOT_NAME=/{print \$2; exit}' \"\$c/device/uevent\" 2>/dev/null); vendor=$(cat \"\$c/device/vendor\" 2>/dev/null); device=$(cat \"\$c/device/device\" 2>/dev/null); drv=$(basename \"$(readlink -f \"\$c/device/driver\" 2>/dev/null)\" 2>/dev/null); echo \"\$slot|\$vendor|\$device|\$drv\"; done; echo __LSPCI__; lspci -nn 2>/dev/null | grep -Ei '(vga compatible controller|3d controller|display controller)' || true",
            "gpu inventory",
            timeoutSeconds = 6,
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
            timeoutSeconds = 12,
        )

        return parseNetworkSummary(networkSummary)
    }

    private suspend fun collectStorageInternal(machine: MachineEntity, route: RouteResolution): StorageMetric {
        if (route.kind == RouteKind.UNREACHABLE) {
            return StorageMetric(
                scope = machine.storageScopeMode,
                used = unavailable("machine unreachable"),
                total = unavailable("machine unreachable"),
            )
        }

        val aggregateAllCommand =
            "df -B1 -x tmpfs -x devtmpfs --output=used,size | awk 'NR>1 {u+=\$1; s+=\$2} END {print u\" \"s}'"
        val scopedCommand =
            when (machine.storageScopeMode) {
                StorageScopeMode.ALL -> aggregateAllCommand
                StorageScopeMode.PER_DEVICE ->
                    "df -B1 -x tmpfs -x devtmpfs --output=source,used,size,target | awk 'NR>1 && \$4==\"/\" {src=\$1} NR>1 && src!=\"\" && \$1==src {u+=\$2; s+=\$3} END {if(src!=\"\") print u\" \"s}'"
                StorageScopeMode.PER_MOUNT ->
                    "df -B1 -x tmpfs -x devtmpfs --output=used,size,target | awk 'NR>1 && \$3==\"/\" {print \$1\" \"\$2; exit}'"
            }

        val scopedResult = command(machine, scopedCommand, "storage", timeoutSeconds = 8)
        val raw = if (scopedResult.isBlank()) {
            command(machine, aggregateAllCommand, "storage", timeoutSeconds = 8)
        } else {
            scopedResult
        }
        val storageParts = raw.split(" ").filter { it.isNotBlank() }

        return StorageMetric(
            scope = machine.storageScopeMode,
            used = bytesToGbField(storageParts.getOrNull(0).orEmpty(), "storage used"),
            total = bytesToGbField(storageParts.getOrNull(1).orEmpty(), "storage total"),
        )
    }

    private suspend fun command(machine: MachineEntity, cmd: String, label: String, timeoutSeconds: Long = 6): String =
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
            if (parts.size >= 4) {
                val name = parts[0]
                val clock = parts[1]
                val vram = parts[2]
                val usage = parts[3]
                add(
                    GpuMetric(
                        name = available(name),
                        clock = mhzToGhzField(clock, "gpu clock"),
                        vram = mibToGbField(vram, "gpu vram"),
                        generation = mapGpuGeneration(name),
                        speed = mhzToGhzField(clock, "gpu speed"),
                        usage = percentField(usage, "gpu usage"),
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
                    generation = mapGpuGeneration(gpuName),
                    speed = unavailable("not exposed by driver"),
                    usage = unavailable("not exposed by driver"),
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
                    generation = mapGpuGeneration(gpuName),
                    speed = unavailable("speed unavailable"),
                    usage = unavailable("usage unavailable"),
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
                        generation = mapGpuGeneration(gpuName),
                        speed = unavailable("speed unavailable"),
                        usage = unavailable("usage unavailable"),
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
        threadsPerCore = unavailable(reason),
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
    )

    private fun unavailableGpu(reason: String): GpuMetric = GpuMetric(
        name = unavailable(reason),
        clock = unavailable(reason),
        vram = unavailable(reason),
        generation = unavailable(reason),
        speed = unavailable(reason),
        usage = unavailable(reason),
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
