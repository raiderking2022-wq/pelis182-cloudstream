package com.pelis182

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder
import org.jsoup.nodes.Element

@CloudstreamPlugin
class Pelis182Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Pelis182())
        registerExtractorAPI(Paulinito())
    }
}

class Pelis182 : MainAPI() {
    override var mainUrl = "https://pelis182.net"
    override var name = "Pelis182"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    private val nextPages = mutableMapOf<String, String>()
    override val mainPage = mainPageOf(
        "$mainUrl/catalogo/" to "Películas",
        "$mainUrl/catalogo/?orden=tendencias" to "Tendencias",
        *listOf("accion|Acción", "animacion|Animación", "anime|Anime", "aventura|Aventura", "ciencia-ficcion|Ciencia ficción", "comedia|Comedia", "corto|Corto", "deporte|Deporte", "disney|Disney", "disney-channel|Disney Channel", "disney-nature|Disney Nature", "documental|Documental", "drama|Drama", "fantasia|Fantasía", "guerra|Guerra", "horror|Terror", "misterio-2|Misterio", "musica|Música", "musical|Musical", "pixar|Pixar", "romance|Romance", "superheroes|Superhéroes", "thriller|Suspenso", "western|Western").map {
            "$mainUrl/category/${it.substringBefore('|')}/" to it.substringAfter('|')
        }.toTypedArray()
    )
    private fun Element.card(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val title = selectFirst("h3")?.text()?.takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(title, fixUrl(a.attr("href")), TvType.Movie) {
            posterUrl = selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = (if (page == 1) request.data else nextPages["${request.data}|$page"]) ?: return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        val doc = app.get(url).document
        val items = doc.select("article.p182-card").mapNotNull { it.card() }.distinctBy { it.url }
        val next = doc.selectFirst("a.next.page-numbers, a[rel=next], .pagination a.next")?.attr("href")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        if (next != null && next != url) nextPages["${request.data}|${page + 1}"] = next
        return newHomePageResponse(request.name, items, next != null && next != url)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        var url: String? = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()
        while (url != null && seen.add(url)) {
            val doc = app.get(url).document
            results.addAll(doc.select("article.p182-card").mapNotNull { it.card() })
            url = doc.selectFirst("a.next.page-numbers, a[rel=next], .pagination a.next")?.attr("href")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }
        return results.distinctBy { it.url }
    }
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val nodes = doc.select("script[type=application/ld+json]").mapNotNull { runCatching { mapper.readTree(it.data()) }.getOrNull() }.flatMap {
            when { it.has("@graph") -> it.get("@graph").toList(); it.isArray -> it.toList(); else -> listOf(it) }
        }
        val movie = nodes.firstOrNull { it.get("@type")?.asText() == "Movie" }
        val title = movie?.get("name")?.asText() ?: doc.selectFirst("h1")?.text() ?: doc.title()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = movie?.get("image")?.takeIf { it.isArray }?.lastOrNull()?.asText() ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            backgroundPosterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            plot = movie?.get("description")?.asText() ?: doc.selectFirst("meta[name=description]")?.attr("content")
            year = movie?.get("dateCreated")?.asText()?.take(4)?.toIntOrNull()
            tags = movie?.get("genre")?.map { it.asText() }
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val response = app.get(data)
        if (response.code !in 200..299) throw ErrorLoadingException("Pelis182: ficha HTTP ${response.code}")
        val doc = response.document
        val urls = linkedSetOf<String>()
        for (el in doc.select("#p182-theater iframe, .theater iframe, iframe[src*=paulinito], [data-src*=paulinito], a[href*=paulinito]")) {
            for (attr in listOf("data-src", "src", "href")) {
                val raw = el.attr(attr).trim()
                if (raw.isNotBlank() && !raw.startsWith("about:") && !raw.startsWith("javascript:")) {
                    runCatching { URI(response.url).resolve(raw).toString() }.getOrNull()?.let { urls.add(it) }
                }
            }
        }
        val html = response.text.replace("\\/", "/")
        Regex("""https?://(?:www\.)?paulinito\.com/player/[A-Za-z0-9_-]+/?""").findAll(html).forEach { urls.add(it.value) }
        if (urls.isEmpty()) throw ErrorLoadingException("Pelis182: no se encontró el iframe del reproductor")
        var found = false
        var lastError: String? = null
        for (frame in urls) {
            try {
                val host = URI(frame).host.orEmpty().lowercase()
                val receive: (ExtractorLink) -> Unit = { found = true; callback(it) }
                if (host == "paulinito.com" || host == "www.paulinito.com") Paulinito().getUrl(frame, response.url, subtitleCallback, receive)
                else loadExtractor(frame, response.url, subtitleCallback, receive)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { lastError = e.message }
        }
        if (!found) throw ErrorLoadingException("Pelis182: " + (lastError ?: "el reproductor no devolvió fuentes de video"))
        return found
    }
}

class Paulinito : ExtractorApi() {
    override val name = "Paulinito"
    override val mainUrl = "https://paulinito.com"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val response = app.get(url, referer = referer ?: "https://pelis182.net/")
        if (response.code !in 200..299) throw ErrorLoadingException("Paulinito: HTTP ${response.code}")
        val text = response.text.replace("\\/", "/")
        val files = linkedMapOf<String, String>()
        Regex("""sources\s*:\s*(\[[\s\S]*?\])""").find(text)?.groupValues?.get(1)?.let { sources ->
            runCatching { mapper.readTree(sources) }.getOrNull()?.forEach { source ->
                val file = source.get("file")?.asText()?.takeIf { it.isNotBlank() } ?: return@forEach
                files[file] = source.get("label")?.asText().orEmpty()
            }
        }
        Regex("""https?://[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE).findAll(text).forEach { files.putIfAbsent(it.value, "") }
        if (files.isEmpty()) throw ErrorLoadingException("Paulinito: página recibida sin m3u8 ni mp4")
        // El CDN de video rechaza con 404 toda petición sin el Referer del reproductor:
        // hay que enviarlo tanto al leer la lista maestra como al reproducir.
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to response.url)
        for ((raw, label) in files) {
            val file = raw.replace("&amp;", "&")
            if (!file.startsWith("https://") && !file.startsWith("http://")) continue
            val hls = file.substringBefore('?').endsWith(".m3u8", true)
            if (hls) {
                // Enumera las calidades reales de la lista maestra (360p a 1080p) con las cabeceras correctas.
                val variants = runCatching { M3u8Helper.generateM3u8(name, file, response.url, headers = headers) }.getOrNull()
                if (!variants.isNullOrEmpty()) {
                    variants.forEach(callback)
                    continue
                }
            }
            callback(newExtractorLink(name, label.ifBlank { name }, file, if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = response.url
                quality = label.filter(Char::isDigit).takeIf { it.isNotBlank() }?.toIntOrNull() ?: Qualities.Unknown.value
                this.headers = headers
            })
        }
    }
}
