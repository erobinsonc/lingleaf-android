package app.lingleaf.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * How a homograph of the irregular-verb table ("left", "saw", "leaves") reads in its sentence
 * (SPEC, Shared logic, "Verb or noun"): a noun, a verb, or unsure. [base] is the base form it
 * leads to ("leave", "leaf"); unsure keeps the word itself. [step] is the rule that decided.
 */
data class Reading(
    val kind: Kind,
    val base: String,
    val step: Step,
    /** For a chosen row: the form as tapped, when its slot changes the meaning ("lies": está, se encuentra). */
    val form: String? = null,
) {
    /**
     * [HOMONYM]: the verb's regular twin ("lied" → lie, to say what isn't true); [WORD]: an
     * ordinary word, an -s or -ing form of the table no step proved ("the lights", "a meeting").
     */
    enum class Kind { NOUN, VERB, HOMONYM, WORD, EXPRESSION, UNSURE }

    /**
     * The steps of the rule, in SPEC order, then [UNSURE] (no step applied) and [CHOSEN] (the
     * learner picked this reading on the card).
     */
    enum class Step {
        NAME,
        PHRASE,
        FIXED,
        NOUN,
        PARTICIPLE_OPEN,
        BASE,
        PARTICIPLE,
        SUBJECT,
        NAME_S,
        SUBJECT_NOUN,
        NOUN_S,
        QUANTIFIER,
        CONJUNCTION,
        TABLE,
        QUOTE,
        S_SUBJECT,
        ING,
        SENSE,
        ORDINARY,
        EXPRESSION,
        TWIN_NOUN,
        UNSURE_NOUN,
        UNSURE_NOUN_FIRST,
        UNSURE,
        CHOSEN,
    }
}

/** Why the table was refused. */
class IrregularVerbsException(message: String) : Exception(message)

/**
 * The irregular-verb table (`irregular-verbs.json`, identical in lingleaf-ios): the only list of
 * irregular forms. It is bundled data, but read as strictly as outside data: a file that is too
 * big, too deep, of another schema version, with a missing or unknown field or a bad form is
 * refused, and the app goes on with an empty table (the regular rules still work).
 */
class IrregularVerbs private constructor(table: Table) {
    /** Every irregular form leading to its base ("stolen" → "steal"), homographs ("stole") left out. */
    val formToBase: Map<String, String>

    /** Every past participle of the table, "got" left out ("have got" means "have"). */
    val participles: Set<String>

    /** Participles spelled like a base of the table ("come", "read", "bet"). */
    val baseLikeParticiples: Set<String>

    /** Bases not marked as mostly nouns, for "had better" + verb. */
    val bases: Set<String>

    /** Every base of the table, nouns-first ones included. */
    val allBases: Set<String>

    /** US double-l bases tried before the single-l stem ("installed" → "install", then "instal"). */
    val llBases: Set<String> = table.llBases.toSet()

    private val verbsByBase: Map<String, Verb> = table.verbs.associateBy { it.base }
    private val homographs: Map<String, Homograph> = table.homographs.associateBy { it.form }
    private val context = table.context

    /**
     * The forms of the table that are not homographs, with what they are: a past or participle
     * (certain: the verb, or the twin for "lied"), or an -s or -ing form (a verb only where a step
     * proves it), and the twin that shares an -s or -ing form ("lying", "lies").
     */
    private val tableForms: Map<String, TableForm> =
        buildMap {
            for (verb in table.verbs) {
                val twin = verb.homonym
                val own = verb.past + verb.participle
                val slots =
                    (own + verb.present.orEmpty()).map { Triple(it, TableForm.Kind.CERTAIN, false) } +
                        Triple(verb.s, TableForm.Kind.S, false) + Triple(verb.ing, TableForm.Kind.ING, false) +
                        (twin?.let { (it.past + it.participle).filter { form -> form !in own } }.orEmpty()).map {
                            Triple(it, TableForm.Kind.CERTAIN, true)
                        }
                for ((form, kind, homonym) in slots) {
                    if (form == verb.base || form in homographs) continue
                    val existing = get(form)
                    if (existing == null || (existing.kind != TableForm.Kind.CERTAIN && kind == TableForm.Kind.CERTAIN)) {
                        val shared = twin?.takeIf { kind != TableForm.Kind.CERTAIN && (form == it.s || form == it.ing) }
                        put(form, TableForm(verb.base, kind, homonym, shared))
                    }
                }
            }
        }

    /** Past forms that are not also a base or a homograph ("shone", "told", "went"): finite verbs. */
    private val pastForms: Set<String> =
        table.verbs.flatMapTo(mutableSetOf()) { it.past }.filterTo(mutableSetOf()) { it !in verbsByBase && it !in homographs }

    init {
        val links = mutableMapOf<String, MutableSet<String>>()
        for (verb in table.verbs) {
            for (form in verb.forms()) if (form != verb.base) links.getOrPut(form) { mutableSetOf() } += verb.base
        }
        val map = mutableMapOf<String, String>()
        for ((form, toBases) in links) {
            if (form in homographs) continue
            if (toBases.size > 1 || form in verbsByBase) {
                throw IrregularVerbsException("$form leads to ${toBases.sorted()} or is a base, and is not a homograph")
            }
            map[form] = toBases.first()
        }
        formToBase = map
        participles = table.verbs.flatMapTo(mutableSetOf()) { it.participle + it.homonym?.participle.orEmpty() } - "got"
        baseLikeParticiples = participles.filterTo(mutableSetOf()) { it in verbsByBase }
        allBases = verbsByBase.keys
        bases = table.verbs.filterNot { it.nounFirst }.mapTo(mutableSetOf()) { it.base }
    }

    /** Whether [word] (normalised) is a homograph of the table, read from its sentence. */
    fun isHomograph(word: String) = word in homographs

    /**
     * Whether a word saved with a sense is always named by it in Words and Review ("to wind",
     * "the left", "to lie"; SPEC S4): a homograph of the table, a base used mostly as a noun
     * (`nounFirst`) or one with a regular twin.
     */
    fun namesItsSense(word: String): Boolean {
        val w = TextTools.normalize(word)
        val verb = verbsByBase[w]
        return w in homographs || verb?.nounFirst == true || verb?.homonym != null
    }

    /**
     * Whether Review's prompt shows the sentence [word] was saved from (SPEC S7): when two senses
     * of its spelling are saved ([saved]), or when its label alone doesn't tell the sense, a verb
     * with a regular twin (lie, hang) saved as the verb or as the twin. Not when the sentence is
     * just the word.
     */
    fun reviewShowsSentence(
        word: VocabWord,
        saved: Collection<VocabWord>,
    ): Boolean {
        if (word.context.isEmpty() || TextTools.normalize(word.context) == word.word) return false
        val twoSenses = saved.count { it.word == word.word && it.sense != null } >= 2
        val twin = (word.sense == Sense.VERB || word.sense == Sense.HOMONYM) && verbsByBase[word.word]?.homonym != null
        return twoSenses || twin
    }

    /** The base both readings of a homograph share ("tears" → "tear"), else null. */
    fun sharedBase(word: String): String? = homographs[word]?.takeIf { it.noun == it.verb }?.verb

    /**
     * Whether the word at [range] of [text] is an irregular form capitalised mid-sentence
     * ("Uncle Drew"; not all capitals): a name, with no irregular link.
     */
    fun isName(
        text: String,
        range: WordRange,
    ): Boolean {
        if (!range.fitsIn(text)) return false
        val surface = text.substring(range.start, range.end)
        if (surface.isEmpty() || !isTitlecase(surface) || surface == surface.uppercase()) return false
        if (TextTools.normalize(surface) !in formToBase) return false
        return at(text, range) { words, index -> isName(text, words, index) }
    }

    /** [isName] of word [index] of [words], the word ranges of [text]. */
    fun isName(
        text: String,
        words: List<WordRange>,
        index: Int,
    ): Boolean {
        val range = words.getOrNull(index)?.takeIf { it.fitsIn(text) && it.start < it.end } ?: return false
        val surface = text.substring(range.start, range.end)
        if (!isTitlecase(surface) || surface == surface.uppercase() || TextTools.normalize(surface) !in formToBase) return false
        return index > 0 && !sentenceStart.containsMatchIn(text.substring(words[index - 1].end, range.start))
    }

