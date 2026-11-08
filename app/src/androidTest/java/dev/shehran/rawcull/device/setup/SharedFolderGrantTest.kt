package dev.shehran.rawcull.device.setup

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import dev.shehran.rawcull.device.SharedFolder
import dev.shehran.rawcull.qa.QaGrantActivity
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R8 D9 b: grants `Download/RawCullQA` through DocumentsUI, driven by UiAutomator. Returns at once
 * when the grant is already there. `scripts/device-test.sh` runs it before every suite, on its own.
 */
@RunWith(AndroidJUnit4::class)
class SharedFolderGrantTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun grantSharedFolder() {
        SharedFolder.ensureExists()
        if (SharedFolder.isGranted(context)) return

        val device = UiDevice.getInstance(instrumentation)
        context.startActivity(
            Intent(context, QaGrantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        tap(device.find(By.text(Pattern.compile("(?i)use this folder")), "Use this folder"))
        // The confirmation is an AlertDialog; its positive button is "Allow".
        tap(
            device.wait(Until.findObject(By.res("android:id/button1")), WAIT_MS)
                ?: device.find(By.text(Pattern.compile("(?i)allow")), "Allow"),
        )

        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!SharedFolder.isGranted(context) && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        device.pressHome()
        assertTrue(SharedFolder.FALLBACK, SharedFolder.isGranted(context))
    }

    private fun UiDevice.find(selector: androidx.test.uiautomator.BySelector, what: String): UiObject2 =
        wait(Until.findObject(selector), WAIT_MS) ?: throw AssertionError("No \"$what\" in DocumentsUI. ${SharedFolder.FALLBACK}")

    private fun tap(button: UiObject2) {
        button.click()
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val POLL_MS = 200L
    }
}
