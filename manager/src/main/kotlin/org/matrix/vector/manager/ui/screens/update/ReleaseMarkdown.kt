package org.matrix.vector.manager.ui.screens.update

import org.matrix.vector.manager.data.github.GitHubRepository

/**
 * GitHub-flavoured markdown, as the HTML the store's renderer already knows how to display.
 *
 * The module catalogue serves `readmeHTML` — HTML that GitHub has already rendered — so
 * `StoreHtmlPane` is an HTML renderer with a sandbox around it. The releases API is the one source
 * in the app that hands over *raw markdown*, in a release's `body`, and this is the adapter that
 * lets it reuse that renderer instead of adding a second rendering path.
 *
 * GitHub's own `POST /markdown` would be exact, and is rejected because it puts a network round
 * trip between the reader and a page they may be reading precisely because they are about to flash
 * something — and an unauthenticated POST is the most rate-limited thing we could reach for. This
 * runs offline on a body already in hand.
 *
 * Deliberately a *subset*, matching what release notes actually contain: headings, lists including
 * GitHub's task lists, links, images, emphasis, inline and fenced code, block quotes, rules, and
 * GFM tables. Tables matter because CI writes one into every canary's notes to compare the debug
 * and release zips.
 *
 * Anything unrecognised survives as its own text rather than vanishing, which is the property that
 * matters: a reader must never be shown a silently emptier release note than the one published.
 */
internal fun releaseMarkdownToHtml(markdown: String): String {
    val out = StringBuilder()
    val lines = markdown.replace("\r\n", "\n").split("\n")

    var index = 0
    var inList = false
    var listIsOrdered = false
    var paragraph = StringBuilder()

    fun closeParagraph() {
        if (paragraph.isNotEmpty()) {
            out.append("<p>").append(inline(paragraph.toString().trim())).append("</p>")
            paragraph = StringBuilder()
        }
    }

    fun closeList() {
        if (inList) {
            out.append(if (listIsOrdered) "</ol>" else "</ul>")
            inList = false
        }
    }

    while (index < lines.size) {
        val raw = lines[index]
        val line = raw.trimEnd()
        val trimmed = line.trim()

        // Fenced code first: everything inside is literal, including things that would otherwise
        // look like headings or list items.
        if (trimmed.startsWith("```")) {
            closeParagraph()
            closeList()
            val language = trimmed.removePrefix("```").trim()
            val body = StringBuilder()
            index++
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                body.append(escape(lines[index])).append('\n')
                index++
            }
            index++ // the closing fence
            out.append("<pre><code")
            if (language.isNotEmpty()) out.append(" class=\"language-").append(escape(language)).append('"')
            out.append('>').append(body).append("</code></pre>")
            continue
        }

        if (trimmed.isEmpty()) {
            closeParagraph()
            closeList()
            index++
            continue
        }

        val heading = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (heading != null) {
            closeParagraph()
            closeList()
            val level = heading.groupValues[1].length
            out.append("<h").append(level).append('>')
                .append(inline(heading.groupValues[2]))
                .append("</h").append(level).append('>')
            index++
            continue
        }

        if (Regex("^(---+|\\*\\*\\*+|___+)$").matches(trimmed)) {
            closeParagraph()
            closeList()
            out.append("<hr>")
            index++
            continue
        }

        if (trimmed.startsWith("> ")) {
            closeParagraph()
            closeList()
            out.append("<blockquote>").append(inline(trimmed.removePrefix("> "))).append("</blockquote>")
            index++
            continue
        }

        // A GFM table, recognised by its alignment row. The store's readme pane already styles
        // <table>, because GitHub hands it rendered HTML, so only the conversion is needed here.
        if (trimmed.startsWith("|") && index + 1 < lines.size && isAlignmentRow(lines[index + 1])) {
            closeParagraph()
            closeList()
            val headers = splitRow(trimmed)
            val aligns = splitRow(lines[index + 1].trim()).map(::alignmentOf)
            out.append("<table><thead><tr>")
            headers.forEachIndexed { i, cell ->
                out.append("<th").append(styleFor(aligns.getOrNull(i))).append('>')
                    .append(inline(cell)).append("</th>")
            }
            out.append("</tr></thead><tbody>")
            index += 2
            while (index < lines.size && lines[index].trim().startsWith("|")) {
                out.append("<tr>")
                splitRow(lines[index].trim()).forEachIndexed { i, cell ->
                    out.append("<td").append(styleFor(aligns.getOrNull(i))).append('>')
                        .append(inline(cell)).append("</td>")
                }
                out.append("</tr>")
                index++
            }
            out.append("</tbody></table>")
            continue
        }

        val bullet = Regex("^[-*+]\\s+(.*)$").find(trimmed)
        val numbered = Regex("^\\d+[.)]\\s+(.*)$").find(trimmed)
        if (bullet != null || numbered != null) {
            closeParagraph()
            val ordered = numbered != null
            if (inList && ordered != listIsOrdered) closeList()
            if (!inList) {
                out.append(if (ordered) "<ol>" else "<ul>")
                inList = true
                listIsOrdered = ordered
            }
            val content = (bullet ?: numbered)!!.groupValues[1]
            // GitHub's task lists. Rendered as real disabled checkboxes: a release note that says
            // "[x] shipped, [ ] not yet" is stating a fact the reader needs, and "[x]" as literal
            // text is exactly as unreadable as it looks.
            val task = Regex("^\\[([ xX])\\]\\s+(.*)$").find(content)
            if (task != null) {
                val checked = !task.groupValues[1].isBlank()
                out.append("<li class=\"task\"><input type=\"checkbox\" disabled")
                if (checked) out.append(" checked")
                out.append("> ").append(inline(task.groupValues[2])).append("</li>")
            } else {
                out.append("<li>").append(inline(content)).append("</li>")
            }
            index++
            continue
        }

        closeList()
        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(trimmed)
        index++
    }

    closeParagraph()
    closeList()
    return out.toString()
}