    /**
     * Runs [block] with the word ranges of [text] and the index of [range] in them; false or
     * null when [range] is not a word of [text]. Callers that look at every word of a text
     * tokenize it once and use the overloads that take the words and an index instead.
     */
    private inline fun <T> at(
        text: String,
        range: WordRange,
        missing: T,
        block: (List<WordRange>, Int) -> T,
    ): T {
        val words = TextTools.wordRanges(text)
        val index = words.indexOf(range)
        return if (index < 0) missing else block(words, index)
    }

    private inline fun at(
        text: String,
        range: WordRange,
        block: (List<WordRange>, Int) -> Boolean,
    ): Boolean = at(text, range, false, block)

    /**
     * The base the table gives the word at [range] of [text], when it differs from the word:
     * a homograph's in its sentence ("she left" → "leave"), else an irregular form's ("stolen" →
     * "steal"; not a name). Used for the card's base form when the dictionary can't be reached.
     */
    fun baseAt(
        text: String,
        range: WordRange,
    ): String? = if (range.fitsIn(text)) at(text, range, null) { words, index -> baseAt(text, words, index) } else null

    /** [baseAt] of word [index] of [words], the word ranges of [text]. */
    fun baseAt(
        text: String,
        words: List<WordRange>,
        index: Int,
    ): String? {
        val range = words.getOrNull(index)?.takeIf { it.fitsIn(text) } ?: return null
        val word = TextTools.normalize(text.substring(range.start, range.end))
        if (isHomograph(word) || word in tableForms) {
            val reading = resolve(text, words, index) ?: return null
            val certain = reading.kind in certainKinds
            return reading.base.takeIf { certain && reading.step != Reading.Step.NAME && it != word }
        }
        return null
    }

    /**
     * What the voice should say instead of the word at [range] of [text] ("to tear" → "tare",
     * "she had read" → "red"), or null to speak it as written. A respelling applies only to the
     * slot the reading proves: the base after to, a modal or do (or opening a command), the past
     * (or -s form) in a past (or present) verb reading of a homograph, the participle after have
     * or a passive be. It is looked up by the reading's base, never by the spelling alone: "to
     * wound" (hurt) is not "wownd".
     */
    fun spokenAt(
        text: String,
        range: WordRange,
    ): String? {
        if (!range.fitsIn(text)) return null
        val form = TextTools.normalize(text.substring(range.start, range.end))
        if (!isHomograph(form) && verbsByBase[formToBase[form] ?: form]?.spokenAs == null) return null
        return at(text, range, null) { words, index -> spokenAt(text, words, index) }
    }

    /** [spokenAt] of word [index] of [words], the word ranges of [text]. */
    fun spokenAt(
        text: String,
        words: List<WordRange>,
        index: Int,
    ): String? {
        val range = words.getOrNull(index)?.takeIf { it.fitsIn(text) } ?: return null
        val form = TextTools.normalize(text.substring(range.start, range.end))
        if (isHomograph(form) || form in tableForms) {
            val reading = resolve(text, words, index) ?: return null
            // A noun as written, unless its sentence proves a sound the voice wouldn't make ("led").
            if (reading.kind == Reading.Kind.NOUN) return nounSpoken(text, words, index)
            return slot(form, reading)?.let { verbsByBase[reading.base]?.spokenAs?.get(it) }
        }
        val verb = verbsByBase[formToBase[form] ?: form] ?: return null
        val spoken = verb.spokenAs ?: return null
        if (isName(text, words, index)) return null
        val head = Window(text, words, index).let { window -> window.head()?.let(window::tok) } ?: return null
        val slot =
            when {
                head in context.toAndModals && form == verb.base -> "base"
                head in participleHelpers && form in verb.participle -> "participle"
                else -> null
            }
        return slot?.let { spoken[it] }
    }

    /**
     * The slot of [form] that [reading] proves (a verb reading only): the base after to, a modal
     * or do (or opening a command), the participle after have or be or opening a sentence
     * ("Left alone, …"), else the past or -s form it is. A chosen reading proves its form's slot
     * when the form fills only one slot of the verb.
     */
    fun slot(
        form: String,
        reading: Reading,
    ): String? {
        if (reading.kind != Reading.Kind.VERB) return null
        val verb = verbsByBase[reading.base] ?: return null
        return when (reading.step) {
            Reading.Step.BASE -> "base".takeIf { form == verb.base }
            Reading.Step.PARTICIPLE, Reading.Step.PARTICIPLE_OPEN -> "participle".takeIf { form in verb.participle }
            // A form of the table, or a chosen reading: the one slot its spelling fills ("torn": participle).
            Reading.Step.CHOSEN, Reading.Step.TABLE, Reading.Step.QUOTE, Reading.Step.S_SUBJECT, Reading.Step.ING, Reading.Step.SENSE ->
                verb.slots(form).singleOrNull()
            else ->
                when (form) {
                    in verb.past -> "past"
                    verb.s -> "s"
                    else -> null
                }
        }
    }

    /**
     * What the voice says for [form] in [reading] instead of its spelling, or null: the respelling
     * of the slot the reading proves; a chosen reading whose form fills several slots ("wound":
     * past and participle) only when they all sound the same.
     */
    fun spokenOf(
        form: String,
        reading: Reading,
    ): String? = slotValue(form, reading) { it.spokenAs }

    /** The table's IPA of [form] in [reading], on the same terms as [spokenOf]. */
    fun ipaOf(
        form: String,
        reading: Reading,
    ): String? = slotValue(form, reading) { it.ipa }

    private fun slotValue(
        form: String,
        reading: Reading,
        values: (Verb) -> Map<String, String>?,
    ): String? {
        if (reading.kind != Reading.Kind.VERB) return null
        val verb = verbsByBase[reading.base] ?: return null
        val map = values(verb) ?: return null
        slot(form, reading)?.let { return map[it] }
        if (reading.step != Reading.Step.CHOSEN) return null
        val all = verb.slots(form).map { map[it] }
        return all.firstOrNull()?.takeIf { first -> all.all { it == first } }
    }

    /**
     * What Listen says on Review and Words for a saved [word] in [sense] (SPEC S7): a verb or the
     * regular twin with the table's spoken form of its base ("tare" for "tear"), a noun (or a word
     * without a sense) as written.
     */
    fun spokenHeadword(
        word: String,
        sense: String?,
    ): String = if (sense == Sense.VERB || sense == Sense.HOMONYM) spokenAs(TextTools.normalize(word), "base") ?: word else word

    /**
     * What Listen says on Review and Words for [word] (SPEC S7): a word saved unsure, its form in
     * each saved reading (once when they sound the same), as on the card; else [spokenHeadword].
     */
    fun spokenSaved(word: VocabWord): String {
        val meanings = word.meanings
        // A noun whose saved sentence proved a sound the voice wouldn't make: its IPA tells ("led").
        if (word.sense == Sense.NOUN) return nounSpokenFor(word.word, word.phonetic) ?: word.word
        if (!word.isUnsure || meanings == null) return spokenHeadword(word.word, word.sense)
        val shown = word.form ?: word.word
        return meanings
            .map { meaning ->
                val kind =
                    when (meaning.kind) {
                        Sense.NOUN -> Reading.Kind.NOUN
                        Sense.HOMONYM -> Reading.Kind.HOMONYM
                        else -> Reading.Kind.VERB
                    }
                spokenOf(word.word, Reading(kind, meaning.base, Reading.Step.CHOSEN)) ?: shown
            }.distinct()
            .joinToString(", ")
    }

    /**
     * What the card's Listen says (SPEC S6): the [headword], then the word at [range] of [text]
     * as written there when it differs ("wind", "wound"), each respelled for its proven slot (a
     * verb's headword is its base); unsure, the tapped form in each reading in the rows' order,
     * once when they sound the same, or only the [chosen] one.
     */
    fun listenText(
        headword: String,
        text: String,
        range: WordRange,
        chosen: Reading? = null,
    ): String {
        if (!range.fitsIn(text) || range.start >= range.end) return headword
        val form = text.substring(range.start, range.end)
        val normalized = TextTools.normalize(form)
        val words = TextTools.wordRanges(text)
        val index = words.indexOf(range).takeIf { it >= 0 }
        val rows = index?.let { choices(text, words, it) }.orEmpty()
        if (rows.isNotEmpty()) {
            val nounSpoken = index?.let { nounSpoken(text, words, it) }
            return (chosen?.let(::listOf) ?: rows)
                .map { spokenOf(normalized, it) ?: nounSpoken.takeIf { _ -> it.kind == Reading.Kind.NOUN } ?: form }
                .distinct()
                .joinToString(", ")
        }
        val formSpoken = index?.let { spokenAt(text, words, it) } ?: form
        if (TextTools.normalize(headword) == normalized) return formSpoken
        val reading = index?.let { resolve(text, words, it) }
        // An expression ("bound for") is said as the unit.
        if (reading?.kind == Reading.Kind.EXPRESSION) return headword
        val verb =
            if (reading != null) {
                reading.kind == Reading.Kind.VERB || reading.kind == Reading.Kind.HOMONYM
            } else {
                index?.let { baseAt(text, words, it) } != null
            }
        val head =
            when {
                verb -> spokenAs(TextTools.normalize(headword), "base") ?: headword
                // A noun ("leads" → "lead"): said as the sound its sentence proves, when the voice would say it wrong.
                reading?.kind == Reading.Kind.NOUN -> nounSpokenFor(headword, index?.let { nounIpa(text, words, it) }) ?: headword
                else -> headword
            }
        return "$head, $formSpoken"
    }

