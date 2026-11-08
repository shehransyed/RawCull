package dev.shehran.rawcull.device

import androidx.test.platform.app.InstrumentationRegistry
import dev.shehran.rawcull.qa.QaReset
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * R8 D11: a first start before each test (as `@Rule`) or class (as `@ClassRule`). Put it outside
 * the Compose rule, so the reset happens before `MainActivity` launches:
 * `RuleChain.outerRule(QaResetRule()).around(compose)`.
 */
class QaResetRule : ExternalResource() {
    override fun before(): Unit = runBlocking {
        QaReset.resetAll(InstrumentationRegistry.getInstrumentation().targetContext)
    }
}
