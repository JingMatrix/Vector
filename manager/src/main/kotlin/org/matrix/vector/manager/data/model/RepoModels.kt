package org.matrix.vector.manager.data.model

import com.google.gson.annotations.SerializedName

/**
 * The module repository's JSON, as types.
 *
 * These mirror what the server actually sends, measured against a live `modules.json` of 809
 * entries. Two things shape the file:
 *
 * **Nullability is not decoration here.** `scope` is null on 506 of the 809 entries, `sourceUrl` on
 * 369 and `summary` on 121. Gson constructs through `Unsafe` and runs neither Kotlin's
 * default-argument logic nor its null checks, so a non-null type on a field the server omits yields
 * a `null` that only explodes at the first dereference, far from the parse. Every field the payload
 * does not guarantee is therefore declared optional.
 *
 * **The list payload is nearly a detail payload.** Each list entry already carries exactly one
 * release — the newest — with its `.apk` asset and download URL. Installing the current version of
 * a module needs no second request, which is what lets the Store work on a bad connection.
 */
data class OnlineModule(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String?,
    @SerializedName("summary") val summary: String?,
    @SerializedName("url") val url: String?,
    @SerializedName("homepageUrl") val homepageUrl: String?,
    @SerializedName("sourceUrl") val sourceUrl: String?,
    @SerializedName("hide") val hide: Boolean? = false,
    @SerializedName("readmeHTML") val readmeHTML: String?,
    @SerializedName("scope") val scope: List<String>? = null,
    @SerializedName("stargazerCount") val stargazerCount: Int? = null,
    @SerializedName("createdAt") val createdAt: String? = null,
    @SerializedName("updatedAt") val updatedAt: String? = null,
    @SerializedName("pushedAt") val pushedAt: String? = null,
    @SerializedName("latestRelease") val latestRelease: String? = null,
    @SerializedName("latestReleaseTime") val latestReleaseTime: String? = null,
    @SerializedName("latestBetaRelease") val latestBetaRelease: String? = null,
    @SerializedName("latestBetaReleaseTime") val latestBetaReleaseTime: String? = null,
    @SerializedName("collaborators") val collaborators: List<Collaborator>? = null,
    @SerializedName("additionalAuthors") val additionalAuthors: List<AdditionalAuthor>? = null,
    @SerializedName("releases") val releases: List<Release>? = null,
    @SerializedName("betaReleases") val betaReleases: List<Release>? = null,
) {
    /** The display name, falling back to the package name so a row is never blank. */
    val title: String
        get() = description?.takeIf { it.isNotBlank() } ?: name

    /** The module's own page, synthesised when the payload carries no explicit url. */
    val repoUrl: String
        get() = url ?: "https://github.com/Xposed-Modules-Repo/$name"
}

data class Collaborator(
    @SerializedName("login") val login: String?,
    @SerializedName("name") val name: String?,
)

/**
 * Someone credited beyond the repository's collaborators.
 *
 * An object, not a bare name, though the field reads like a list of names: the 49 entries that
 * carry one hold `{type, name, link}`. Typed as a list of strings it makes Gson throw `Expected a
 * string but was BEGIN_OBJECT`, which takes the whole catalogue parse — and with it the entire
 * Store — rather than the one module.
 */
data class AdditionalAuthor(
    @SerializedName("name") val name: String?,
    @SerializedName("link") val link: String?,
    @SerializedName("type") val type: String?,
)

data class Release(
    /** GitHub's node id — the only field on a release that is actually unique. See [key]. */
    @SerializedName("id") val id: String?,
    @SerializedName("databaseId") val databaseId: Long? = null,
    @SerializedName("name") val name: String?,
    @SerializedName("tagName") val tagName: String? = null,
    @SerializedName("url") val url: String?,
    @SerializedName("descriptionHTML") val descriptionHTML: String?,
    @SerializedName("createdAt") val createdAt: String? = null,
    @SerializedName("publishedAt") val publishedAt: String?,
    @SerializedName("isDraft") val isDraft: Boolean? = null,
    @SerializedName("isPrerelease") val isPrerelease: Boolean? = null,
    @SerializedName("isLatest") val isLatest: Boolean? = null,
    @SerializedName("isLatestBeta") val isLatestBeta: Boolean? = null,
    @SerializedName("releaseAssets") val releaseAssets: List<ReleaseAsset>? = null,
) {
    /**
     * A stable identity for a lazy list.
     *
     * Release *names* are not unique in real data: `com.rww.wetypeswipe` currently publishes two
     * releases both named `1.11.4`, under tags `43-` and `42-`. `LazyColumn` throws
     * `IllegalArgumentException` on a duplicate key, so identity comes from `id`, which is unique
     * by construction, with the tag as fallback and the index as last resort — a malformed payload
     * then degrades into an odd-looking list rather than a crash.
     */
    fun key(index: Int): String = id ?: tagName ?: "release:$index"

    /** The version this release publishes, read from its `<versionCode>-<versionName>` tag. */
    val version: RepoVersion?
        get() = RepoVersion.parse(tagName)

    /** The assets a package installer could actually accept. */
    val apks: List<ReleaseAsset>
        get() = releaseAssets.orEmpty().filter { it.isApk }
}