    /** What the voice says for [slot] of the verb [base] instead of its spelling, or null. */
    fun spokenAs(
        base: String,
        slot: String,
    ): String? = verbsByBase[base]?.spokenAs?.get(slot)

    /** The table's IPA for [slot] of the verb [base] (no slashes), or null. */
    fun ipa(
        base: String,
        slot: String,
    ): String? = verbsByBase[base]?.ipa?.get(slot)

    /** The table's Spanish meanings of the verb [base] (its regular twin's with [homonym]). */
    fun meanings(
        base: String,
        homonym: Boolean = false,
    ): List<String> = verbsByBase[base]?.let { if (homonym) it.homonym?.es else it.es }.orEmpty()

    /** Whether [form] is only a form of [base]'s regular twin ("lied", "hanged"). */
    fun isHomonymForm(
        form: String,
        base: String,
    ): Boolean {
        val verb = verbsByBase[base] ?: return false
        val twin = verb.homonym ?: return false
        val own = listOf(verb.base, verb.ing, verb.s) + verb.past + verb.participle + verb.present.orEmpty()
        return form !in own && form in (listOf(twin.ing, twin.s) + twin.past + twin.participle)
    }

    private val certainKinds = setOf(Reading.Kind.VERB, Reading.Kind.NOUN, Reading.Kind.HOMONYM, Reading.Kind.EXPRESSION)

    /** Whether the homograph [form]'s noun is pronounced as its sentence says (`nounIpa`: tear, lead, wind). */
    fun hasNounIpa(form: String): Boolean = homographs[TextTools.normalize(form)]?.nounIpa?.isNotEmpty() == true

    /**
     * How the homograph at [index] is pronounced as a noun in its sentence ("tears in her eyes" →
     * tɪr; "a tear in my shirt" → tɛr; "made of lead" → lɛd), or null when nothing proves it.
     */
    fun nounIpa(
        text: String,
        words: List<WordRange>,
        index: Int,
    ): String? = nounIpaRule(text, words, index)?.ipa

    /**
     * What the voice says for the noun at [index] instead of its spelling, when the rule its
     * sentence matched has a spoken form ("made of lead" → "led", "a tear in my shirt" → "tare").
     */
    fun nounSpoken(
        text: String,
        words: List<WordRange>,
        index: Int,
    ): String? = nounIpaRule(text, words, index)?.spokenAs

    /**
     * What the voice says for the noun [noun] pronounced [ipa] (a saved word's IPA, with or
     * without slashes), when the table says the voice would say it wrong: "lead" /lɛd/ → "led".
     */
    fun nounSpokenFor(
        noun: String,
        ipa: String?,
    ): String? {
        val sound = ipa?.takeIf { it.isNotBlank() } ?: return null
        return homographs[TextTools.normalize(noun)]
            ?.nounIpa
            ?.firstOrNull { it.spokenAs != null && DictionaryEntry.sameIpa(it.ipa, sound) }
            ?.spokenAs
    }

    /** The first `nounIpa` rule of the homograph at [index] that its sentence matches, in the table's order. */
    private fun nounIpaRule(
        text: String,
        words: List<WordRange>,
        index: Int,
    ): NounIpa? {
        val range = words.getOrNull(index)?.takeIf { it.fitsIn(text) } ?: return null
        val h = homographs[TextTools.normalize(text.substring(range.start, range.end))] ?: return null
        if (h.nounIpa.isEmpty()) return null
        val window = Window(text, words, index)
        val sentence = words.indices.filter { it != index }.mapTo(mutableSetOf(), window::tok)
        for (rule in h.nounIpa) {
            if (rule.after != null && window.prev(1) !in rule.after) continue
            if (rule.before != null && window.next(1) !in rule.before) continue
            if (rule.near != null && rule.near.none { it in sentence }) continue
            return rule
        }
        return null
    }

    /** The twin's noun meanings in Spanish ("lie": mentira), when [noun] is a twin's noun. */
    fun twinNounEs(noun: String): List<String>? =
        verbsByBase.values.firstNotNullOfOrNull { v -> v.homonym?.takeIf { it.noun == noun }?.nounEs }

    /** The English query for translating [reading] outside Spanish: the sense's own, else "to {base}" / "the {noun}". */
    fun query(reading: Reading): String {
        val verb = verbsByBase[reading.base]
        return when (reading.kind) {
            Reading.Kind.NOUN -> "the ${reading.base}"
            Reading.Kind.HOMONYM -> verb?.homonym?.query ?: "to ${reading.base}"
            Reading.Kind.EXPRESSION -> expressionOf(reading.base)?.query ?: reading.base
            else -> verb?.query ?: "to ${reading.base}"
        }
    }

    /** The sense's short English gloss ("recline", "say what isn't true"), for telling two rows apart. */
    fun gloss(reading: Reading): String? {
        val verb = verbsByBase[reading.base]
        return when (reading.kind) {
            Reading.Kind.HOMONYM -> verb?.homonym?.gloss
            Reading.Kind.VERB -> verb?.gloss
            Reading.Kind.EXPRESSION -> expressionOf(reading.base)?.gloss
            else -> null
        }
    }

    /** The Spanish meanings of an expression ("bound for": rumbo a, con destino a). */
    fun expressionEs(unit: String): List<String> = expressionOf(unit)?.es.orEmpty()

    /** The -s form's Spanish meaning of the verb [base] ("lies": estar, encontrarse), when [form] is that form. */
    fun sEs(
        base: String,
        form: String?,
    ): String? = verbsByBase[base]?.takeIf { form != null && it.s == form }?.sEs

    private fun expressionOf(unit: String): Expression? {
        val form = unit.substringBefore(' ')
        val next = unit.substringAfter(' ', "")
        return homographs[form]?.expressions?.firstOrNull { it.next == next }
    }

    /**
     * The two readings an unsure homograph offers the learner, most common first: a verb and its
     * noun ("to leave", "the left"; the noun first when the verb is mostly a noun, `nounFirst`), or
     * its two verbs when it has no noun ("to find", "to found") or is "lay" after I/you/we/they
     * ("to lie", "to lay"). Empty when the word at [index] isn't an unsure homograph.
     */
    fun choices(
        text: String,
        words: List<WordRange>,
        index: Int,
    ): List<Reading> {
        val range = words.getOrNull(index)?.takeIf { it.fitsIn(text) } ?: return emptyList()
        val surface = text.substring(range.start, range.end)
        val form = TextTools.normalize(surface)
        val entry = tableForms[form]
        if (entry != null) {
            val reading = resolve(text, words, index)?.takeIf { it.kind == Reading.Kind.UNSURE } ?: return emptyList()
            val verb = Reading(Reading.Kind.VERB, entry.base, Reading.Step.CHOSEN, form)
            return when (reading.step) {
                // "She tells lies": the twin's noun or the verb ("the lie — mentira" / "to lie — estar, encontrarse").
                Reading.Step.UNSURE_NOUN -> listOf(Reading(Reading.Kind.NOUN, entry.shared?.noun ?: form, Reading.Step.CHOSEN), verb)
                // "The bell rings": the verb or the noun, its regular lemma spelled like the base
                // ("to ring" / "the ring"; chosen, it is saved as "ring").
                Reading.Step.UNSURE_NOUN_FIRST -> listOf(verb, Reading(Reading.Kind.NOUN, entry.base, Reading.Step.CHOSEN, form))
                // lying, lies, hanging, hangs left unsure: the verb's sense and the twin's ("to lie — mentir").
                else ->
                    listOf(
                        Reading(Reading.Kind.VERB, entry.base, Reading.Step.CHOSEN),
                        Reading(Reading.Kind.HOMONYM, entry.base, Reading.Step.CHOSEN),
                    )
            }
        }
        val h = homographs[form] ?: return emptyList()
        val resolver = Resolver(text, words, index, surface, form, h)
        if (resolver.resolve().kind != Reading.Kind.UNSURE) return emptyList()
        val verb = Reading(Reading.Kind.VERB, h.verb, Reading.Step.CHOSEN)
        if (resolver.lieOrLay) return listOf(verb, Reading(Reading.Kind.VERB, "lay", Reading.Step.CHOSEN))
        val noun = h.noun?.takeIf { it.isNotEmpty() }
        if (noun == null) {
            val other = h.baseVerb?.takeIf { it != h.verb } ?: return listOf(verb)
            return listOf(verb, Reading(Reading.Kind.VERB, other, Reading.Step.CHOSEN))
        }
        val nounReading = Reading(Reading.Kind.NOUN, noun, Reading.Step.CHOSEN)
        return if (verbsByBase[h.verb]?.nounFirst == true) listOf(nounReading, verb) else listOf(verb, nounReading)
    }

