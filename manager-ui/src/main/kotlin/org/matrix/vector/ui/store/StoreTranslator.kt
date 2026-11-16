package org.matrix.vector.ui.store

import android.util.Log
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native in-app translator for the Module Store.
 * Translates module titles, summaries, and README descriptions into the user's preferred language.
 *
 * Features persistent disk caching so translations survive app restarts without re-translating.
 */
class StoreTranslator(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val log: StoreLog? = null,
    private val cacheDir: File? = null,
) {
    private val diskCacheFile: File? = cacheDir?.let { File(it, "store_translations_cache.json") }

    // Multi-language memory cache: langCode -> (originalText -> translatedText)
    private val langCaches = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val saveMutex = Mutex()

    private val translateClient =
        client
            .newBuilder()
            .callTimeout(java.time.Duration.ofSeconds(10))
            .connectTimeout(java.time.Duration.ofSeconds(8))
            .readTimeout(java.time.Duration.ofSeconds(8))
            .build()

    @Volatile private var bingToken: String? = null
    @Volatile private var bingKey: String? = null
    @Volatile private var bingIg: String? = null
    @Volatile private var bingTokenExpiry = 0L

    init {
        // 1. Pre-populate Portuguese cache with the built-in offline dictionary
        getCacheForLang("pt").putAll(StoreBuiltinTranslations.translations)

        // 2. Load disk cache containing all previous dynamic translations
        loadDiskCache()
    }

    /** Returns or creates the thread-safe memory cache for [lang]. */
    fun getCacheForLang(lang: String): ConcurrentHashMap<String, String> {
        val normalized = resolveTargetLanguage(lang)
        return langCaches.computeIfAbsent(normalized) { ConcurrentHashMap() }
    }

    /** Loads previously cached translations from the disk cache file. */
    private fun loadDiskCache() {
        val file = diskCacheFile ?: return
        if (!file.exists()) return
        try {
            val content = file.readText()
            if (content.isNotBlank()) {
                val json = JSONObject(content)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val lang = keys.next()
                    val langObj = json.optJSONObject(lang) ?: continue
                    val cache = getCacheForLang(lang)
                    val itemKeys = langObj.keys()
                    while (itemKeys.hasNext()) {
                        val orig = itemKeys.next()
                        val trans = langObj.optString(orig)
                        if (!trans.isNullOrBlank()) {
                            cache[orig] = trans
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log?.log(Log.WARN, "StoreTranslator: Failed to load disk cache: ${e.message}", e)
        }
    }

    /** Persists dynamic translations across all languages to disk. */
    fun persistDiskCache() {
        val file = diskCacheFile ?: return
        scope.launch(Dispatchers.IO) {
            if (!saveMutex.tryLock()) return@launch
            try {
                val root = JSONObject()
                for ((lang, cache) in langCaches) {
                    val langObj = JSONObject()
                    for ((k, v) in cache) {
                        langObj.put(k, v)
                    }
                    root.put(lang, langObj)
                }
                val parent = file.parentFile ?: return@launch
                val tmp = File(parent, "${file.name}.tmp")
                tmp.writeText(root.toString())
                tmp.renameTo(file)
            } catch (e: Exception) {
                log?.log(Log.WARN, "StoreTranslator: Failed to save disk cache: ${e.message}", e)
            } finally {
                saveMutex.unlock()
            }
        }
    }

    /**
     * Determines whether [text] contains foreign characters (CJK, Cyrillic, Korean, Arabic)
     * that require translation to [targetLang].
     */
    fun needsTranslation(text: CharSequence?, targetLang: String? = null): Boolean {
        if (text.isNullOrBlank()) return false
        val target = targetLang?.let { resolveTargetLanguage(it) }

        var hasCjk = false
        var hasCyrillic = false
        var hasArabic = false

        for (i in 0 until text.length) {
            val c = text[i]
            if (
                c in '\u4e00'..'\u9fff' ||
                    c in '\u3400'..'\u4dbf' ||
                    c in '\uf900'..'\ufaff' ||
                    c in '\u3040'..'\u30ff' ||
                    c in '\uac00'..'\ud7af' ||
                    c in '\u1100'..'\u11ff'
            ) {
                hasCjk = true
            } else if (c in '\u0400'..'\u04ff' || c in '\u0500'..'\u052f') {
                hasCyrillic = true
            } else if (c in '\u0600'..'\u06ff') {
                hasArabic = true
            }
        }

        if (!hasCjk && !hasCyrillic && !hasArabic) return false

        // If target language is Chinese and text is already Chinese, no need to translate
        if (target == "zh" && hasCjk && !hasCyrillic && !hasArabic) return false
        // If target language is Russian and text is already Russian, no need to translate
        if (target == "ru" && hasCyrillic && !hasCjk && !hasArabic) return false
        // If target language is Arabic and text is already Arabic, no need to translate
        if (target == "ar" && hasArabic && !hasCjk && !hasCyrillic) return false

        return true
    }

    /** Backward-compatible alias for existing callers. */
    fun hasCjk(text: CharSequence?): Boolean = needsTranslation(text)

    /** Resolves the target language code (e.g. "pt", "en", "es", "zh", "ru", "de", "fr"). */
    fun resolveTargetLanguage(targetTag: String? = null): String {
        val tag = targetTag?.takeIf { it.isNotBlank() } ?: Locale.getDefault().language
        return when {
            tag.startsWith("pt", ignoreCase = true) -> "pt"
            tag.startsWith("es", ignoreCase = true) -> "es"
            tag.startsWith("zh", ignoreCase = true) -> "zh"
            tag.startsWith("ru", ignoreCase = true) -> "ru"
            tag.startsWith("de", ignoreCase = true) -> "de"
            tag.startsWith("fr", ignoreCase = true) -> "fr"
            tag.startsWith("ja", ignoreCase = true) -> "ja"
            tag.startsWith("ko", ignoreCase = true) -> "ko"
            tag.startsWith("it", ignoreCase = true) -> "it"
            tag.startsWith("tr", ignoreCase = true) -> "tr"
            tag.startsWith("vi", ignoreCase = true) -> "vi"
            tag.startsWith("id", ignoreCase = true) -> "id"
            tag.startsWith("pl", ignoreCase = true) -> "pl"
            tag.startsWith("uk", ignoreCase = true) -> "uk"
            tag.startsWith("ar", ignoreCase = true) -> "ar"
            tag.startsWith("hi", ignoreCase = true) -> "hi"
            tag.startsWith("en", ignoreCase = true) -> "en"
            else -> {
                val lang = tag.substringBefore("-").substringBefore("_").lowercase(Locale.ROOT)
                if (lang.length in 2..3) lang else "en"
            }
        }
    }

    /**
     * Finds a translation in memory cache, including normalized lookups and compound title
     * splitting.
     */
    fun findCachedTranslation(text: String?, targetLang: String = "pt"): String? {
        if (text.isNullOrBlank()) return null
        val cache = getCacheForLang(targetLang)
        cache[text]?.let {
            return it
        }

        val trimmed = text.trim()
        cache[trimmed]?.let {
            return it
        }

        // If composite title like "Name · Subtitle", translate foreign parts
        if (trimmed.contains(" · ")) {
            val parts = trimmed.split(" · ")
            val transParts =
                parts.map { part ->
                    val clean = part.trim()
                    if (needsTranslation(clean, targetLang)) cache[clean] ?: cache[part] ?: part
                    else part
                }
            val joined = transParts.joinToString(" · ")
            if (joined != trimmed) {
                cache[trimmed] = joined
                return joined
            }
        }

        return null
    }

    /** Translates a single text string from source to [targetLang]. */
    suspend fun translateText(text: String?, targetLang: String): String? {
        if (text.isNullOrBlank()) return text
        val resolvedLang = resolveTargetLanguage(targetLang)
        if (!needsTranslation(text, resolvedLang)) return text

        val cached = findCachedTranslation(text, resolvedLang)
        if (cached != null) return cached

        val translated =
            withContext(Dispatchers.IO) { fetchTranslation(text, resolvedLang) }

        if (!translated.isNullOrBlank()) {
            getCacheForLang(resolvedLang)[text] = translated
            persistDiskCache()
            return translated
        }

        return text
    }

    /**
     * Translates multiple text items in concurrent batches, reusing cache and delimiter packing.
     */
    suspend fun translateTextsBatch(
        texts: List<String>,
        targetLang: String,
    ): Map<String, String> {
        if (texts.isEmpty()) return emptyMap()
        val resolvedLang = resolveTargetLanguage(targetLang)
        val cache = getCacheForLang(resolvedLang)
        val result = ConcurrentHashMap<String, String>(texts.size)
        val missing = ArrayList<String>()

        for (t in texts) {
            val cached = findCachedTranslation(t, resolvedLang)
            if (cached != null) {
                result[t] = cached
            } else if (needsTranslation(t, resolvedLang)) {
                missing.add(t)
            }
        }

        if (missing.isEmpty()) return result

        val batches = ArrayList<List<String>>()
        var currentBatch = ArrayList<String>()
        var currentLen = 0

        for (item in missing.distinct()) {
            if (currentLen + item.length > 900 || currentBatch.size >= 12) {
                batches.add(currentBatch)
                currentBatch = ArrayList()
                currentLen = 0
            }
            currentBatch.add(item)
            currentLen += item.length
        }
        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch)
        }

        withContext(Dispatchers.IO) {
            coroutineScope {
                batches
                    .map { batch ->
                        async {
                            val delimiter = "\n===\n"
                            val combined = batch.joinToString(delimiter)
                            val translated = fetchTranslation(combined, resolvedLang)
                            if (translated != null) {
                                val parts = translated.split(delimiter)
                                if (parts.size == batch.size) {
                                    for (i in batch.indices) {
                                        val trans = parts[i].trim()
                                        result[batch[i]] = trans
                                        cache[batch[i]] = trans
                                    }
                                    persistDiskCache()
                                    return@async
                                }
                            }
                            // Fallback for this batch: translate individually
                            for (item in batch) {
                                val single = fetchTranslation(item, resolvedLang) ?: item
                                result[item] = single
                                cache[item] = single
                            }
                            persistDiskCache()
                        }
                    }
                    .awaitAll()
            }
        }

        return result
    }

    /**
     * Translates the entire catalog using the in-memory cache and built-in dictionary.
     * Operates purely in-memory with instant O(1) lookups per module.
     */
    fun translateCatalog(catalog: StoreCatalog, targetLang: String): StoreCatalog {
        if (catalog.isEmpty) return catalog
        val resolvedLang = resolveTargetLanguage(targetLang)

        val translatedModules =
            catalog.modules.map { module ->
                val transDesc =
                    if (needsTranslation(module.description, resolvedLang)) {
                        findCachedTranslation(module.description, resolvedLang)
                            ?: module.description
                    } else {
                        module.description
                    }

                val transSummary =
                    if (needsTranslation(module.summary, resolvedLang)) {
                        findCachedTranslation(module.summary, resolvedLang) ?: module.summary
                    } else {
                        module.summary
                    }

                if (transDesc != module.description || transSummary != module.summary) {
                    module.copy(description = transDesc, summary = transSummary)
                } else {
                    module
                }
            }

        return catalog.copy(modules = translatedModules)
    }

    /**
     * Translates a module detail record concurrently, including title, summary, README HTML, and
     * release notes.
     */
    suspend fun translateDetails(module: OnlineModule, targetLang: String): OnlineModule =
        withContext(Dispatchers.IO) {
            val resolvedLang = resolveTargetLanguage(targetLang)

            val transDescDeferred =
                async { translateText(module.description, resolvedLang) ?: module.description }
            val transSummaryDeferred =
                async { translateText(module.summary, resolvedLang) ?: module.summary }
            val transReadmeDeferred =
                async { translateHtml(module.readmeHTML, resolvedLang) ?: module.readmeHTML }

            val transReleasesDeferred =
                async {
                    module.releases?.map { release ->
                        val transNotes =
                            translateHtml(release.descriptionHTML, resolvedLang)
                                ?: release.descriptionHTML
                        if (transNotes != release.descriptionHTML) {
                            release.copy(descriptionHTML = transNotes)
                        } else {
                            release
                        }
                    }
                }

            module.copy(
                description = transDescDeferred.await(),
                summary = transSummaryDeferred.await(),
                readmeHTML = transReadmeDeferred.await(),
                releases = transReleasesDeferred.await(),
            )
        }

    /**
     * Safely translates HTML content by preserving markup and translating non-code text blocks
     * concurrently.
     */
    suspend fun translateHtml(html: String?, targetLang: String): String? {
        if (html.isNullOrBlank() || !needsTranslation(html, targetLang)) return html
        val resolvedLang = resolveTargetLanguage(targetLang)

        val cache = getCacheForLang(resolvedLang)
        val cached = cache[html]
        if (cached != null) return cached

        // Fast tag regex to split HTML into tags and text chunks
        val tagRegex = Regex("""(<[^>]+>)""")
        val parts = tagRegex.split(html)
        val tags = tagRegex.findAll(html).map { it.value }.toList()

        // 1. Gather all text chunks that need translation
        val textsToTranslate = ArrayList<String>()
        var inScriptOrStyle = false
        var inCode = false

        for (i in parts.indices) {
            val part = parts[i]
            if (
                part.isNotBlank() &&
                    needsTranslation(part, resolvedLang) &&
                    !inScriptOrStyle &&
                    !inCode
            ) {
                textsToTranslate.add(part.trim())
            }

            if (i < tags.size) {
                val lower = tags[i].lowercase(Locale.ROOT)
                if (
                    lower.startsWith("<script") ||
                        lower.startsWith("<style") ||
                        lower.startsWith("<svg")
                ) {
                    inScriptOrStyle = true
                } else if (
                    lower.startsWith("</script") ||
                        lower.startsWith("</style") ||
                        lower.startsWith("</svg")
                ) {
                    inScriptOrStyle = false
                } else if (lower.startsWith("<pre") || lower.startsWith("<code")) {
                    inCode = true
                } else if (lower.startsWith("</pre") || lower.startsWith("</code")) {
                    inCode = false
                }
            }
        }

        // 2. Translate all unique text chunks in concurrent batches
        val translations = translateTextsBatch(textsToTranslate, resolvedLang)

        // 3. Assemble translated HTML document
        val result = StringBuilder(html.length + 500)
        for (i in parts.indices) {
            val part = parts[i]
            val trimmed = part.trim()
            val trans = translations[trimmed]
            if (trans != null) {
                val leadingWs = part.takeWhile { it.isWhitespace() }
                val trailingWs = part.takeLastWhile { it.isWhitespace() }
                result.append(leadingWs).append(trans).append(trailingWs)
            } else {
                result.append(part)
            }

            if (i < tags.size) {
                result.append(tags[i])
            }
        }

        val out = result.toString()
        cache[html] = out
        persistDiskCache()
        return out
    }

    private fun fetchTranslation(text: String, targetLang: String): String? {
        // Attempt 1: Bing Translator (supports auto-detect, HTML preserving, multi-lingual)
        fetchBingTranslation(text, targetLang)?.let {
            return it
        }

        // Attempt 2: DuckDuckGo Translator fallback
        fetchDuckDuckGoTranslation(text, targetLang)?.let {
            return it
        }

        // Attempt 3: Google Translate RPC fallback
        fetchGoogleTranslation(text, targetLang)?.let {
            return it
        }

        return null
    }

    private fun refreshBingToken(): Boolean {
        try {
            val req =
                Request.Builder()
                    .url("https://www.bing.com/translator")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
            translateClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return false
                val html = response.body.string()
                val igMatcher = Regex("IG:\"([A-Za-z0-9]+)\"").find(html)
                val tokenMatcher =
                    Regex("params_AbusePreventionHelper\\s*=\\s*\\[([0-9]+),\"([^\"]+)\",([0-9]+)\\]")
                        .find(html)
                if (igMatcher != null && tokenMatcher != null) {
                    bingIg = igMatcher.groupValues[1]
                    bingKey = tokenMatcher.groupValues[1]
                    bingToken = tokenMatcher.groupValues[2]
                    bingTokenExpiry = System.currentTimeMillis() + 600_000 // 10 minutes
                    return true
                }
            }
        } catch (e: Exception) {
            log?.log(Log.DEBUG, "Bing token refresh error: ${e.message}", null)
        }
        return false
    }

    private fun fetchBingTranslation(text: String, targetLang: String): String? {
        if (System.currentTimeMillis() >= bingTokenExpiry || bingToken == null) {
            if (!refreshBingToken()) return null
        }
        val currentToken = bingToken ?: return null
        val currentKey = bingKey ?: return null
        val currentIg = bingIg ?: return null

        val to =
            when {
                targetLang.startsWith("zh-TW", ignoreCase = true) ||
                    targetLang.startsWith("zh-HK", ignoreCase = true) ||
                    targetLang.equals("zh-Hant", ignoreCase = true) -> "zh-Hant"
                targetLang.startsWith("zh", ignoreCase = true) -> "zh-Hans"
                else -> targetLang
            }

        try {
            val formBody =
                FormBody.Builder()
                    .add("fromLang", "auto-detect")
                    .add("text", text)
                    .add("to", to)
                    .add("token", currentToken)
                    .add("key", currentKey)
                    .build()

            val url =
                "https://www.bing.com/ttranslatev3?isVertical=1&&IG=$currentIg&IID=translator.5028"
            val request =
                Request.Builder()
                    .url(url)
                    .post(formBody)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

            translateClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    if (!body.isNullOrBlank()) {
                        val jsonArray = JSONArray(body)
                        if (jsonArray.length() > 0) {
                            val firstObj = jsonArray.getJSONObject(0)
                            val transArray = firstObj.getJSONArray("translations")
                            if (transArray.length() > 0) {
                                val res = transArray.getJSONObject(0).getString("text")
                                if (!res.isNullOrBlank()) return res
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log?.log(Log.DEBUG, "Bing translation failed: ${e.message}", null)
        }
        return null
    }

    private fun fetchDuckDuckGoTranslation(text: String, targetLang: String): String? {
        try {
            val req1 =
                Request.Builder()
                    .url("https://duckduckgo.com/?q=translate")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
            val vqd =
                translateClient.newCall(req1).execute().use { response ->
                    if (!response.isSuccessful) return null
                    val html = response.body.string()
                    Regex("""vqd=['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                        ?: Regex("""vqd=([a-zA-Z0-9_\-]+)""").find(html)?.groupValues?.get(1)
                } ?: return null

            val to =
                if (targetLang.startsWith("zh", ignoreCase = true)) "zh-Hans" else targetLang
            val url = "https://duckduckgo.com/translation.js?query=translate&vqd=$vqd&to=$to"
            val mediaType = "text/plain; charset=UTF-8".toMediaType()
            val body = text.toRequestBody(mediaType)
            val req2 =
                Request.Builder()
                    .url(url)
                    .post(body)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

            translateClient.newCall(req2).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body.string())
                    val translated = json.optString("translated")
                    if (!translated.isNullOrBlank()) {
                        return translated
                    }
                }
            }
        } catch (e: Exception) {
            log?.log(Log.DEBUG, "DuckDuckGo translation failed: ${e.message}", null)
        }
        return null
    }

    private fun fetchGoogleTranslation(text: String, targetLang: String): String? {
        try {
            val tl =
                when {
                    targetLang.startsWith("zh-TW", ignoreCase = true) ||
                        targetLang.startsWith("zh-HK", ignoreCase = true) -> "zh-TW"
                    targetLang.startsWith("zh", ignoreCase = true) -> "zh-CN"
                    else -> targetLang
                }
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url =
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$tl&dt=t&q=$encoded"
            val request =
                Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    .build()

            translateClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    if (!body.isNullOrBlank()) {
                        val jsonArray = JSONArray(body)
                        val sentences = jsonArray.getJSONArray(0)
                        val sb = StringBuilder()
                        for (i in 0 until sentences.length()) {
                            val sentence = sentences.getJSONArray(i)
                            sb.append(sentence.getString(0))
                        }
                        if (sb.isNotEmpty()) {
                            return sb.toString()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log?.log(Log.DEBUG, "Google RPC failed: ${e.message}", null)
        }
        return null
    }
}
