package dev.shehran.rawcull.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.shehran.rawcull.data.model.LibraryLocation
import dev.shehran.rawcull.data.model.PhotoCollection
import dev.shehran.rawcull.library.LibraryScope
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Collections section of the folder pane, in the narrow drawer and the wide side pane (D5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CollectionsPaneTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = ArrayList<Long>()
    private val toggled = ArrayList<Long>()
    private val targets = ArrayList<Long?>()

    private fun row(id: Long, name: String, parent: Long? = null, isSet: Boolean = false) =
        PhotoCollection(id = id, locationId = 1, parentId = parent, name = name, isSet = isSet, createdAt = id)

    private val state = LibraryUiState(
        activeLocation = LibraryLocation(id = 1, treeUri = "tree", displayName = "Shoots", addedAt = 0, lastOpenedAt = 0),
        collections = listOf(row(1, "Trips", isSet = true), row(2, "Iceland", parent = 1), row(3, "Portraits")),
        expandedCollectionSets = setOf(1),
        scope = LibraryScope.Collection(3),
        targetCollectionId = 3,
    )

    private fun show(uiState: LibraryUiState, widthDp: Int) {
        val actions = FolderPaneActions(
            onSelectLocation = {},
            onRelinkLocation = {},
            onAddLocation = {},
            onSelectScope = {},
            onShowLatestImport = {},
            onToggleFolder = {},
            collections = CollectionPaneActions(
                onOpen = { opened += it },
                onToggleSet = { toggled += it },
                onCreate = { _, _, _ -> },
                onRename = { _, _ -> },
                onMove = { _, _ -> },
                onDelete = {},
                onSetTarget = { targets += it },
            ),
        )
        compose.setContent {
            Box(Modifier.fillMaxSize()) { FolderPane(uiState, actions, Modifier.width(widthDp.dp)) }
        }
    }

    private fun checkSection() {
        compose.onNodeWithTag("collection_row_1").assertIsDisplayed()
        compose.onNodeWithTag("collection_row_2").assertIsDisplayed() // Inside the expanded set
        compose.onNodeWithTag("collection_row_3").assertIsDisplayed()
        compose.onNodeWithTag("collection_target_3", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithTag("collection_row_2").performClick()
        compose.onNodeWithTag("collection_row_1").performClick()
        assertEquals(listOf(2L), opened)
        assertEquals(listOf(1L), toggled)

        compose.onNodeWithTag("collection_menu_3").performClick()
        compose.onNodeWithTag("collection_clear_target").performClick()
        assertEquals(listOf<Long?>(null), targets)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `compact width lists sets, collections and the target`() {
        show(state, widthDp = 320)
        checkSection()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `expanded width lists sets, collections and the target`() {
        show(state, widthDp = 280)
        checkSection()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `a collapsed set hides what is inside and an empty section says so`() {
        show(state.copy(expandedCollectionSets = emptySet()), widthDp = 320)
        compose.onNodeWithTag("collection_row_1").assertIsDisplayed()
        compose.onNodeWithTag("collection_row_2").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `no collections shows the hint`() {
        show(state.copy(collections = emptyList(), scope = LibraryScope.All), widthDp = 320)
        compose.onNodeWithTag("collections_empty").assertIsDisplayed()
    }
}