    /**
     * Forms of have and be before a participle; not get ("got read" is rare), not 'd ("I'd read"
     * can be "I would read").
     */
    private val participleHelpers: Set<String> by lazy {
        context.haveAndBe.filterTo(mutableSetOf()) { it !in getForms && !it.endsWith("'d") }
    }

    /**
     * How the word at [range] of [text] reads: null when it is not a homograph of the table.
     * Only the words next to it count (only spaces between), within [text].
     */
    fun resolve(
        text: String,
        range: WordRange,
    ): Reading? {
        if (!range.fitsIn(text)) return null
        // Most words are neither a homograph nor a form of the table: no need to tokenize the text for them.
        val form = TextTools.normalize(text.substring(range.start, range.end))
        if (form !in homographs && form !in tableForms) return null
        return at(text, range, null) { words, index -> resolve(text, words, index) }
    }

    /**
     * [resolve] for word [index] of [words], the word ranges of [text]: constant work per
     * word, so reading every word of a paragraph stays linear in its length.
     */
    fun resolve(
        text: String,
        words: List<WordRange>,
        index: Int,
    ): Reading? {
        val range = words.getOrNull(index)?.takeIf { it.fitsIn(text) } ?: return null
        val surface = text.substring(range.start, range.end)
        val form = TextTools.normalize(surface)
        homographs[form]?.let { return Resolver(text, words, index, surface, form, it).resolve() }
        return tableForms[form]?.let { TableFormResolver(text, words, index, surface, form, it).resolve() }
    }

    /** The words around word [i] of [text]. */
    private open inner class Window(
        protected val text: String,
        protected val words: List<WordRange>,
        protected val i: Int,
    ) {
        /** Words [a] and [b] next to each other, only spaces between. */
        fun gapOk(
            a: Int,
            b: Int,
        ) = a >= 0 && b < words.size && text.substring(words[a].end, words[b].start).isBlank()

        fun tok(k: Int) = TextTools.normalize(text.substring(words[k].start, words[k].end))

        /** The [n]-th word before, if every gap up to the form is blank. */
        fun prev(n: Int): String? {
            val k = i - n
            if (k < 0 || (k until i).any { !gapOk(it, it + 1) }) return null
            return tok(k)
        }

        fun next(n: Int): String? {
            val k = i + n
            if (k >= words.size || (i until k).any { !gapOk(it, it + 1) }) return null
            return tok(k)
        }

        fun startsSentence(k: Int) = k == 0 || sentenceStart.containsMatchIn(text.substring(words[k - 1].end, words[k].start))

        fun titlecase(k: Int) = isTitlecase(text.substring(words[k].start, words[k].end))

        /** A listed adverb, or a lower-case word in -ly ("slowly"; not "Polly"). */
        fun isSkip(k: Int): Boolean {
            val t = tok(k)
            return t in context.skip || (t.endsWith("ly") && t.length > 3 && !titlecase(k))
        }

        /** The nearest word before the form past up to [limit] skip words. */
        fun head(limit: Int = 2): Int? {
            var k = i - 1
            var skipped = 0
            while (k >= 0 && gapOk(k, k + 1)) {
                if (isSkip(k) && skipped < limit) {
                    skipped++
                    k--
                    continue
                }
                return k
            }
            return null
        }

        /** A word that can head a noun phrase: not a listed function word or adjective, not in -ly or 's. */
        fun isNounWord(t: String) = t !in nounClosed && !t.endsWith("ly") && !t.endsWith("'s")

        /** "men", "people", "girls", "buses"; not "bus", "glass", "news". */
        fun isPlural(t: String) =
            t in context.pluralNouns ||
                (t.length > 3 && t.endsWith("s") && pluralNotEndings.none { t.endsWith(it) } && t !in context.notPlural)

        /** The index of the word before [k] when only spaces are between, else null. */
        fun back(k: Int): Int? = if (k >= 1 && gapOk(k - 1, k)) k - 1 else null

        /** The determiner's index for "determiner (+ adjective) + the noun at [k]", else null. */
        fun nounPhraseStart(k: Int): Int? {
            var j = back(k)
            if (j != null && tok(j) in context.adjectives) j = back(j)
            return j?.takeIf { tok(it) in context.subjectDeterminers }
        }

        /** The quantifier's index for "quantifier [of] [determiner] (+ adjective) + the plural noun at [k]", else null. */
        fun quantifierStart(k: Int): Int? {
            var j = back(k)
            if (j != null && (tok(j) in context.adjectives || tok(j) in context.pluralAdjectives) && tok(j) !in context.quantifiers) {
                j = back(j)
            }
            if (j == null) return null
            var q: Int? = null
            if (tok(j) in context.quantifiers) {
                q = j
            } else if (tok(j) in context.determiners) {
                val b = back(j)
                if (b != null && tok(b) in context.quantifiersBeforeDeterminer) q = b
                j = b
            }
            if (j == null) return q
            val o = if (tok(j) == "of") j else back(j)
            if (o != null && tok(o) == "of") {
                val b = back(o)
                if (b != null && (tok(b) in context.quantifiers || tok(b) in context.quantifiersBeforeOf)) return b
            }
            return q
        }

        /** A finite verb after the form in its clause: the form is then a noun ("Double lives in fiction are common"). */
        fun finiteVerbLater(): Boolean {
            // At most a long clause's words: a text without punctuation can't make every word scan to its end.
            for (j in i + 1 until minOf(words.size, i + 1 + MAX_CLAUSE_WORDS)) {
                if (clauseEnd.containsMatchIn(text.substring(words[j - 1].end, words[j].start))) return false
                val t = tok(j)
                if (t in context.clauseOpeners || t in relativePronouns) return false
                if (t in context.finiteVerbs || t in pastForms) return true
            }
            return false
        }

        /** A capitalised word opening the sentence that is probably a name ("Tom"; not "Wasted", "Double"). */
        fun looksLikeName(k: Int): Boolean {
            val t = tok(k)
            if (t in nounClosed || t in context.notNameStarts || t in context.nameOrPlant) return false
            if (nameNotEndings.any { t.endsWith(it) }) return false
            for ((suffix, cut) in verbSuffixes) {
                if (!t.endsWith(suffix) || t.length <= suffix.length + 2) continue
                val stem = t.dropLast(cut)
                val doubled = stem.length > 2 && stem.last() == stem[stem.length - 2] && isVerb(stem.dropLast(1))
                if (isVerb(stem) || isVerb(stem + "e") || doubled) {
                    return false
                }
            }
            return true
        }

        fun isVerb(s: String) = s in allBases || s in Verbs.regular

        /** The determiner at [d] starts the sentence or a clause. */
        fun opensClause(d: Int): Boolean {
            if (startsSentence(d) || clauseMark.containsMatchIn(text.substring(words[d - 1].end, words[d].start))) return true
            return gapOk(d - 1, d) && tok(d - 1) in context.clauseOpeners
        }
    }

