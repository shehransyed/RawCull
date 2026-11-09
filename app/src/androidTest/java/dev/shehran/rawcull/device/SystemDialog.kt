package dev.shehran.rawcull.device

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.util.regex.Pattern

/**
 * R8.7: answers a system dialog (the MediaStore trash request) with UiAutomator. The dialog belongs
 * to another package (PermissionController, or the vendor's own), so buttons are found by their
 * label in any package except the app's own.
 */
object SystemDialog {
    private val ALLOW = Pattern.compile("(?i)allow|move to trash|restore|ok|confirm|yes")
    private val DENY = Pattern.compile("(?i)deny|don.?t allow|cancel|no")

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun allow(timeoutMs: Long = 15_000): Boolean = tap(ALLOW, "Allow", timeoutMs)

    fun deny(timeoutMs: Long = 15_000): Boolean = tap(DENY, "Deny", timeoutMs)

    /** Labels of the clickable things other packages show right now; for failure messages. */
    fun buttons(): List<String> {
        val own = instrumentation.targetContext.packageName
        return UiDevice.getInstance(instrumentation)
            .findObjects(By.clickable(true))
            .filter { it.applicationPackage != own }
            .mapNotNull { it.text }
    }

    private fun tap(label: Pattern, what: String, timeoutMs: Long): Boolean {
        val device = UiDevice.getInstance(instrumentation)
        val own = instrumentation.targetContext.packageName
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val button: UiObject2? = device.findObjects(By.clickable(true).text(label))
                .firstOrNull { it.applicationPackage != own }
            if (button != null) {
                button.click()
                return true
            }
            Thread.sleep(250)
        }
        android.util.Log.w("RawCull.QaDialog", "No \"$what\" button in $timeoutMs ms; foreground ${device.currentPackageName}")
        runCatching { device.takeScreenshot(java.io.File(DeviceOut.screens(), "dialog-missing-${what.lowercase()}.png")) }
        return false
    }
}

private fun everything(device: UiDevice): List<String> =
    device.findObjects(By.pkg(java.util.regex.Pattern.compile(".*"))).take(40)
        .map { "${it.applicationPackage}:${it.className}:${it.text ?: it.contentDescription ?: it.resourceName}" }
