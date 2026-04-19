package dev.themajorones.servermanager.network

import dev.themajorones.servermanager.data.MachineEntity

interface RouteResolver {
    suspend fun resolve(machine: MachineEntity): RouteResolution
}