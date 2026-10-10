package app.lingleaf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import app.lingleaf.core.BundledStory
import app.lingleaf.core.DictionaryClient
import app.lingleaf.core.GutenbergClient
import app.lingleaf.core.HttpResponse
import app.lingleaf.core.Library
import app.lingleaf.ui.LingleafApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

class FakeSpeaker : Speaker {
    val spoken = mutableListOf<String>()
    override val isSpeaking: StateFlow<Boolean> = MutableStateFlow(false)

    override fun speak(
        text: String,
        rate: Float,
    ) {
        spoken += text
    }

    override fun stop() = Unit
}

private val dictionaryJson =
    """[{"word": "equal", "phonetic": "/ˈiːkwəl/",
         "meanings": [{"partOfSpeech": "adjective", "definitions": [{"definition": "The same in quantity."}]}]}]"""

private const val STORY = "All animals are equal, but some are more equal.\n\nThe end."

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val folder = TemporaryFolder()

    private lateinit var container: AppContainer
    private val speaker = FakeSpeaker()

    @Before
    fun setUp() {
        val http =
            app.lingleaf.core.HttpFetcher { url ->
                when {
                    url.endsWith("/equal") -> HttpResponse(200, dictionaryJson.toByteArray())
                    "gutendex" in url -> HttpResponse(200, """{"results": []}""".toByteArray())
                    else -> HttpResponse(404, ByteArray(0))
                }
            }
        container =
            AppContainer(
                library = Library(FileStorage(folder.root)),
                dictionary = DictionaryClient(http),
                gutenberg = GutenbergClient(http),
                translator = { text, _ -> if (text == "equal") "igual" else "[es] $text" },
                speaker = speaker,
                bundledStories = { listOf(BundledStory("The Red Boat", "A1", STORY)) },
            )
        container.library.load()
        container.library.seedIfNeeded(container.bundledStories())
        compose.setContent { LingleafApp(container) }
    }

    @Test
    fun tapAWordSeeItsCardAndSaveIt() {
        compose.onAllNodesWithText("The Red Boat").onFirst().performClick()

        val paragraph = hasText("All animals are equal", substring = true)
        compose.waitUntil(5_000) { compose.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }

        // Tap the middle of the word "equal" using the paragraph's real text layout.
        val node = compose.onAllNodes(paragraph).onFirst()
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val text = layouts.first().layoutInput.text.text
        val start = text.indexOf("equal")
        val box = layouts.first().getBoundingBox(start + 2)
        node.performTouchInput { click(Offset(box.center.x, box.center.y)) }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("/ˈiːkwəl/").assertExists()
        compose.onNodeWithText("[es] All animals are equal, but some are more equal.").assertExists()

        compose.onNodeWithText("Learn").performClick()
        compose.waitUntil(5_000) { container.library.state.value.word("equal") != null }

        val saved = container.library.state.value.word("equal")!!
        assertEquals("igual", saved.translation)
        assertEquals("/ˈiːkwəl/", saved.phonetic)
        assertTrue(container.library.state.value.isLearning("equal"))
    }

    @Test
    fun everyTappableElementHasALabel() {
        for (tab in listOf("Library", "Discover", "Words", "Settings")) {
            compose.onAllNodesWithText(tab).onFirst().performClick()
            compose.waitForIdle()
            val unlabeled =
                compose
                    .onAllNodes(hasClickAction())
                    .fetchSemanticsNodes()
                    .filter { node ->
                        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()
                        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("").orEmpty()
                        text.isBlank() && description.isBlank() && !node.hasLabelledChildren()
                    }
            assertTrue("Unlabeled tappable elements on $tab: ${unlabeled.map { it.config }}", unlabeled.isEmpty())
        }
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.hasLabelledChildren(): Boolean =
        children.any { child ->
            !child.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() ||
                !child.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() ||
                child.hasLabelledChildren()
        }

    @Test
    fun wordsScreenShowsSavedWordsAndReviewWorks() {
        container.library.saveWord("bay", learned = false, translation = "bahía", context = "The edge of the bay.")
        compose.onAllNodesWithText("Words").onFirst().performClick()
        compose.onNodeWithText("bahía").assertExists()
        compose.onNodeWithText("Review now").performClick()
        compose.onNodeWithText("Show meaning").performClick()
        compose.onNodeWithText("I know it").performClick()
        compose.onNodeWithText("All done!").assertExists()
        assertEquals(1, container.library.state.value.word("bay")!!.box)
    }
}
