package app.lingleaf

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import app.lingleaf.core.BundledStory
import app.lingleaf.core.DictionaryClient
import app.lingleaf.core.GutenbergClient
import app.lingleaf.core.HttpResponse
import app.lingleaf.core.Library
import app.lingleaf.core.NetworkException
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

private fun catalogBook(
    id: Int,
    title: String,
    author: String,
) = """{"id": $id, "title": "$title", "authors": [{"name": "$author"}],
       "formats": {"text/plain; charset=us-ascii": "https://www.gutenberg.org/ebooks/$id.txt.utf-8"}}"""

private val recommendedCatalog =
    listOf(
        catalogBook(21, "Aesop's Fables", "Aesop"),
        catalogBook(55, "The Wonderful Wizard of Oz", "Baum, L. Frank"),
        catalogBook(16, "Peter Pan", "Barrie, J. M."),
        catalogBook(2701, "Moby-Dick; Or, The Whale", "Melville, Herman"),
    ).joinToString(prefix = """{"results": [""", postfix = "]}")

/** Generous: CI runners are slow on the first test, and lookups run on real background threads. */
private const val WAIT_MS = 15_000L

private const val STORY = "All animals are equal, but some are more equal.\n\nThe end."

private const val ALICE_TITLE = "Alice's Adventures in Wonderland"
private const val ALICE_TEXT_URL = "https://www.gutenberg.org/ebooks/11.txt.utf-8"

private val aliceCatalog =
    """{"results": [{"id": 11, "title": "$ALICE_TITLE", "authors": [{"name": "Carroll, Lewis"}],
         "formats": {"text/plain; charset=utf-8": "$ALICE_TEXT_URL"}}]}"""

