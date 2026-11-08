package dev.shehran.rawcull.device

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.AssumptionViolatedException
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The rules every device test class uses, outermost first: known issues, reset, then [inner]
 * (usually the Compose rule, so the reset happens before `MainActivity` launches).
 */
fun deviceRules(inner: TestRule): RuleChain =
    RuleChain.outerRule(KnownIssueRule()).around(QaResetRule()).around(inner)

/**
 * R8 D12: a failing `@KnownIssue` test is reported as skipped with [MARKER] and its reason, so it
 * never fails the run; `scripts/device-test.sh` lists it under *Known issues*.
 */
class KnownIssueRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val known = description.getAnnotation(KnownIssue::class.java)
                ?: description.testClass?.getAnnotation(KnownIssue::class.java)
                ?: return base.evaluate()
            try {
                base.evaluate()
            } catch (e: AssumptionViolatedException) {
                throw e
            } catch (t: Throwable) {
                throw AssumptionViolatedException("$MARKER ${known.reason}: $t", t)
            }
        }
    }

    companion object {
        const val MARKER = "KNOWN ISSUE"
    }
}

/**
 * Files the runner pulls after a run (R8 D13): `screens/` goes to `build/device-screens/`, and each
 * `bench/<name>.json` (a flat object of metric → milliseconds) into the report's benchmark table.
 */
object DeviceOut {
    private val root: File
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "device-out")

    fun screens(): File = File(root, "screens").apply { mkdirs() }

    fun bench(): File = File(root, "bench").apply { mkdirs() }
}