    /** A form of the table that is not a homograph (tore, lights, meeting, lying), read from the words around it. */
    private inner class TableFormResolver(
        text: String,
        words: List<WordRange>,
        i: Int,
        private val surface: String,
        private val f: String,
        private val e: TableForm,
    ) : Window(text, words, i) {
        fun resolve(): Reading {
            val range = words[i]
            val name = !startsSentence(i) && titlecase(i) && surface != surface.uppercase()
            if (name || text.getOrNull(range.end) == '-' || text.getOrNull(range.start - 1) == '-') {
                return Reading(Reading.Kind.NOUN, f, Reading.Step.NAME)
            }
            // A past or participle (tore, torn, lain): the verb; lied, hanged: its regular twin.
            if (e.kind == TableForm.Kind.CERTAIN) {
                val kind = if (e.homonym) Reading.Kind.HOMONYM else Reading.Kind.VERB
                return Reading(kind, e.base, Reading.Step.TABLE)
            }
            val n1 = next(1)
            val p1 = prev(1)
            val p2 = prev(2)
            val dets = context.determiners + context.pluralDeterminers
            val afterDet =
                p1 in dets || (p1 != null && p1.endsWith("'s") && p1 !in context.haveAndBe) || (p1 in context.adjectives && p2 in dets)
            val k = head()
            val t = k?.let(::tok)
            var proven: Reading.Step? = null
            // A base that is more often a noun (light, ring, bear) after a noun subject needs an object word after it.
            val nounLike = verbsByBase[e.base]?.nounFirst == true
            val nextOk =
                n1 in context.objectStarts ||
                    (!nounLike && (n1 == null || e.shared == null || n1 in context.sFormNext || n1.endsWith("ly")))
            if (e.kind == TableForm.Kind.S && i >= 1 && quoteMark.containsMatchIn(text.substring(words[i - 1].end, range.start)) &&
                surface[0].isLowerCase()
            ) {
                // '"Look!" says Tom.': lower case after the quote, so the sentence goes on.
                proven = Reading.Step.QUOTE
            } else if (!afterDet && k != null && t != null) {
                if (e.kind == TableForm.Kind.S) {
                    val objectOrNoFinite by lazy { n1 in context.objectStarts || !finiteVerbLater() }
                    proven =
                        when {
                            t in context.sSubjects -> Reading.Step.S_SUBJECT
                            titlecase(k) && t != "i" && t !in context.subjects && !startsSentence(k) -> Reading.Step.S_SUBJECT
                            titlecase(k) && startsSentence(k) && k == i - 1 && looksLikeName(k) && nextOk && objectOrNoFinite ->
                                Reading.Step.S_SUBJECT
                            !titlecase(k) && k == i - 1 && isNounWord(t) && !isPlural(t) && nextOk && objectOrNoFinite ->
                                nounPhraseStart(k)?.takeIf(::opensClause)?.let { Reading.Step.S_SUBJECT }
                            // "The bell rings": a subject but no object after a base that is mostly a noun: unsure
                            // between the verb and the word as a noun ("to ring" / "the rings").
                            nounLike && e.shared == null && !titlecase(k) && k == i - 1 && isNounWord(t) && !isPlural(t) &&
                                !finiteVerbLater() && nounPhraseStart(k)?.takeIf(::opensClause) != null ->
                                return Reading(Reading.Kind.UNSURE, e.base, Reading.Step.UNSURE_NOUN_FIRST)
                            else -> null
                        }
                } else if (t in context.beForms || t in context.ingAfter) {
                    proven = Reading.Step.ING
                }
            }
            // -ing after a comma, "and" or "or", before an object word, a preposition or an adverb.
            if (e.kind == TableForm.Kind.ING && proven == null && !afterDet && i >= 1 && n1 != null &&
                (',' in text.substring(words[i - 1].end, range.start) || p1 == "and" || p1 == "or") &&
                (n1 in context.objectStarts || n1 in context.participleOpenNext || n1 in context.prepositions || n1.endsWith("ly"))
            ) {
                proven = Reading.Step.ING
            }
            // -ing opening the sentence before an object word, a listed word or an adverb, with a comma later.
            if (e.kind == TableForm.Kind.ING && proven == null && startsSentence(i) && n1 != null &&
                (n1 in context.objectStarts || n1 in context.participleOpenNext || (n1.endsWith("ly") && n1.length > 3)) &&
                ',' in text.substring(range.end).split(sentenceMark, limit = 2)[0]
            ) {
                proven = Reading.Step.ING
            }
            val twin = e.shared
            // "lies" can be the twin's noun ("a lie"): a noun after a determiner; unproven, unsure
            // between that noun and the verb ("the lie — mentira" / "to lie — estar, encontrarse").
            val twinNoun = twin?.noun
            if (twinNoun != null && e.kind == TableForm.Kind.S) {
                if (afterDet) return Reading(Reading.Kind.NOUN, twinNoun, Reading.Step.TWIN_NOUN)
                if (proven == null) return Reading(Reading.Kind.UNSURE, e.base, Reading.Step.UNSURE_NOUN)
            }
            // lying, lies, hanging, hangs: the next word tells the sense, else unsure.
            if (twin != null && !afterDet) {
                if (n1 in twin.homonymBefore) return Reading(Reading.Kind.HOMONYM, e.base, Reading.Step.SENSE)
                if (n1 in twin.verbBefore) return Reading(Reading.Kind.VERB, e.base, Reading.Step.SENSE)
                return Reading(Reading.Kind.UNSURE, f, Reading.Step.UNSURE)
            }
            if (proven != null) return Reading(Reading.Kind.VERB, e.base, proven)
            return Reading(Reading.Kind.WORD, f, Reading.Step.ORDINARY)
        }
    }