private val aliceText =
    "The Project Gutenberg eBook of Alice\r\nLicense\r\n*** START OF THE PROJECT GUTENBERG EBOOK ALICE ***\r\n\r\n" +
        "Down the Rabbit-Hole\r\n\r\nAlice was beginning to get very tired.\r\n" +
        "*** END OF THE PROJECT GUTENBERG EBOOK ALICE ***\r\nMore license"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val folder = TemporaryFolder()

    private lateinit var container: AppContainer
    private val speaker = FakeSpeaker()

    /** What Gutendex answers; tests set it before opening Discover. */
    private var catalogJson = """{"results": []}"""

    /** When true every request fails and translation throws, like a phone with no internet and no language model. */
    private var offline = false

    /** The system's preferred-language list. Only English: no translation language can be picked from it. */
    private var preferred = listOf("en-US")

    @Before
    fun setUp() {
        val http =
            app.lingleaf.core.HttpFetcher { url ->
                if (offline) throw NetworkException("No internet")
                when {
                    url.endsWith("/equal") -> HttpResponse(200, dictionaryJson.toByteArray())
                    "gutendex" in url -> HttpResponse(200, catalogJson.toByteArray())
                    url == ALICE_TEXT_URL -> HttpResponse(200, aliceText.toByteArray())
                    else -> HttpResponse(404, ByteArray(0))
                }
            }
        container =
            AppContainer(
                library = Library(FileStorage(folder.root)),
                dictionary = DictionaryClient(http),
                gutenberg = GutenbergClient(http),
                translator = { text, _ ->
                    if (offline) throw IllegalStateException("Language model not downloaded")
                    if (text == "equal") "igual" else "[es] $text"
                },
                speaker = speaker,
                bundledStories = { listOf(BundledStory("The Red Boat", "A1", STORY)) },
                preferredLanguages = { preferred },
                region = { "US" },
            )
        container.library.load()
        container.library.seedIfNeeded(container.bundledStories())
        // Most tests translate into Spanish, as if it had been chosen before.
        container.library.updateSettings { it.copy(targetLanguage = "es") }
        compose.setContent { LingleafApp(container) }
    }

    @Test
    fun tapAWordSeeItsCardAndSaveIt() {
        compose.onAllNodesWithText("The Red Boat").onFirst().performClick()

        val paragraph = hasText("All animals are equal", substring = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }

        // Tap the middle of the word "equal" using the paragraph's real text layout.
        val node = compose.onAllNodes(paragraph).onFirst()
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val text = layouts.first().layoutInput.text.text
        val start = text.indexOf("equal")
        val box = layouts.first().getBoundingBox(start + 2)
        node.performTouchInput { click(Offset(box.center.x, box.center.y)) }

        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("/ˈiːkwəl/").assertExists()
        compose.onNodeWithText("[es] All animals are equal, but some are more equal.").assertExists()

        compose.onNodeWithText("Save word").performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.word("equal") != null }

        val saved = container.library.state.value.word("equal")!!
        assertEquals("igual", saved.translation)
        assertEquals("es", saved.translationLanguage)
        assertEquals("/ˈiːkwəl/", saved.phonetic)
        assertTrue(container.library.state.value.isLearning("equal"))
    }

    @Test
    fun everyTappableElementHasALabel() {
        for (tab in listOf("Library", "Discover", "Words", "Settings")) {
            compose.onAllNodesWithText(tab).onFirst().performClick()
            compose.waitForIdle()
            assertTappablesAreLabelled(tab)
        }
    }

    @Test
    fun readerAndWordCardAreLabelled() {
        compose.onAllNodesWithText("The Red Boat").onFirst().performClick()
        val paragraph = hasText("All animals are equal", substring = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }
        assertTappablesAreLabelled("Reader")

        compose.onAllNodes(paragraph).onFirst().invokeCustomAction("Look up equal")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isNotEmpty() }
        assertTappablesAreLabelled("Word card")
    }

    @Test
    fun reviewIsLabelledAndAnnouncesTheMeaning() {
        container.library.saveWord(
            "bay",
            learned = false,
            translation = "bahía",
            context = "The edge of the bay.",
            translationLanguage = "es",
        )
        compose.onAllNodesWithText("Words").onFirst().performClick()
        compose.onNodeWithText("Review now").performClick()
        assertTappablesAreLabelled("Review")
        compose.onNodeWithContentDescription("Listen to bay").assertExists()

        compose.onNodeWithText("Show meaning").performClick()
        compose.waitForIdle()
        assertTappablesAreLabelled("Review (meaning shown)")
        // The answer replaces the button inside a polite live region, so TalkBack reads it.
        compose
            .onNode(
                hasText("bahía") and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)),
            )
            .assertExists()
    }

    @Test
    fun paragraphWordsAreLinksAndAccessibilityActions() {
        container.library.saveWord("animal", learned = false, translation = "animal", translationLanguage = "es")
        compose.onAllNodesWithText("The Red Boat").onFirst().performClick()
        val paragraph = hasText("All animals are equal", substring = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }

        val node = compose.onAllNodes(paragraph).onFirst().fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].first()
        // One link per word: "All animals are equal, but some are more equal."
        assertEquals(9, text.getLinkAnnotations(0, text.length).size)

        val labels = node.config[SemanticsActions.CustomActions].map { it.label }
        // Repeated words get one action; words being learned say so.
        assertEquals(
            listOf(
                "Look up All",
                "Look up animals, learning",
                "Look up are",
                "Look up equal",
                "Look up but",
                "Look up some",
                "Look up more",
            ),
            labels,
        )
    }

    // S15: deleting happens at once, with Undo instead of a confirmation dialog.
    @Test
    fun deletingAWordOffersUndo() {
        container.library.saveWord("bay", learned = false, translation = "bahía", translationLanguage = "es")
        compose.onAllNodesWithText("Words").onFirst().performClick()

        compose.onNodeWithContentDescription("Delete bay").performClick()
        compose.waitForIdle()
        assertTrue(container.library.state.value.word("bay") == null)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("“bay” deleted").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.word("bay") != null }
        assertEquals("bahía", container.library.state.value.word("bay")!!.translation)
    }

    // S4: Undo after "Save word" puts things back as they were.
    @Test
    fun undoAfterSaveWordRestoresTheWord() {
        compose.onAllNodesWithText("The Red Boat").onFirst().performClick()
        val paragraph = hasText("All animals are equal", substring = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(paragraph).onFirst().invokeCustomAction("Look up equal")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Save word").performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.word("equal") != null }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("“equal” saved to Words").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.word("equal") == null }
        assertTrue(container.library.state.value.words.isEmpty())
    }

    // S4, S15: tapping the checked "✓ Saved" removes the word at once, with Undo.
    @Test
    fun tappingTheCheckedButtonRemovesTheWordWithUndo() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onAllNodesWithText("The Red Boat").onFirst().performClick()
        val paragraph = hasText("All animals are equal", substring = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(paragraph).onFirst().invokeCustomAction("Look up equal")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save word").performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.word("equal") != null }

        // Once the card has closed, the word is being learned and its card shows the checked button.
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isEmpty() }
        val savedLabel = context.getString(R.string.card_saved)
        compose.onAllNodes(paragraph).onFirst().invokeCustomAction("Look up equal, learning")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(savedLabel).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(savedLabel).performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.word("equal") == null }
        val removed = context.getString(R.string.word_removed_message, "equal")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(removed).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.word("equal") != null }
        assertTrue(container.library.state.value.isLearning("equal"))
        assertEquals("igual", container.library.state.value.word("equal")!!.translation)
    }

    // S14: with no saved language and only English in the system list, the card asks.
    @Test
    fun withNoLanguageTheCardAsksForOne() {
        container.library.updateSettings { it.copy(targetLanguage = null) }
        compose.onAllNodesWithText("The Red Boat").onFirst().performClick()
        val paragraph = hasText("All animals are equal", substring = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(paragraph).onFirst().invokeCustomAction("Look up equal")

        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Translate into:").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Português").assertExists()
        compose.onNodeWithText("More languages").assertExists()
        compose.onNodeWithText("igual").assertDoesNotExist()
        // The rest of the card works meanwhile.
        compose.onNodeWithText("Save word").assertExists()

        compose.onNodeWithText("Español").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("es", container.library.state.value.settings.targetLanguage)
        compose.onNodeWithText("Translate into:").assertDoesNotExist()
    }

    private fun SemanticsNodeInteraction.invokeCustomAction(label: String) {
        val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { action.action() }
    }

    private fun assertTappablesAreLabelled(where: String) {
        val unlabeled =
            compose
                .onAllNodes(hasClickAction())
                .fetchSemanticsNodes()
                .filter { node ->
                    // Word links inside a paragraph are hidden from screen readers on purpose:
                    // the paragraph exposes them as links and custom actions instead.
                    if (SemanticsProperties.InvisibleToUser in node.config) return@filter false
                    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("").orEmpty()
                    val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("").orEmpty()
                    text.isBlank() && description.isBlank() && !node.hasLabelledChildren()
                }
        assertTrue("Unlabeled tappable elements on $where: ${unlabeled.map { it.config }}", unlabeled.isEmpty())
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.hasLabelledChildren(): Boolean =
        children.any { child ->
            !child.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() ||
                !child.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() ||
                child.hasLabelledChildren()
        }

    @Test
    fun filterRecommendedBooksByLevel() {
        catalogJson = recommendedCatalog
        compose.onAllNodesWithText("Discover").onFirst().performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasTestTag("level-filter-B1")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("level-filter-B1").performClick()
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("Peter Pan"))
        compose.onNodeWithText("Peter Pan").assertIsDisplayed()
        compose.onNodeWithText("Aesop's Fables").assertDoesNotExist()

        list.performScrollToIndex(0)
        compose.onNodeWithTag("level-filter-all").performClick()
        compose.onNodeWithText("Aesop's Fables").assertExists()
    }

    @Test
    fun wordsScreenShowsSavedWordsAndReviewWorks() {
        container.library.saveWord(
            "bay",
            learned = false,
            translation = "bahía",
            context = "The edge of the bay.",
            translationLanguage = "es",
        )
        compose.onAllNodesWithText("Words").onFirst().performClick()
        compose.onNodeWithText("bahía").assertExists()
        compose.onNodeWithText("Review now").performClick()
        compose.onNodeWithText("Show meaning").performClick()
        compose.onNodeWithText("I know it").performClick()
        compose.onNodeWithText("All done!").assertExists()
        assertEquals(1, container.library.state.value.word("bay")!!.box)
    }

    // S6 Listen
    @Test
    fun wordSentenceAndParagraphCanBeReadAloud() {
        container.library.addBook("Listening Practice", "Lingleaf", "The sun is up. All animals are equal.\n\nThe end.")
        compose.onAllNodesWithText("Listening Practice").onFirst().performClick()
        val paragraph = hasText("The sun is up.", substring = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Read aloud").performClick()
        assertEquals(listOf("The sun is up. All animals are equal."), speaker.spoken)

        compose.onAllNodes(paragraph).onFirst().invokeCustomAction("Look up equal")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Listen to equal").performClick()
        compose.onNodeWithContentDescription("Listen to the sentence").performClick()

        assertEquals(listOf("The sun is up. All animals are equal.", "equal", "All animals are equal."), speaker.spoken)
    }

    // S9 Discover: one-tap download into the library
    @Test
    fun getABookFromDiscoverAddsItToTheLibrary() {
        catalogJson = aliceCatalog
        compose.onAllNodesWithText("Discover").onFirst().performClick()
        val getButton = hasContentDescription("Get $ALICE_TITLE")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(getButton).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(getButton).performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.books.any { it.gutenbergId == 11 } }

        val book = container.library.state.value.books.single { it.gutenbergId == 11 }
        assertEquals(ALICE_TITLE, book.title)
        assertEquals("Lewis Carroll", book.author)
        assertEquals("B2", book.level)
        // The license header and footer are gone: only the two story paragraphs remain.
        assertEquals(listOf("Down the Rabbit-Hole", "Alice was beginning to get very tired."), container.library.openBook(book.id))

        compose.onAllNodesWithText("Library").onFirst().performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText(ALICE_TITLE).fetchSemanticsNodes().isNotEmpty())
    }

    // S10 Import goes through the system document picker, which Robolectric can't drive.
    // The import rules (title from the file name, boilerplate removed, empty files refused)
    // are covered by the core LibraryTest tests for Library.importText and Library.importTitle.

    // S12 Offline
    @Test
    fun withoutInternetTheCardSaysWhatIsUnavailableAndTheReaderStillWorks() {
        offline = true
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onAllNodesWithText("The Red Boat").onFirst().performClick()
        val paragraph = hasText("All animals are equal", substring = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }

        compose.onAllNodes(paragraph).onFirst().invokeCustomAction("Look up equal")
        val unavailable = context.getString(R.string.translation_unavailable)
        val noDefinition = context.getString(R.string.no_definition)
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText(unavailable).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(noDefinition).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(unavailable).assertExists()
        compose.onNodeWithText(noDefinition).assertExists()
        compose.onNodeWithText("igual").assertDoesNotExist()
        compose.onAllNodes(paragraph).onFirst().assertExists()
    }
}