private fun isAlignmentRow(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.startsWith("|")) return false
    return splitRow(trimmed).isNotEmpty() &&
        splitRow(trimmed).all { Regex("^:?-{1,}:?$").matches(it.trim()) }
}

/** Cells of one row, without the outer pipes. Escaped pipes inside cells are not supported. */
private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

private fun alignmentOf(spec: String): String? {
    val cell = spec.trim()
    return when {
        cell.startsWith(":") && cell.endsWith(":") -> "center"
        cell.endsWith(":") -> "right"
        cell.startsWith(":") -> "left"
        else -> null
    }
}

private fun styleFor(alignment: String?): String =
    if (alignment == null) "" else " style=\"text-align:$alignment\""

/**
 * Inline spans.
 *
 * Order matters twice over. Code is lifted out first so its contents are never re-parsed — a
 * backticked `#795` is a literal, not an issue. Then, once links exist, they are lifted out too, so
 * the reference pass below cannot go rewriting the inside of an `href` it just produced. Both use a
 * placeholder rather than a lookbehind: a lookbehind would have to enumerate every context an
 * anchor can appear in, and the placeholder needs to know none of them.
 */
private fun inline(text: String): String {
    val vault = mutableListOf<String>()

    fun stash(html: String): String {
        vault += html
        return "\u0000${vault.size - 1}\u0000"
    }

    var working =
        Regex("`([^`]+)`").replace(text) { match -> stash("<code>${escape(match.groupValues[1])}</code>") }

    working = escape(working)

    working =
        Regex("!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)").replace(working) { match ->
            stash("<img src=\"${match.groupValues[2]}\" alt=\"${match.groupValues[1]}\">")
        }
    working =
        Regex("\\[([^\\]]+)]\\(([^)\\s]+)[^)]*\\)").replace(working) { match ->
            stash("<a href=\"${match.groupValues[2]}\">${match.groupValues[1]}</a>")
        }
    // Bare URLs, which release notes use for compare links.
    working =
        Regex("\\bhttps?://[^\\s<]+").replace(working) { stash("<a href=\"${it.value}\">${it.value}</a>") }

    working = linkReferences(working, ::stash)

    working = Regex("\\*\\*([^*]+)\\*\\*").replace(working) { "<strong>${it.groupValues[1]}</strong>" }
    working = Regex("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])").replace(working) { "<em>${it.groupValues[1]}</em>" }
    working = Regex("~~([^~]+)~~").replace(working) { "<del>${it.groupValues[1]}</del>" }

    // Restore innermost-last: a stashed anchor may itself contain a stashed code span.
    for (i in vault.indices.reversed()) working = working.replace("\u0000$i\u0000", vault[i])
    return working
}

/**
 * The three shorthands release notes are written in: `#795`, a bare commit SHA, and `@handle`.
 *
 * GitHub renders all three as links and authors write them expecting that, so left as plain text
 * they read as noise — a trailing `(#795)` is often the only pointer to why a build exists.
 *
 * The SHA rule wants between seven and forty hex digits, which is what keeps it off ordinary words:
 * `deadbeef` is a real risk in a changelog, and seven is GitHub's own abbreviation length, so this
 * matches what a reader already believes is a commit.
 */
private fun linkReferences(text: String, stash: (String) -> String): String {
    var working =
        Regex("(?<![\\w/])#(\\d+)\\b").replace(text) { match ->
            stash("<a href=\"${GitHubRepository.REPO_URL}/issues/${match.groupValues[1]}\">#${match.groupValues[1]}</a>")
        }
    working =
        Regex("(?<![\\w/])\\b([0-9a-f]{7,40})\\b(?![\\w/])").replace(working) { match ->
            val sha = match.groupValues[1]
            // All digits is a number someone wrote, not a commit.
            if (sha.none { it.isLetter() }) sha
            else stash("<a href=\"${GitHubRepository.REPO_URL}/commit/$sha\">${sha.take(8)}</a>")
        }
    working =
        Regex("(?<![\\w/@])@([A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?)\\b").replace(working) { match ->
            stash("<a href=\"https://github.com/${match.groupValues[1]}\">@${match.groupValues[1]}</a>")
        }
    return working
}

private fun escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
