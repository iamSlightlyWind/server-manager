package dev.themajorones.servermanager.metrics

import dev.themajorones.servermanager.data.StorageScopeMode

data class ResourceField(
    val value: String,
    val isAvailable: Boolean,
    val reason: String? = null,
)

data class CpuMetrics(
    val name: ResourceField,
    val threads: ResourceField,
    val cores: ResourceField,
    val clock: ResourceField,
    val usage: ResourceField,
    val temperature: ResourceField,
)

data class RamMetrics(
    val amount: ResourceField,
    val generation: ResourceField,
    val speed: ResourceField,
    val clock: ResourceField,
    val usage: ResourceField,
    val zram: ResourceField,
    val swap: ResourceField,
)

data class GpuMetric(
    val name: ResourceField,
    val clock: ResourceField,
    val vram: ResourceField,
    val vramUsage: ResourceField,
    val generation: ResourceField,
    val speed: ResourceField,
    val usage: ResourceField,
    val powerDraw: ResourceField,
)

data class NetworkMetric(
    val name: String,
    val maxBandwidth: ResourceField,
    val currentDown: ResourceField,
    val currentUp: ResourceField,
    val ip: ResourceField,
    val mac: ResourceField,
)

data class StorageItemMetric(
    val label: String,
    val used: ResourceField,
    val total: ResourceField,
    val usage: ResourceField,
)

data class StorageMetric(
    val scope: StorageScopeMode,
    val used: ResourceField,
    val total: ResourceField,
    val items: List<StorageItemMetric> = emptyList(),
)

data class MachineMetricsSnapshot(
    val cpu: CpuMetrics,
    val ram: RamMetrics,
    val gpus: List<GpuMetric>,
    val networks: List<NetworkMetric>,
    val storage: StorageMetric,
    val diagnostics: List<String>,
)

fun unavailable(reason: String): ResourceField = ResourceField(value = "N/A", isAvailable = false, reason = reason)

fun available(value: String): ResourceField = ResourceField(value = value, isAvailable = true)