data class ReleaseAsset(
    @SerializedName("name") val name: String?,
    @SerializedName("contentType") val contentType: String? = null,
    @SerializedName("downloadUrl") val downloadUrl: String?,
    @SerializedName("downloadCount") val downloadCount: Int? = null,
    /** A byte count. `Int` runs out at 2 GB, and this is a file size, so it is a `Long`. */
    @SerializedName("size") val size: Long = 0,
) {
    /**
     * Whether this asset is an APK.
     *
     * Judged on the declared content type *and* the filename: 923 of the 946 assets in the
     * catalogue declare `application/vnd.android.package-archive`, but a few authors upload theirs
     * as `application/octet-stream`, and trusting the type alone would hide the only download those
     * modules have.
     */
    val isApk: Boolean
        get() =
            downloadUrl != null &&
                (contentType == "application/vnd.android.package-archive" ||
                    name?.endsWith(".apk", ignoreCase = true) == true)
}

/**
 * A module version as the repository states it: `"44-1.11.5"` is code 44, name `1.11.5`.
 *
 * The second clause of the comparison is load-bearing rather than defensive: a release whose *code*
 * equals what is installed but whose *name* differs is a rebuild of that version, and the user does
 * want it.
 */
data class RepoVersion(val versionCode: Long, val versionName: String) {

    fun upgradableOver(installedCode: Long, installedName: String): Boolean =
        versionCode > installedCode ||
            (versionCode == installedCode && installedName.replace(' ', '_') != versionName)

    companion object {
        fun parse(raw: String?): RepoVersion? {
            val text = raw?.takeIf { it.isNotBlank() } ?: return null
            val split = text.split('-', limit = 2)
            if (split.size < 2) return null
            val code = split[0].toLongOrNull() ?: return null
            return RepoVersion(code, split[1])
        }
    }
}

/**
 * One row of the Store: a catalogue entry, plus what this device has to say about it.
 *
 * The join lives in the ViewModel rather than in either repository, so the network layer stays
 * ignorant of the daemon and neither has to know the other exists.
 */
data class StoreEntry(
    val module: OnlineModule,
    val latest: RepoVersion?,
    val installed: RepoVersion?,
    /** The reader asked not to be told about this one again. */
    val updatesMuted: Boolean = false,
) {
    /**
     * There is a newer version *and* the reader wants to hear about it.
     *
     * Muting is folded in here rather than at each place that reads this, because every list and
     * count that mentions updates reads it — the Store's header count, its updates filter, its row
     * badge, and the set the Modules screen badges from — and a mute that only some of them
     * honoured would be worse than none at all.
     *
     * The two screens that show a module *by itself* deliberately sidestep the mute: the store's
     * detail page computes its own answer, and the module's own sheet asks this with `updatesMuted`
     * cleared and puts the switch right beside the result. Muting means "stop counting this and
     * stop mentioning it in lists", not "refuse to let me update it" — someone who has opened the
     * page for one module is not being nagged, they are asking.
     */
    val upgradable: Boolean
        get() =
            !updatesMuted &&
                installed != null &&
                latest != null &&
                latest.upgradableOver(installed.versionCode, installed.versionName)
}

/**
 * The catalogue as one value, so "these are saved results" is a property of the data.
 *
 * The shape `CommunityFeed` uses on Home, for the same reason: the manager routinely runs with no
 * network, and a screen that cannot tell a stale list from a fresh one has to choose between lying
 * and showing an error where a perfectly usable list was available.
 */
data class StoreCatalog(
    val modules: List<OnlineModule> = emptyList(),
    val loaded: Boolean = false,
    val fromCache: Boolean = false,
    val loadedAtMillis: Long = 0L,
) {
    val isEmpty: Boolean
        get() = modules.isEmpty()
}
