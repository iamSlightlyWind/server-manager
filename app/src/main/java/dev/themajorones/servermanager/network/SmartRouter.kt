package dev.themajorones.servermanager.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.themajorones.servermanager.data.MachineEntity
import dev.themajorones.servermanager.data.MachineRepository
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RouteKind {
    DIRECT,
    PARENT_FALLBACK,
    UNREACHABLE,
}

data class RouteResolution(
    val kind: RouteKind,
    val candidateHost: String,
    val parentPath: List<Long>,
    val diagnostics: List<String>,
)

class SmartRouter(
    private val context: Context,
    private val repository: MachineRepository,
) : RouteResolver {
    override suspend fun resolve(machine: MachineEntity): RouteResolution =
        withContext(Dispatchers.IO) {
            val diagnostics = mutableListOf<String>()
            val activeLink = detectActiveLinkType()
            diagnostics += "active_link=$activeLink"

            val directReachable = canConnect(machine.host, machine.port)
            diagnostics += "direct:${machine.host}:${machine.port}=$directReachable"
            if (directReachable) {
                return@withContext RouteResolution(
                    kind = RouteKind.DIRECT,
                    candidateHost = machine.host,
                    parentPath = emptyList(),
                    diagnostics = diagnostics,
                )
            }

            val parentPath = findReachableParentPath(machine.id, depth = 0, visited = mutableSetOf(), diagnostics)
            if (parentPath != null) {
                return@withContext RouteResolution(
                    kind = RouteKind.PARENT_FALLBACK,
                    candidateHost = machine.host,
                    parentPath = parentPath,
                    diagnostics = diagnostics,
                )
            }

            RouteResolution(
                kind = RouteKind.UNREACHABLE,
                candidateHost = machine.host,
                parentPath = emptyList(),
                diagnostics = diagnostics,
            )
        }

    private suspend fun findReachableParentPath(
        machineId: Long,
        depth: Int,
        visited: MutableSet<Long>,
        diagnostics: MutableList<String>,
    ): List<Long>? {
        if (depth >= 5) {
            diagnostics += "parent_depth_limit_reached"
            return null
        }
        if (!visited.add(machineId)) {
            diagnostics += "cycle_detected@$machineId"
            return null
        }

        val parentIds = repository.getParentIds(machineId)
        for (parentId in parentIds) {
            val parent = repository.getMachine(parentId) ?: continue
            val reachable = canConnect(parent.host, parent.port)
            diagnostics += "parent:${parent.id}:${parent.host}:${parent.port}=$reachable"
            if (reachable) {
                return listOf(parent.id)
            }
            val nested = findReachableParentPath(parent.id, depth + 1, visited, diagnostics)
            if (nested != null) {
                return listOf(parent.id) + nested
            }
        }
        return null
    }

    private fun canConnect(host: String, port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
            }
            true
        }.getOrDefault(false)

    private fun detectActiveLinkType(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = manager.activeNetwork ?: return "none"
        val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return "none"

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    }
}
