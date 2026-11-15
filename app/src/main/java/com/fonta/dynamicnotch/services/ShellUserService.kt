package com.fonta.dynamicnotch.services

import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal fun runShellCommand(binary: String, command: String): Boolean = runCatching {
    val process = ProcessBuilder(binary, "-c", command).redirectErrorStream(true).start()
    process.outputStream.close()
    if (!process.waitFor(8, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        false
    } else {
        process.exitValue() == 0
    }
}.getOrDefault(false)

/**
 * Shizuku UserService running with shell (UID 2000) or root privileges so non-root devices
 * with Shizuku can change Samsung's status-bar chip and Now Bar settings without a computer.
 */
@Keep
class ShellUserService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun run(command: String): Boolean = runShellCommand("sh", command)
}