    private inner class Resolver(
        text: String,
        words: List<WordRange>,
        i: Int,
        private val surface: String,
        private val f: String,
        private val h: Homograph,
    ) : Window(text, words, i) {
        private val rules = h.rules.toSet()
        private val plural = "plural" in rules
        private val end = words[i].end

        /** Set when "lay" after I/you/we/they was left unsure: present "lay" or past "lie". */
        var lieOrLay = false
            private set

        fun noun(step: Reading.Step) = Reading(Reading.Kind.NOUN, h.noun!!, step)

        /** The form's expression the next words make ("bound for"; "bound to" + a base verb), else null. */
        fun expression(
            n1: String?,
            n2: String?,
        ): Expression? = h.expressions.firstOrNull { n1 == it.next && (!it.nextBase || n2 in allBases || n2 in Verbs.regular) }

        fun verb(
            step: Reading.Step,
            subject: String? = null,
        ): Reading {
            var base = h.verb
            if (f == "lay") {
                val n1 = next(1)
                val n2 = next(2)
                val obj = n1 in context.objectStarts && !(n1 in articles && n2 in context.layTime)
                if (subject in plainSubjects && !obj && n1 !in context.lieAfter) {
                    lieOrLay = true
                    return unsure()
                }
                base = if (obj) "lay" else "lie"
            }
            return Reading(Reading.Kind.VERB, base, step)
        }

        fun unsure() = Reading(Reading.Kind.UNSURE, f, Reading.Step.UNSURE)

        fun resolve(): Reading {
            // 1. A name ("Aunt Rose") or part of a hyphenated word ("left-handed").
            val range = words[i]
            val name = !startsSentence(i) && titlecase(i) && surface != surface.uppercase()
            if (name || text.getOrNull(range.end) == '-' || text.getOrNull(range.start - 1) == '-') {
                return Reading(Reading.Kind.NOUN, f, Reading.Step.NAME)
            }
            val p1 = prev(1)
            val p2 = prev(2)
            val n1 = next(1)
            val n2 = next(2)
            val hasNoun = !h.noun.isNullOrEmpty()
            // 2. A listed noun phrase ("the lost and found"), unless what is before it makes it a verb.
            nounPhrase()?.let { return it }
            // 2. Fixed phrases. A capitalised plant word that is also a name ("Holly leaves at six") is not the plant.
            if (hasNoun) {
                val plantName = p1 in context.nameOrPlant && titlecase(i - 1)
                if (p1 != null && p1 in h.nounAfter && !plantName) return noun(Reading.Step.FIXED)
                if (n1 != null && n1 in h.nounBefore) return noun(Reading.Step.FIXED)
                val pair = h.nounPair
                if (!pair.isNullOrEmpty() && ((p1 in andOr && p2 == pair) || (n1 in andOr && n2 == pair))) return noun(Reading.Step.FIXED)
            }
            // An expression of the form ("bound for", "bound to" + a base verb): a unit with its own meanings.
            expression(n1, n2)?.let { return Reading(Reading.Kind.EXPRESSION, "$f ${it.next}", Reading.Step.EXPRESSION) }
            // 3. A noun after a determiner (+ one listed adjective); -s forms also after a quantifier or preposition.
            if (hasNoun) {
                val dets = if (plural) context.determiners + context.pluralDeterminers + context.prepositions else context.determiners
                val adjectives = if (plural) context.adjectives + context.pluralAdjectives else context.adjectives

                // A possessive ("the soldier's") too, unless the form can follow "has" ("John's left").
                fun det(t: String?) = t != null && (t in dets || (t.endsWith("'s") && t !in context.haveAndBe && "participle" !in rules))
                if (det(p1) || (p1 in adjectives && det(p2))) return noun(Reading.Step.NOUN)
            }
            // 3b. A participle opening the sentence, then a listed word, then a comma later in the
            // sentence ("Left alone, she cried"; not "Felt hats, …", "Thought, like speech, …").
            if ("participle" in rules && startsSentence(i) && n1 != null && n2 != f &&
                expression(n1, n2) == null &&
                (n1 in context.participleOpenNext || (n1.endsWith("ly") && n1.length > 3))
            ) {
                val rest = text.substring(end).split(sentenceMark, limit = 2)[0]
                if (',' in rest) return verb(Reading.Step.PARTICIPLE_OPEN)
            }
            val k = head()
            val t = k?.let(::tok)
            // 4. A base form after to / a modal / do ("to saw", "will lead"), or opening a command ("Lead the way").
            val baseVerb = h.baseVerb
            if (!baseVerb.isNullOrEmpty()) {
                if (t in context.toAndModals && !(t == "to" && h.noTo)) return Reading(Reading.Kind.VERB, baseVerb, Reading.Step.BASE)
                val opens = startsSentence(i) || (p1 == "please" && startsSentence(i - 1))
                if ("imperative" in rules && opens && n1 in context.objectStarts) {
                    return Reading(Reading.Kind.VERB, baseVerb, Reading.Step.BASE)
                }
            }
            // 5. A participle after have/be ("had left", "was found").
            if ("participle" in rules && t in context.haveAndBe) return verb(Reading.Step.PARTICIPLE)
            // 6–8. After a subject: a pronoun, a name, a determiner + noun, or a conjunction.
            if (k != null && t != null) subjectReading(k, t)?.let { return it }
            return unsure()
        }

        private fun subjectReading(
            k: Int,
            t: String,
        ): Reading? {
            if (t in h.verbAfter) return Reading(Reading.Kind.VERB, h.verb, Reading.Step.SUBJECT)
            // "one" is a subject in "no one saw", not in "with one bound" or "One spoke had come loose".
            val oneOk = t != "one" || (k >= 1 && gapOk(k - 1, k) && tok(k - 1) in context.oneAfter)
            if ((t in context.subjects && oneOk) || (plural && t in context.singularSubjects)) return verb(Reading.Step.SUBJECT, t)
            val n1 = next(1)
            val sNext = n1 != null && n1 != "of" && (n1 in context.sFormNext || (n1.endsWith("ly") && n1.length > 3))
            if (titlecase(k) && t != "i" && t !in context.subjects) {
                val first = startsSentence(k)
                if (!first || (t !in context.notNames && t !in context.adjectives && !t.endsWith("ly") && !plural)) {
                    return verb(Reading.Step.SUBJECT)
                }
                // A name opening the sentence + an -s form + a preposition or adverb ("Tom lives in…").
                if (first && "sVerb" in rules && sNext && looksLikeName(k) && !finiteVerbLater()) return verb(Reading.Step.NAME_S)
            }
            if ("subjectNoun" in rules && !titlecase(k) && t !in closedClass && !t.endsWith("ly") && k >= 1 && gapOk(k - 1, k)) {
                val d =
                    when {
                        tok(k - 1) in context.subjectDeterminers -> k - 1
                        tok(k - 1) in context.adjectives && k >= 2 && gapOk(k - 2, k - 1) &&
                            tok(k - 2) in context.subjectDeterminers -> k - 2
                        else -> null
                    }
                val need = h.subjectNounNext
                if (d != null && opensClause(d) && (need == null || n1 in need)) return verb(Reading.Step.SUBJECT_NOUN)
            }
            // A determiner (+ adjective) + a singular noun + an -s form + a preposition or adverb, the
            // determiner opening the sentence or a clause ("The old man lives alone").
            if ("sVerb" in rules && sNext && !titlecase(k) && isNounWord(t) && !isPlural(t)) {
                val d = nounPhraseStart(k)
                if (d != null && opensClause(d) && !finiteVerbLater()) return verb(Reading.Step.NOUN_S)
            }
            // A quantifier or number (+ of (+ determiner)) (+ adjective) + a plural noun ("Many people left").
            if ("quantifier" in rules && !titlecase(k) && isNounWord(t) && isPlural(t)) {
                val q = quantifierStart(k)
                if (q != null && opensClause(q)) return verb(Reading.Step.QUANTIFIER, "they")
            }
            if ("conjunction" in rules && t in context.conjunctions) return verb(Reading.Step.CONJUNCTION)
            return null
        }

        /** A listed noun phrase around the form ("the lost and found") that nothing makes a verb. */
        private fun nounPhrase(): Reading? {
            for (phrase in h.nounPhrases) {
                val at = phrase.indexOf(f)
                if (at < 0) continue
                val first = i - at
                val last = first + phrase.size - 1
                if (first < 0 || last >= words.size || (first until last).any { !gapOk(it, it + 1) }) continue
                if ((first..last).map(::tok) != phrase) continue
                val before = if (first >= 1 && gapOk(first - 1, first)) tok(first - 1) else null
                val after = if (last + 1 < words.size && gapOk(last, last + 1)) tok(last + 1) else null
                val verbal =
                    before != null &&
                        (
                            before in context.subjects || before in context.haveAndBe || isSkip(first - 1) ||
                                (titlecase(first - 1) && !startsSentence(first - 1))
                        )
                if (!verbal && after !in context.objectStarts) return Reading(Reading.Kind.NOUN, h.noun ?: f, Reading.Step.PHRASE)
            }
            return null
        }
    }

    private val closedClass: Set<String> =
        with(context) {
            determiners + pluralDeterminers + adjectives + subjects + notNames + skip + prepositions + toAndModals + haveAndBe +
                conjunctions
        }

    /** [closedClass] and the plural adjectives: words that can't head a noun phrase. */
    private val nounClosed: Set<String> = closedClass + context.pluralAdjectives

