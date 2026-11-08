package dev.shehran.rawcull.device

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream
import org.junit.Assume.assumeTrue

/**
 * Runs `sh` as the shell user, which can read and write shared storage that the app can't.
 * The script goes in on stdin, so paths with spaces only need [quote]: `executeShellCommand` itself
 * splits on every space and knows no quoting.
 */
object Shell {

    fun <T> run(script: String, read: (InputStream) -> T): T {
        assumeTrue("Shell access needs API 31+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val (stdout, stdin) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write("$script\nexit\n".toByteArray()) }
        return ParcelFileDescriptor.AutoCloseInputStream(stdout).use(read)
    }

    fun text(script: String): String = run(script) { it.readBytes().decodeToString() }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
