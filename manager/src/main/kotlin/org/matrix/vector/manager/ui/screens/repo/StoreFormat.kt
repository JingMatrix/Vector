package org.matrix.vector.manager.ui.screens.repo

import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

/**
 * Repository timestamps are ISO-8601 in UTC. The reader's calendar is neither, so the instant is
 * parsed and re-formatted for [locale] rather than sliced out of the machine format, which reads as
 * a date only to someone who already writes dates that way.
 *
 * Returns null when the field is missing or unparseable, so a caller can drop the line rather than
 * print a placeholder that says nothing.
 */
internal fun String?.asRepositoryDate(locale: Locale): String? {
    val raw = this?.takeIf { it.isNotBlank() } ?: return null
    val instant = runCatching { Instant.parse(raw) }.getOrNull() ?: return null
    return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(instant.toEpochMilli()))
}