    companion object {
        /** Largest table accepted, in bytes: today's is about 43 KB. */
        const val MAX_BYTES = 256 * 1024
        const val SCHEMA_VERSION = 1
        private const val RESOURCE = "/irregular-verbs.json"
        private const val MAX_FORM = 20

        /** Deepest nesting accepted: the table's is 5 (a homonym's meanings). */
        const val MAX_DEPTH = 8

        private val formPattern = Regex("^[a-z]{1,$MAX_FORM}$")

        private val groupPattern = Regex("^[a-z-]{1,$MAX_FORM}$")
        private const val MAX_TEXT = 100
        private const val MAX_ABOUT = 4000
        private val levels = setOf("A1", "A2", "B1", "B2", "C1", "C2")
        private val slots = setOf("base", "past", "participle", "ing", "s")
        private val ruleNames = setOf("plural", "participle", "subjectNoun", "conjunction", "imperative", "sVerb", "quantifier")
        private val sentenceStart = Regex("[.!?:;“\"‘(]")
        private val clauseMark = Regex("[,;—–]")
        private val clauseEnd = Regex("[,;:.!?—–()]")
        private val sentenceMark = Regex("[.!?]")
        private val quoteMark = Regex("[\"”]")

        /** Words a clause is scanned for a finite verb: far more than a real clause has. */
        private const val MAX_CLAUSE_WORDS = 60
        private val relativePronouns = setOf("who", "which", "whose", "whom")
        private val pluralNotEndings = listOf("ss", "us", "is", "'s")
        private val nameNotEndings = listOf("ly", "ful", "less", "ous")
        private val verbSuffixes = listOf("ed" to 2, "ed" to 1, "ing" to 3)
        private const val MAX_IPA = 40

        /** IPA letters and marks the table may use, and the combining syllabic and nasal marks. */
        private val ipaChars: Set<Int> =
            ("abdefghijklmnoprstuvwxz" + "æðŋɑɒɔəɚɛɜɝɡɪɹɾʃʊʌʒʔθʤʧˈˌːˑ." + "\u0329\u0303").codePoints().toArray().toSet()

        /** An English query or gloss: lower-case words, apostrophes and spaces, 2–40 characters. */
        private val englishPattern = Regex("^[a-z][a-z' ]{0,38}[a-z]$")

        /** A Spanish meaning of the v4 fields: shown text ([isText]) with no surrounding space. */
        private fun isSpanish(s: String) = isText(s) && s.trim() == s

        private val articles = setOf("the", "a", "an")
        private val andOr = setOf("and", "or")
        private val plainSubjects = setOf("i", "you", "we", "they")
        private val getForms = setOf("get", "gets", "got", "getting", "gotten")

        /** Capitalised, the rest lower case ("Rose", "O'Brien"). */
        private fun isTitlecase(s: String): Boolean {
            val rest = s.substring(1)
            return s[0].isUpperCase() && (rest.none { it.isUpperCase() } || '\'' in s || '’' in s)
        }

        // Strict: an unknown key, a missing field or a wrong type throws.
        private val json =
            Json {
                ignoreUnknownKeys = false
                isLenient = false
                explicitNulls = true
                coerceInputValues = false
                allowSpecialFloatingPointValues = false
            }

        private val empty = IrregularVerbs(Table.EMPTY)

        /** Why the bundled table was refused, or null. The app logs it in debug builds. */
        @Volatile
        var loadError: Throwable? = null
            private set

        /** The bundled table, read once; empty (and [loadError] set) when it is refused. */
        val shared: IrregularVerbs by lazy {
            val (table, error) = loadBundled { IrregularVerbs::class.java.getResourceAsStream(RESOURCE) }
            loadError = error
            table
        }

        /**
         * The table read from [open], or the empty table and why. Any failure counts, errors too
         * (a class a shrinker dropped, a failed initializer): a reader never crashes over it, and
         * the lazy [shared] never rethrows it on every word. Only the virtual machine's own
         * failures (out of memory, stack overflow) go on up.
         */
        internal fun loadBundled(open: () -> java.io.InputStream?): Pair<IrregularVerbs, Throwable?> =
            try {
                val stream = open() ?: throw IrregularVerbsException("$RESOURCE is missing")
                load(stream.use { readAtMost(it, MAX_BYTES + 1) }) to null
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                empty to e
            }

        /**
         * At most [max] bytes of [stream]: a bigger file is cut there (and then refused), never
         * read whole. (`InputStream.readNBytes` needs Android 13.)
         */
        internal fun readAtMost(
            stream: java.io.InputStream,
            max: Int,
        ): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (out.size() < max) {
                val read = stream.read(buffer, 0, minOf(buffer.size, max - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        /** Reads and checks [bytes]; throws on anything that isn't a valid table of schema version 1. */
        internal fun load(bytes: ByteArray): IrregularVerbs {
            if (bytes.size > MAX_BYTES) throw IrregularVerbsException("larger than $MAX_BYTES bytes")
            val text = bytes.toString(Charsets.UTF_8)
            if (!JsonGuard.isShallow(text, maxDepth = MAX_DEPTH)) throw IrregularVerbsException("nested too deeply")
            val element = json.parseToJsonElement(text)
            // First: a future schema is refused as such, whatever else it holds.
            val version = ((element as? JsonObject)?.get("schemaVersion") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (version != SCHEMA_VERSION) throw IrregularVerbsException("schema version $version")
            val table = json.decodeFromJsonElement(Table.serializer(), element)
            validate(table)
            return IrregularVerbs(table)
        }

        private fun check(
            condition: Boolean,
            message: () -> String,
        ) {
            if (!condition) throw IrregularVerbsException(message())
        }

        private fun validate(table: Table) {
            check(table.schemaVersion == SCHEMA_VERSION) { "schema version ${table.schemaVersion}" }
            check(table.about.codePointCount(0, table.about.length) <= MAX_ABOUT) { "about" }
            val groups = table.groups.map { it.id }
            check(groups.size == groups.toSet().size) { "duplicate group" }
            for (group in table.groups) {
                check(groupPattern.matches(group.id)) { "group ${group.id}" }
                check(listOf(group.example, group.title.en, group.title.es).all(::isText)) { "group ${group.id} text" }
            }
            val bases = table.verbs.map { it.base }
            check(bases.size == bases.toSet().size) { "duplicate base" }
            for (verb in table.verbs) {
                for (form in verb.forms()) check(formPattern.matches(form)) { "${verb.base}: bad form" }
                check(isFormList(verb.past)) { "${verb.base}: past" }
                check(isFormList(verb.participle)) { "${verb.base}: participle" }
                check(isMeanings(verb.es)) { "${verb.base}: es" }
                check(verb.present.orEmpty().size <= 2 && (verb.freq ?: 0) >= 0) { "${verb.base}: present or freq" }
                check(verb.group in groups) { "${verb.base}: group" }
                check(verb.level in levels) { "${verb.base}: level" }
                verb.spokenAs?.forEach { (slot, spoken) ->
                    check(slot in slots && formPattern.matches(spoken)) { "${verb.base}: spokenAs" }
                }
                verb.ipa?.forEach { (slot, ipa) -> check(slot in slots && isIpa(ipa)) { "${verb.base}: ipa" } }
                check(listOfNotNull(verb.query, verb.gloss).all(englishPattern::matches)) { "${verb.base}: query or gloss" }
                check(verb.sEs?.let(::isSpanish) ?: true) { "${verb.base}: sEs" }
                verb.homonym?.let { h ->
                    check(isFormList(h.past) && isFormList(h.participle)) { "${verb.base}: homonym forms" }
                    check(isMeanings(h.es)) { "${verb.base}: homonym es" }
                    check((h.homonymBefore + h.verbBefore).all(formPattern::matches)) { "${verb.base}: homonym words" }
                    check(listOfNotNull(h.query, h.gloss).all(englishPattern::matches)) { "${verb.base}: homonym query or gloss" }
                    // The twin's noun and its meanings come together.
                    check((h.noun == null) == (h.nounEs == null)) { "${verb.base}: homonym noun" }
                    h.noun?.let {
                            noun ->
                        check(
                            formPattern.matches(noun) && h.nounEs!!.size in 1..2 && h.nounEs.all(::isSpanish),
                        ) { "${verb.base}: homonym noun" }
                    }
                }
            }
            val forms = table.homographs.map { it.form }
            check(forms.size == forms.toSet().size) { "duplicate homograph" }
            val verbs = table.verbs.associateBy { it.base }
            for (h in table.homographs) {
                val words = listOfNotNull(h.form, h.noun, h.verb, h.baseVerb, h.nounPair) + h.nounAfter + h.nounBefore + h.verbAfter
                for (word in words) check(formPattern.matches(word)) { "homograph ${h.form}: bad word" }
                check(h.rules.all { it in ruleNames }) { "homograph ${h.form}: rule" }
                for (phrase in h.nounPhrases) {
                    check(
                        phrase.size in 2..4 && phrase.all(formPattern::matches) && h.form in phrase,
                    ) { "homograph ${h.form}: noun phrase" }
                }
                h.subjectNounNext?.let { next -> check(next.all(formPattern::matches)) { "homograph ${h.form}: subjectNounNext" } }
                // Pronunciation rules: words a–z, a spoken form like a verb's, a rule with no condition
                // only as the last, and rules on the next word before those on the previous word only.
                for ((n, rule) in h.nounIpa.withIndex()) {
                    val conditions = listOfNotNull(rule.near, rule.after, rule.before)
                    check(isIpa(rule.ipa) && conditions.flatten().all(formPattern::matches)) { "homograph ${h.form}: nounIpa" }
                    check(rule.spokenAs?.let(formPattern::matches) ?: true) { "homograph ${h.form}: nounIpa spokenAs" }
                    check(conditions.isNotEmpty() || n == h.nounIpa.lastIndex) { "homograph ${h.form}: nounIpa default not last" }
                }
                val afterOnly = h.nounIpa.indexOfFirst { it.after != null && it.before == null }
                check(afterOnly < 0 || h.nounIpa.drop(afterOnly).none { it.before != null }) { "homograph ${h.form}: nounIpa order" }
                for (x in h.expressions) {
                    val ok = formPattern.matches(x.next) && x.es.size in 1..2 && x.es.all(::isSpanish) && englishPattern.matches(x.query)
                    check(ok && englishPattern.matches(x.gloss)) { "homograph ${h.form}: expression" }
                }
                check("imperative" !in h.rules || !h.baseVerb.isNullOrEmpty()) { "homograph ${h.form}: imperative without its verb" }
                // Its verb is a known verb, and the form one of its forms.
                check(h.verb in verbs || h.verb in Verbs.regular) { "homograph ${h.form}: verb ${h.verb}" }
                verbs[h.verb]?.let { check(h.form == it.base || h.form in it.forms()) { "homograph ${h.form}: not a form of ${h.verb}" } }
            }
            for (list in table.context.lists()) for (word in list) check(isContextWord(word)) { "context: bad word" }
            val allForms = table.verbs.flatMapTo(mutableSetOf()) { it.forms() }
            check(table.llBases.size == table.llBases.toSet().size) { "duplicate llBase" }
            for (base in table.llBases) {
                val ok = llPattern.matches(base) && base.endsWith("ll") && vowelGroups(base.dropLast(1)) >= 2 && base !in allForms
                check(ok) { "llBase $base" }
            }
        }

        private val llPattern = Regex("^[a-z]{3,$MAX_FORM}$")

        private fun vowelGroups(s: String) = Regex("[aeiouy]+").findAll(s).count()

        /** IPA without slashes: 1–[MAX_IPA] characters of [ipaChars], in NFC. */
        private fun isIpa(s: String): Boolean {
            val count = s.codePointCount(0, s.length)
            if (count !in 1..MAX_IPA || !java.text.Normalizer.isNormalized(s, java.text.Normalizer.Form.NFC)) return false
            var i = 0
            while (i < s.length) {
                val codePoint = s.codePointAt(i)
                if (codePoint !in ipaChars) return false
                i += Character.charCount(codePoint)
            }
            return true
        }

        private fun isFormList(forms: List<String>) = forms.size in 1..2 && forms.toSet().size == forms.size

        private fun isMeanings(es: List<String>) = es.size in 1..2 && es.all(::isText)

        /**
         * Shown text (meanings, group names): not blank, at most [MAX_TEXT] characters, no control
         * or invisible formatting characters (bidi overrides, zero-width).
         */
        private fun isText(s: String): Boolean {
            if (s.isBlank() || s.codePointCount(0, s.length) > MAX_TEXT) return false
            var i = 0
            while (i < s.length) {
                val codePoint = s.codePointAt(i)
                val type = Character.getType(codePoint).toByte()
                if (type == Character.CONTROL || type == Character.FORMAT) return false
                i += Character.charCount(codePoint)
            }
            return true
        }

        /** A context word: a–z with apostrophes inside ("can't"), at most [MAX_FORM] characters. */
        private fun isContextWord(s: String) = s.length <= MAX_FORM && s.split('\'').all { formPattern.matches(it) }
    }
}

// The file's shape. No defaults on required fields, so a missing one is refused.

@Serializable
internal data class Table(
    val schemaVersion: Int,
    val about: String,
    val groups: List<Group>,
    val verbs: List<Verb>,
    val homographs: List<Homograph>,
    val context: Context,
    val llBases: List<String>,
) {
    companion object {
        val EMPTY = Table(IrregularVerbs.SCHEMA_VERSION, "", emptyList(), emptyList(), emptyList(), Context.EMPTY, emptyList())
    }
}

@Serializable
internal data class Group(val id: String, val example: String, val title: Title)

@Serializable
internal data class Title(val en: String, val es: String)

@Serializable
internal data class Verb(
    val base: String,
    val past: List<String>,
    val participle: List<String>,
    val ing: String,
    val s: String,
    val group: String,
    val level: String,
    val es: List<String>,
    val present: List<String>? = null,
    val spokenAs: Map<String, String>? = null,
    val ipa: Map<String, String>? = null,
    val homonym: Homonym? = null,
    /** An unambiguous English query and a short gloss for the verb's own sense ("to lie down", "recline"). */
    val query: String? = null,
    val gloss: String? = null,
    /** The Spanish meaning of the -s form ("lies": estar, encontrarse). */
    val sEs: String? = null,
    val nounFirst: Boolean = false,
    val freq: Int? = null,
) {
    /** Every form, the base and a regular homonym's included. */
    fun forms(): List<String> =
        listOf(base, ing, s) + past + participle + present.orEmpty() +
            homonym?.let { listOf(it.ing, it.s) + it.past + it.participle }.orEmpty()

    /** The slots [form] fills ("read": base, past, participle). */
    fun slots(form: String): List<String> =
        listOfNotNull(
            "base".takeIf { form == base },
            "past".takeIf { form in past },
            "participle".takeIf { form in participle },
            "ing".takeIf { form == ing },
            "s".takeIf { form == s },
        )
}

@Serializable
internal data class Homonym(
    val es: List<String>,
    val past: List<String>,
    val participle: List<String>,
    val ing: String,
    val s: String,
    /** Words after a shared -s or -ing form that make it the twin ("lying to"). */
    val homonymBefore: List<String> = emptyList(),
    /** Words after it that make it the verb ("lying on"). */
    val verbBefore: List<String> = emptyList(),
    /** An unambiguous English query and a short gloss for this sense ("to tell a lie", "say what isn't true"). */
    val query: String? = null,
    val gloss: String? = null,
    /** The twin's noun spelled like its -s form's base ("lies" → "lie": mentira), with its Spanish meanings. */
    val noun: String? = null,
    val nounEs: List<String>? = null,
)

/** How a homograph's noun is pronounced, when its sentence proves it (`nounIpa`). */
@Serializable
internal data class NounIpa(
    val ipa: String,
    val near: List<String>? = null,
    val after: List<String>? = null,
    val before: List<String>? = null,
    /** What the voice says for the noun when this rule applies and it would say it wrong (lead /lɛd/ "led"). */
    val spokenAs: String? = null,
)

/** An expression a homograph makes with the next word ("bound for"; "bound to" + a base verb). */
@Serializable
internal data class Expression(
    val next: String,
    val nextBase: Boolean = false,
    val es: List<String>,
    val query: String,
    val gloss: String,
)

/** A form of the table that is not a homograph (see `tableForms`). */
internal data class TableForm(
    val base: String,
    val kind: Kind,
    /** A past or participle only of the regular twin ("lied", "hanged"). */
    val homonym: Boolean,
    /** The twin that shares this -s or -ing form ("lying", "lies"), whose words tell the sense. */
    val shared: Homonym?,
) {
    enum class Kind { CERTAIN, S, ING }
}

@Serializable
internal data class Homograph(
    val form: String,
    val noun: String?,
    val verb: String,
    val rules: List<String>,
    val baseVerb: String? = null,
    val nounAfter: List<String> = emptyList(),
    val nounBefore: List<String> = emptyList(),
    val nounPair: String? = null,
    val verbAfter: List<String> = emptyList(),
    val noTo: Boolean = false,
    val nounPhrases: List<List<String>> = emptyList(),
    val subjectNounNext: List<String>? = null,
    val nounIpa: List<NounIpa> = emptyList(),
    val expressions: List<Expression> = emptyList(),
)

/** The word lists of the verb-or-noun rule. */
@Serializable
internal data class Context(
    val determiners: Set<String>,
    val pluralDeterminers: Set<String>,
    val prepositions: Set<String>,
    val adjectives: Set<String>,
    val pluralAdjectives: Set<String>,
    val subjects: Set<String>,
    val singularSubjects: Set<String>,
    val skip: Set<String>,
    val toAndModals: Set<String>,
    val haveAndBe: Set<String>,
    val subjectDeterminers: Set<String>,
    val oneAfter: Set<String>,
    val clauseOpeners: Set<String>,
    val lieAfter: Set<String>,
    val conjunctions: Set<String>,
    val notNames: Set<String>,
    val objectStarts: Set<String>,
    val layTime: Set<String>,
    val sFormNext: Set<String>,
    val notNameStarts: Set<String>,
    val nameOrPlant: Set<String>,
    val pluralNouns: Set<String>,
    val notPlural: Set<String>,
    val quantifiers: Set<String>,
    val quantifiersBeforeDeterminer: Set<String>,
    val quantifiersBeforeOf: Set<String>,
    val finiteVerbs: Set<String>,
    val participleOpenNext: Set<String>,
    val sSubjects: Set<String>,
    val beForms: Set<String>,
    val ingAfter: Set<String>,
) {
    fun lists() =
        listOf(
            determiners, pluralDeterminers, prepositions, adjectives, pluralAdjectives, subjects, singularSubjects, skip,
            toAndModals, haveAndBe, subjectDeterminers, oneAfter, clauseOpeners, lieAfter, conjunctions, notNames,
            objectStarts, layTime, sFormNext, notNameStarts, nameOrPlant, pluralNouns, notPlural, quantifiers,
            quantifiersBeforeDeterminer, quantifiersBeforeOf, finiteVerbs, participleOpenNext, sSubjects, beForms, ingAfter,
        )

    companion object {
        private val none = emptySet<String>()
        val EMPTY =
            Context(
                none, none, none, none, none, none, none, none, none, none, none, none, none, none, none, none, none, none,
                none, none, none, none, none, none, none, none, none, none, none, none, none,
            )
    }
}
