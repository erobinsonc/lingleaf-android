# Lingleaf — product spec

Shared by [lingleaf-ios](https://github.com/erobinsonc/lingleaf-ios) and
[lingleaf-android](https://github.com/erobinsonc/lingleaf-android). Both
apps implement every scenario below; each scenario is covered by tests with
the same intent on both platforms. A change to behaviour updates this file in
**both** repositories.

## Principles

- Learn English by reading real books. The reader is the product.
- No accounts, ads or tracking. Data stays on the device.
- Network only for: the Gutenberg catalog and downloads (`gutendex.com`,
  `gutenberg.org`) and word definitions (`api.dictionaryapi.dev`). HTTPS only.
- Translation runs on the device (Apple Translation on iOS, Google ML Kit on Android).
- Interface in English and Spanish; more languages welcome.

## Scenarios

| ID | Scenario | Expected |
|---|---|---|
| S1 | First launch | Library shows 3 bundled stories (A1, A2, B1) and the daily goal card. |
| S2 | Open a book | Reader shows the text; returning later reopens at the same paragraph. |
| S3 | Tap a word | A card shows the base form ("animals" → "animal"), IPA, translation, definition, the sentence and its translation. |
| S4 | Learn | "Learn" saves the word; it is underlined in yellow in every book, including inflected forms. |
| S5 | Learned | "Learned" saves the word as known; it is not underlined or reviewed. |
| S6 | Listen | Word, sentence and paragraph can be read aloud with an English voice. |
| S7 | Review | Due words appear as flashcards; "I know it" schedules them 1, 3, 7, 14, 30 days out, then marks them learned; "Again" repeats them. |
| S8 | Daily goal | Reading time counts while the reader is open; the card shows minutes today and the streak (days meeting the goal). |
| S9 | Discover | Recommended classics with a level, search by title/author, one-tap download into the library. |
| S10 | Import | A plain-text `.txt` file can be imported; Gutenberg license headers are removed. |
| S11 | Settings | Daily goal, text size, speech speed and translation language persist. |
| S12 | Offline | Without internet the reader, saved words and review work; the card says translation/definition are unavailable. |

## Shared logic (same rules, same tests on both platforms)

- Paragraphs: split on blank lines, hard-wrapped lines joined.
- Words: `[A-Za-z]+(['’][A-Za-z]+)*`; contractions stay whole.
- Base forms: irregular table, then plural/past/-ing rules (see `TextTools`).
- Spaced repetition: boxes with 1, 3, 7, 14, 30 day intervals.
- Streak: consecutive days meeting the goal; an unfinished today doesn't break it.
- Imported .txt: title is the file name without its extension (empty → "Book"); Gutenberg header/footer removed; files over 10 MB or with no text are refused.
