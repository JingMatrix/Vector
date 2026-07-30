package org.matrix.vector.manager.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import org.matrix.vector.manager.ui.theme.currentLocale

/**
 * Compact counts for the project footer: 11905 becomes "11.9k".
 *
 * The locale is passed in rather than read from `Locale.getDefault()`, which is the *process*
 * default and stays the host app's: a reader on a French phone who has set the app to English would
 * otherwise be shown "11,9k".
 */
fun compactCount(value: Int, locale: Locale): String =
    when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> String.format(locale, "%.1fk", value / 1000f)
        else -> String.format(locale, "%.1fM", value / 1_000_000f)
    }

/**
 * The precise moment a commit landed, in the device's locale and 12/24-hour preference.
 *
 * The timeline already carries *approximate* time structurally — the rail's length is the elapsed
 * gap, and the month separators give the coarse position. So the text is free to be exact, which
 * is what someone comparing a commit against their own build actually needs. A relative label
 * would duplicate what the rail already says, less precisely.
 */
@Composable
fun exactTime(epochSeconds: Long): String {
    val context = LocalContext.current
    val locale = currentLocale()
    // Built once per language rather than once per row. Formatting in place would cost two
    // Calendars, a time format, an ICU pattern lookup and a SimpleDateFormat for *every commit on
    // screen, on every recomposition* — invisible on a feed of a hundred, not on one that holds
    // thousands and re-lays itself out whenever the author filter changes.
    val formats = remember(context, locale) { TimeFormats(context, locale) }
    return remember(formats, epochSeconds) { formats.format(epochSeconds) }
}

/**
 * The date and time formatters for one language, plus the two boundaries they are chosen by.
 *
 * "Today" and "this year" are captured when this is built, not read per row. The cost of that is a
 * session left open across midnight showing a bare time for yesterday's newest commit until
 * something rebuilds this; the benefit is that formatting a row is a lookup and a format call
 * rather than two Calendar instantiations.
 */
private class TimeFormats(context: android.content.Context, private val locale: Locale) {
    private val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    private val thisYear = pattern("MMMd")
    private val otherYear = pattern("yMMMd")

    private val startOfToday: Long
    private val startOfNextDay: Long
    private val startOfYear: Long
    private val startOfNextYear: Long

    init {
        val cal = java.util.Calendar.getInstance(locale)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        startOfToday = cal.timeInMillis
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        startOfNextDay = cal.timeInMillis
        cal.timeInMillis = startOfToday
        cal.set(java.util.Calendar.DAY_OF_YEAR, 1)
        startOfYear = cal.timeInMillis
        cal.add(java.util.Calendar.YEAR, 1)
        startOfNextYear = cal.timeInMillis
    }

    fun format(epochSeconds: Long): String {
        val millis = epochSeconds * 1000
        val date = java.util.Date(millis)
        val time = timeFormat.format(date)
        if (millis in startOfToday until startOfNextDay) return time
        val day =
            if (millis in startOfYear until startOfNextYear) thisYear.format(date)
            else otherYear.format(date)
        return "$day $time"
    }

    // Not DateUtils: its formatting runs through `Locale.getDefault()` regardless of the context
    // handed to it, so the month abbreviation would stay in the phone's language while everything
    // around it followed the app's. Asking for the best pattern for a locale and formatting with
    // it keeps the same shape — abbreviated month, year only when it is not this one — and honours
    // the choice.
    private fun pattern(skeleton: String) =
        java.text.SimpleDateFormat(
            android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton),
            locale,
        )
}
