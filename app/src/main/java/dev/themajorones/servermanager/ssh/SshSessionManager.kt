package dev.themajorones.servermanager.ssh

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dev.themajorones.servermanager.data.AuthMode
import dev.themajorones.servermanager.data.MachineEntity
import dev.themajorones.servermanager.security.CredentialStore
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SshSessionManager(
    private val credentialStore: CredentialStore,
    private val cacheDir: File,
) : RemoteCommandExecutor {
    private val tag = "SshSessionManager"
    private val sessions = ConcurrentHashMap<Long, Session>()
    private val sessionMutex = Mutex()
    private val commandMutexes = ConcurrentHashMap<Long, Mutex>()

    override suspend fun execute(machine: MachineEntity, command: String, timeoutSeconds: Long): String =
        withContext(Dispatchers.IO) {
            val commandMutex = commandMutexes.getOrPut(machine.id) { Mutex() }
            commandMutex.withLock {
                val session = getOrCreate(machine)
                try {
                    runCommand(session, command, timeoutSeconds)
                } catch (error: Exception) {
                    Log.w(tag, "Command failed on machine=${machine.id} host=${machine.host}, reconnecting", error)
                    invalidate(machine.id)
                    val retry = getOrCreate(machine)
                    runCommand(retry, command, timeoutSeconds)
                }
            }
        }

    override suspend fun isReachable(machine: MachineEntity, timeoutSeconds: Long): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val output = execute(machine, "echo connected", timeoutSeconds)
                output.contains("connected")
            }.onFailure { error ->
                Log.w(tag, "Reachability failed machine=${machine.id} host=${machine.host}", error)
            }.getOrDefault(false)
        }

    override suspend fun invalidate(machineId: Long) {
        sessionMutex.withLock {
            sessions.remove(machineId)?.disconnect()
        }
    }

    private suspend fun getOrCreate(machine: MachineEntity): Session =
        sessionMutex.withLock {
            sessions[machine.id]?.let { existing ->
                if (existing.isConnected) {
                    return@withLock existing
                }
                runCatching { existing.disconnect() }
                sessions.remove(machine.id)
            }

            val jsch = JSch()
            val session = jsch.getSession(machine.username, machine.host, machine.port)

            val config = Properties().apply {
                setProperty("StrictHostKeyChecking", "no")
                setProperty("PreferredAuthentications", "publickey,password,keyboard-interactive")
            }
            session.setConfig(config)

            when (machine.authMode) {
                AuthMode.PASSWORD -> {
                    val password = credentialStore.getPassword(machine.id)
                        ?: throw IllegalStateException("Missing password for machine ${machine.id}")
                    session.setPassword(password)
                }

                AuthMode.SSH_KEY -> {
                    val keyText = credentialStore.getPrivateKey(machine.id)
                        ?: throw IllegalStateException("Missing private key for machine ${machine.id}")
                    val keyFile = File(cacheDir, "machine_${machine.id}.key")
                    keyFile.writeText(keyText)
                    keyFile.setReadable(true, true)
                    keyFile.setWritable(true, true)
                    jsch.addIdentity(keyFile.absolutePath)
                }
            }

            session.connect(8_000)
            sessions[machine.id] = session
            session
        }

    private fun runCommand(session: Session, command: String, timeoutSeconds: Long): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(wrapInPosixShell(command))
        channel.setInputStream(null)
        channel.setErrStream(null)

        try {
            channel.connect((timeoutSeconds * 1_000).toInt())
            val out = channel.inputStream.bufferedReader().readText().trim()
            return out
        } finally {
            channel.disconnect()
        }
    }

    private fun wrapInPosixShell(command: String): String {
        val escaped = command.replace("'", "'\"'\"'")
        return "sh -lc '$escaped'"
    }
}
