package ir.solina.fm

import android.os.RemoteException
import java.util.concurrent.TimeUnit

/** Executed by Shizuku under its shell-level user service, not as root. */
class ShellUserService : IUserService.Stub() {
    @Throws(RemoteException::class)
    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                "__SOLINA_ERROR__:timeout"
            } else if (process.exitValue() == 0) output
            else "__SOLINA_ERROR__:${process.exitValue()}:$output"
        } catch (e: Exception) {
            "__SOLINA_ERROR__:${e.message ?: "unknown"}"
        }
    }
}
