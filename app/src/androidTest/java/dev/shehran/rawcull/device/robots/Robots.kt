package dev.shehran.rawcull.device.robots

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import dev.shehran.rawcull.MainActivity

typealias MainRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

const val DEFAULT_TIMEOUT_MS = 20_000L

fun hasTestTagPrefix(prefix: String): SemanticsMatcher =
    SemanticsMatcher("TestTag starts with '$prefix'") {
        it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith(prefix) == true
    }

/** The activity-scoped ViewModel the screens use (same default key as Compose's `viewModel()`). */
inline fun <reified T : ViewModel> MainRule.activityViewModel(factory: ViewModelProvider.Factory): T {
    var vm: T? = null
    runOnUiThread { vm = ViewModelProvider(activity as ComponentActivity, factory)[T::class.java] }
    return checkNotNull(vm)
}

fun MainRule.countNodes(matcher: SemanticsMatcher): Int =
    onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

fun MainRule.awaitNode(matcher: SemanticsMatcher, timeoutMs: Long = DEFAULT_TIMEOUT_MS) =
    waitUntil(timeoutMs) { countNodes(matcher) > 0 }
