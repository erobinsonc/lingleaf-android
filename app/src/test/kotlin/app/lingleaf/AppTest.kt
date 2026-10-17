package app.lingleaf

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import app.lingleaf.core.BundledStory
import app.lingleaf.core.DictionaryClient
import app.lingleaf.core.FunctionWord
import app.lingleaf.core.GutenbergClient
import app.lingleaf.core.HttpResponse
import app.lingleaf.core.Library
import app.lingleaf.core.NetworkException
import app.lingleaf.ui.LingleafApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

/** Generous: CI runners are slow on the first test, and lookups run on real background threads. */
private const val WAIT_MS = 15_000L

private const val STORY = "All animals are equal, but some are more equal.\n\nCan we fix it?\n\nIt's late. He had gone.\n\nThe end."

private const val ALICE_TITLE = "Alice's Adventures in Wonderland"
private const val ALICE_TEXT_URL = "https://www.gutenberg.org/cache/epub/11/pg11.txt"

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
                    // Peter Pan is "not available right now", to show that message.
                    "cache/epub/16/" in url -> HttpResponse(404, ByteArray(0))
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
        // S15: the language button under the translation says what it is and that it opens a list.
        compose
            .onNode(hasContentDescription("Translation language: Español") and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
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
        dueNow("bay")
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
    fun aModalVerbShowsItsVerbGroupAndWhatItDoes() {
        compose.onAllNodesWithText("The Red Boat").onFirst().performClick()
        val paragraph = hasText("Can we fix it?")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onAllNodes(paragraph).onFirst()
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val box = layouts.first().getBoundingBox(1)
        node.performTouchInput { click(Offset(box.center.x, box.center.y)) }

        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("[es] Can we fix").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Modal verb").assertExists()
        compose.onNodeWithText("Can we fix").assertExists()
        compose.onNodeWithText("“can” + verb: be able to, or be allowed to.").assertExists()

        compose.onNodeWithText("Save word").performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.word("can we fix") != null }
        val saved = container.library.state.value.word("can we fix")!!
        assertEquals(FunctionWord.Kind.MODAL, saved.kind)
        assertEquals("can", saved.base)
        assertEquals("[es] Can we fix", saved.translation)
    }

    /** Opens the story and taps the character at [offset] of the paragraph [text]. */
    private fun tapInParagraph(
        text: String,
        offset: Int,
    ) {
        compose.onAllNodesWithText("The Red Boat").onFirst().performClick()
        val paragraph = hasText(text)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onAllNodes(paragraph).onFirst()
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val box = layouts.first().getBoundingBox(offset)
        node.performTouchInput { click(Offset(box.center.x, box.center.y)) }
    }

    @Test
    fun aPerfectSaysWhatItDoes() {
        tapInParagraph("It's late. He had gone.", "It's late. He had".indexOf("had") + 1)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("[es] had gone").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Helper verb").assertExists()
        compose.onNodeWithText("have").assertExists()
        compose.onNodeWithText("“had” + past participle: before another past moment: “had left”.").assertExists()
    }

    @Test
    fun aContractionSaysWhatItStandsFor() {
        tapInParagraph("It's late. He had gone.", 1)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("it is").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("“it's” = “it is”; “its” (no apostrophe) = belonging to it.").assertExists()
        compose.onNodeWithText("Helper verb").assertDoesNotExist()
        compose.onNodeWithText("Modal verb").assertDoesNotExist()

        compose.onNodeWithText("Save word").performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.word("it's") != null }
        val saved = container.library.state.value.word("it's")!!
        assertEquals(FunctionWord.Kind.CONTRACTION, saved.kind)
        assertEquals("it is", saved.base)
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

    // S4: Undo after changing a saved word puts back the previous record.
    @Test
    fun undoAfterChangingAKnownWordRestoresItAsKnown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val knownLabel = context.getString(R.string.card_known)
        val knowIt = hasText(context.getString(R.string.card_i_know_it)) and hasClickAction()
        compose.onAllNodesWithText("The Red Boat").onFirst().performClick()
        val paragraph = hasText("All animals are equal", substring = true)
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(paragraph).onFirst().invokeCustomAction("Look up equal")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isNotEmpty() }

        // "I know it" saves the word as known and says so.
        compose.onNode(knowIt).performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.word("equal")?.learned == true }
        val knownMessage = context.getString(R.string.word_known_message, "equal")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(knownMessage).fetchSemanticsNodes().isNotEmpty() }

        // Reopened once the card has closed, the card shows the word is known.
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodes(paragraph).onFirst().invokeCustomAction("Look up equal")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isNotEmpty() }
        compose
            .onNode(hasText(knownLabel) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

        // "Save word" moves it to learning; Undo puts it back as known.
        compose.onNodeWithText("Save word").performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.isLearning("equal") }
        val savedMessage = context.getString(R.string.word_saved_message, "equal")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(savedMessage).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.word("equal")?.learned == true }
        assertEquals(1, container.library.state.value.words.size)
        assertEquals("igual", container.library.state.value.word("equal")!!.translation)

        // Reopened, the card still shows the word as known, not saved.
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodes(paragraph).onFirst().invokeCustomAction("Look up equal")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("igual").fetchSemanticsNodes().isNotEmpty() }
        compose
            .onNode(hasText(knownLabel) and hasClickAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText(context.getString(R.string.card_saved)).assertDoesNotExist()
    }

    // S4, S15: tapping the checked "Saved" removes the word at once, with Undo.
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
        // The check mark is drawn, not read; the button is selected and its tap is announced as removing the word.
        assertEquals("Saved", savedLabel)
        val checked = compose.onNode(hasText(savedLabel) and hasClickAction())
        checked.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        assertEquals("Remove from Words", checked.fetchSemanticsNode().config[SemanticsActions.OnClick].label)

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
        dueNow("bay")
        compose.onAllNodesWithText("Words").onFirst().performClick()
        compose.onNodeWithText("bahía").assertExists()
        compose.onNodeWithText("Review now").performClick()
        compose.onNodeWithText("I remembered").assertDoesNotExist()
        compose.onNodeWithText("Show meaning").performClick()
        // The meaning brings the sentence, the word in bold, its translation (filled in on the device), then the answers.
        compose.onNodeWithText("The edge of the bay.").assertExists()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("[es] The edge of the bay.").fetchSemanticsNodes().isNotEmpty() }
        // "Again" repeats the word, recall first again.
        compose.onNodeWithText("Again").performClick()
        compose.onNodeWithText("Show meaning").performClick()
        compose.onNodeWithText("I remembered").performClick()
        compose.onNodeWithText("Done for today.").assertExists()
        compose.onNodeWithText("1 word tomorrow.").assertExists()
        assertEquals(1, container.library.state.value.word("bay")!!.box)
    }

    /** New words are first due tomorrow (SPEC S7); "Again" makes one due now. */
    private fun dueNow(word: String) = container.library.answerReview(word, correct = false)

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

    // S9 Discover: a search shows its results above the recommended books.
    @Test
    fun searchShowsResults() {
        catalogJson = aliceCatalog
        compose.onAllNodesWithText("Discover").onFirst().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("alice")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Results").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasContentDescription("Get $ALICE_TITLE")).onFirst().assertExists()
    }

    // S9 Discover: recommended books ship with the app; Get shows progress and turns into Read;
    // a missing book says so in its row.
    @Test
    fun getABookFromDiscoverAddsItToTheLibrary() {
        compose.onAllNodesWithText("Discover").onFirst().performClick()
        val list = compose.onNode(hasScrollToIndexAction())
        val getButton = hasContentDescription("Get $ALICE_TITLE")
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        list.performScrollToNode(getButton)
        compose.onNode(getButton).performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.books.any { it.gutenbergId == 11 } }

        val book = container.library.state.value.books.single { it.gutenbergId == 11 }
        assertEquals(ALICE_TITLE, book.title)
        assertEquals("Lewis Carroll", book.author)
        assertEquals("B2", book.level)
        // The license header and footer are gone: only the two story paragraphs remain.
        assertEquals(listOf("Down the Rabbit-Hole", "Alice was beginning to get very tired."), container.library.openBook(book.id))
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasContentDescription("Read $ALICE_TITLE")).fetchSemanticsNodes().isNotEmpty() }

        list.performScrollToNode(hasContentDescription("Get Peter Pan"))
        compose.onNode(hasContentDescription("Get Peter Pan")).performClick()
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodesWithText("This book isn't available right now.").fetchSemanticsNodes().isNotEmpty()
        }

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

        // Saving never waits for the translation: the card closes and the word is saved without one.
        compose.onNodeWithText("Save word").performClick()
        compose.waitUntil(WAIT_MS) { container.library.state.value.word("equal") != null }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(unavailable).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Save word").assertDoesNotExist()
        val saved = container.library.state.value.word("equal")!!
        assertEquals("", saved.translation)
        assertNull(saved.translationLanguage)
        assertTrue(container.library.state.value.isLearning("equal"))
    }

    // S12: a word saved without a translation says so in Review until one can be made.
    @Test
    fun reviewSaysWhenAWordHasNoTranslationYet() {
        offline = true
        val context = ApplicationProvider.getApplicationContext<Context>()
        container.library.saveWord("bay", learned = false, context = "The edge of the bay.")
        dueNow("bay")
        compose.onAllNodesWithText("Words").onFirst().performClick()
        compose.onNodeWithText("Review now").performClick()
        compose.onNodeWithText("Show meaning").performClick()

        val noTranslation = context.getString(R.string.no_translation)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(noTranslation).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("", container.library.state.value.word("bay")!!.translation)
    }
}
