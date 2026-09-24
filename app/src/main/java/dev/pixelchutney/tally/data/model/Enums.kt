package dev.pixelchutney.tally.data.model

/**
 * Need vs Want is the only judgement Tally asks for at capture time, because it
 * is the only split that answers "what could I actually cut".
 */
enum class Necessity { NEED, WANT, UNSORTED }

enum class EntryMethod {
    /** Amount and merchant came from a parsed payment notification. */
    AUTO_PARSED,
    /** Prompted after leaving a payment app, typed by hand. */
    PROMPT_MANUAL,
    /** Opened the app or widget and typed it in. */
    FULLY_MANUAL,
    /** Restored from a backup or CSV. */
    IMPORTED,
}

enum class SessionOutcome {
    /** Still inside the payment app. */
    OPEN,
    /** Prompt answered with a saved transaction. */
    LOGGED,
    /** Prompt answered "no payment" — a deliberate no. */
    NO_PAYMENT,
    /** Prompt shown, swiped away. */
    DISMISSED,
    /** Prompt shown, never answered, expired. */
    IGNORED,
    /** Too short to be a payment; recorded but never prompted. */
    TOO_SHORT,
    /**
     * Left a payment app, no payment notification followed, and "sort later" is
     * on: waiting in "To sort" for an amount or a "no payment".
     */
    TO_SORT,
}

enum class Cadence { WEEKLY, MONTHLY, QUARTERLY, YEARLY }

enum class TxnFilterRange { TODAY, WEEK, MONTH, LAST_MONTH, ALL }
