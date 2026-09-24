package dev.pixelchutney.tally.data.db

import androidx.room.TypeConverter
import dev.pixelchutney.tally.data.model.Cadence
import dev.pixelchutney.tally.data.model.EntryMethod
import dev.pixelchutney.tally.data.model.Necessity
import dev.pixelchutney.tally.data.model.SessionOutcome

class Converters {
    @TypeConverter fun necessityToString(value: Necessity?): String? = value?.name
    @TypeConverter fun stringToNecessity(value: String?): Necessity? =
        value?.let { runCatching { Necessity.valueOf(it) }.getOrNull() }

    @TypeConverter fun entryMethodToString(value: EntryMethod?): String? = value?.name
    @TypeConverter fun stringToEntryMethod(value: String?): EntryMethod? =
        value?.let { runCatching { EntryMethod.valueOf(it) }.getOrNull() }

    @TypeConverter fun outcomeToString(value: SessionOutcome?): String? = value?.name
    @TypeConverter fun stringToOutcome(value: String?): SessionOutcome? =
        value?.let { runCatching { SessionOutcome.valueOf(it) }.getOrNull() }

    @TypeConverter fun cadenceToString(value: Cadence?): String? = value?.name
    @TypeConverter fun stringToCadence(value: String?): Cadence? =
        value?.let { runCatching { Cadence.valueOf(it) }.getOrNull() }
}
