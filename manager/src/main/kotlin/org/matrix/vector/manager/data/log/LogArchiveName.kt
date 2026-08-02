package org.matrix.vector.manager.data.log

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.matrix.vector.manager.BuildConfig

/**
 * What a saved bug report is called, wherever it is saved from.
 *
 * Three places produce one — the log panel, the troubleshooting page, and the root export that
 * tars the daemon's folder — and they used to name it three ways. The name is the first thing
 * anyone attaching one to an issue sees, and it has to say which build it came from: a report from
 * a debug build explains behaviour that a release build does not have, and asking after the fact
 * is a round trip that the file name can save.
 *
 * Not a string resource. It was one, translated into nineteen locales, but a file name is not
 * language — a report named in Persian and one named in German are the same file, and the
 * translations only made it possible for them to disagree.
 *
 * [extension] rather than a fixed `zip`: the manager builds a zip through SAF, while the root
 * export shells out to `tar`, which is what Android actually ships. Only the extension differs.
 */
fun logArchiveName(extension: String): String =
    "Vector-logs-${BuildConfig.BUILD_TYPE}-${LocalDateTime.now().format(ARCHIVE_STAMP)}.$extension"

/**
 * Which build wrote an archive, for the archive itself to carry.
 *
 * The name says the build type and no more, because a name has to stay short enough to read. What
 * identifies a *binary* is the commit: the version code is the commit count on master, so every
 * branch build at the same depth wears the number of an official build it was never made from.
 *
 * Where this goes depends on what the format offers. A zip has a comment field and gets this
 * verbatim; a backup is our own document and carries it as a field. `tar` has no such slot at all,
 * so the root export can only say what its name says.
 */
fun archiveBuildStamp(): String =
    "Vector ${BuildConfig.BUILD_TYPE} ${BuildConfig.VERSION_NAME} " +
        "(${BuildConfig.VERSION_CODE}) ${BuildConfig.VERSION_HASH}"

/** Sortable, no separators a file manager or a shell would have to be told about. */
private val ARCHIVE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
