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
) {
    enum class Kind { NOUN, VERB, UNSURE }

    enum class Step { NAME, FIXED, NOUN, BASE, PARTICIPLE, SUBJECT, SUBJECT_NOUN, CONJUNCTION, UNSURE }
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

    private val verbsByBase: Map<String, Verb> = table.verbs.associateBy { it.base }
    private val homographs: Map<String, Homograph> = table.homographs.associateBy { it.form }
    private val context = table.context

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
        if (isHomograph(word)) return resolve(text, words, index)?.takeIf { it.kind != Reading.Kind.UNSURE && it.base != word }?.base
        return formToBase[word]?.takeUnless { isName(text, words, index) }
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
        if (isHomograph(form)) {
            val reading = resolve(text, words, index)?.takeIf { it.kind == Reading.Kind.VERB } ?: return null
            val verb = verbsByBase[reading.base] ?: return null
            val slot =
                when (reading.step) {
                    Reading.Step.BASE -> "base".takeIf { form == verb.base }
                    Reading.Step.PARTICIPLE -> "participle".takeIf { form in verb.participle }
                    else ->
                        when (form) {
                            in verb.past -> "past"
                            verb.s -> "s"
                            else -> null
                        }
                }
            return slot?.let { verb.spokenAs?.get(it) }
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
        // Most words are no homograph: no need to tokenize the text for them.
        if (TextTools.normalize(text.substring(range.start, range.end)) !in homographs) return null
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
        val homograph = homographs[form] ?: return null
        return Resolver(text, words, index, surface, form, homograph).resolve()
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

        fun noun(step: Reading.Step) = Reading(Reading.Kind.NOUN, h.noun!!, step)

        fun verb(
            step: Reading.Step,
            subject: String? = null,
        ): Reading {
            var base = h.verb
            if (f == "lay") {
                val n1 = next(1)
                val n2 = next(2)
                val obj = n1 in context.objectStarts && !(n1 in articles && n2 in context.layTime)
                if (subject in plainSubjects && !obj && n1 !in context.lieAfter) return unsure()
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
            // 2. Fixed phrases.
            if (hasNoun) {
                if (p1 != null && p1 in h.nounAfter) return noun(Reading.Step.FIXED)
                if (n1 != null && n1 in h.nounBefore) return noun(Reading.Step.FIXED)
                val pair = h.nounPair
                if (!pair.isNullOrEmpty() && ((p1 in andOr && p2 == pair) || (n1 in andOr && n2 == pair))) return noun(Reading.Step.FIXED)
            }
            if (f == "bound" && (n1 in context.boundAdjective || (n1 == "to" && (n2 in allBases || n2 in Verbs.regular)))) {
                return Reading(Reading.Kind.NOUN, "bound", Reading.Step.FIXED)
            }
            // 3. A noun after a determiner (+ one listed adjective); -s forms also after a quantifier or preposition.
            if (hasNoun) {
                val dets = if (plural) context.determiners + context.pluralDeterminers + context.prepositions else context.determiners
                val adjectives = if (plural) context.adjectives + context.pluralAdjectives else context.adjectives

                // A possessive ("the soldier's") too, unless the form can follow "has" ("John's left").
                fun det(t: String?) = t != null && (t in dets || (t.endsWith("'s") && t !in context.haveAndBe && "participle" !in rules))
                if (det(p1) || (p1 in adjectives && det(p2))) return noun(Reading.Step.NOUN)
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
            if (titlecase(k) && t != "i" && t !in context.subjects) {
                val first = startsSentence(k)
                if (!first || (t !in context.notNames && t !in context.adjectives && !t.endsWith("ly") && !plural)) {
                    return verb(Reading.Step.SUBJECT)
                }
            }
            if ("subjectNoun" in rules && !titlecase(k) && t !in closedClass && !t.endsWith("ly") && k >= 1 && gapOk(k - 1, k)) {
                val d =
                    when {
                        tok(k - 1) in context.subjectDeterminers -> k - 1
                        tok(k - 1) in context.adjectives && k >= 2 && gapOk(k - 2, k - 1) &&
                            tok(k - 2) in context.subjectDeterminers -> k - 2
                        else -> null
                    }
                if (d != null && opensClause(d)) return verb(Reading.Step.SUBJECT_NOUN)
            }
            if ("conjunction" in rules && t in context.conjunctions) return verb(Reading.Step.CONJUNCTION)
            return null
        }

        /** The determiner at [d] starts the sentence or a clause. */
        private fun opensClause(d: Int): Boolean {
            if (startsSentence(d) || clauseMark.containsMatchIn(text.substring(words[d - 1].end, words[d].start))) return true
            return gapOk(d - 1, d) && tok(d - 1) in context.clauseOpeners
        }
    }

    private val closedClass: Set<String> =
        with(context) {
            determiners + pluralDeterminers + adjectives + subjects + notNames + skip + prepositions + toAndModals + haveAndBe +
                conjunctions
        }

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
        private val ruleNames = setOf("plural", "participle", "subjectNoun", "conjunction", "imperative")
        private val sentenceStart = Regex("[.!?:;“\"‘(]")
        private val clauseMark = Regex("[,;—–]")
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
                verb.homonym?.let { h ->
                    check(isFormList(h.past) && isFormList(h.participle)) { "${verb.base}: homonym forms" }
                    check(isMeanings(h.es)) { "${verb.base}: homonym es" }
                }
            }
            val forms = table.homographs.map { it.form }
            check(forms.size == forms.toSet().size) { "duplicate homograph" }
            val verbs = table.verbs.associateBy { it.base }
            for (h in table.homographs) {
                val words = listOfNotNull(h.form, h.noun, h.verb, h.baseVerb, h.nounPair) + h.nounAfter + h.nounBefore + h.verbAfter
                for (word in words) check(formPattern.matches(word)) { "homograph ${h.form}: bad word" }
                check(h.rules.all { it in ruleNames }) { "homograph ${h.form}: rule" }
                check("imperative" !in h.rules || !h.baseVerb.isNullOrEmpty()) { "homograph ${h.form}: imperative without its verb" }
                // Its verb is a known verb, and the form one of its forms.
                check(h.verb in verbs || h.verb in Verbs.regular) { "homograph ${h.form}: verb ${h.verb}" }
                verbs[h.verb]?.let { check(h.form == it.base || h.form in it.forms()) { "homograph ${h.form}: not a form of ${h.verb}" } }
            }
            for (list in table.context.lists()) for (word in list) check(isContextWord(word)) { "context: bad word" }
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
) {
    companion object {
        val EMPTY = Table(IrregularVerbs.SCHEMA_VERSION, "", emptyList(), emptyList(), emptyList(), Context.EMPTY)
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
    val homonym: Homonym? = null,
    val nounFirst: Boolean = false,
    val freq: Int? = null,
) {
    /** Every form, the base and a regular homonym's included. */
    fun forms(): List<String> =
        listOf(base, ing, s) + past + participle + present.orEmpty() +
            homonym?.let { listOf(it.ing, it.s) + it.past + it.participle }.orEmpty()
}

@Serializable
internal data class Homonym(
    val es: List<String>,
    val past: List<String>,
    val participle: List<String>,
    val ing: String,
    val s: String,
)

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
    val boundAdjective: Set<String>,
) {
    fun lists() =
        listOf(
            determiners, pluralDeterminers, prepositions, adjectives, pluralAdjectives, subjects, singularSubjects, skip,
            toAndModals, haveAndBe, subjectDeterminers, oneAfter, clauseOpeners, lieAfter, conjunctions, notNames,
            objectStarts, layTime, boundAdjective,
        )

    companion object {
        private val none = emptySet<String>()
        val EMPTY =
            Context(none, none, none, none, none, none, none, none, none, none, none, none, none, none, none, none, none, none, none)
    }
}
