package com.pelis182

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.*
import com.lagradost.cloudstream3.utils.*
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
        "$mainUrl/category/accion/" to "Acción",
        "$mainUrl/category/animacion/" to "Animación",
        "$mainUrl/category/anime/" to "Anime",
        "$mainUrl/category/aventura/" to "Aventura",
        "$mainUrl/category/ciencia-ficcion/" to "Ciencia ficción",
        "$mainUrl/category/comedia/" to "Comedia",
        "$mainUrl/category/corto/" to "Corto",
        "$mainUrl/category/deporte/" to "Deporte",
        "$mainUrl/category/disney/" to "Disney",
        "$mainUrl/category/disney-channel/" to "Disney Channel",
        "$mainUrl/category/disney-nature/" to "Disney Nature",
        "$mainUrl/category/documental/" to "Documental",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/fantasia/" to "Fantasía",
        "$mainUrl/category/guerra/" to "Guerra",
        "$mainUrl/category/horror/" to "Terror",
        "$mainUrl/category/misterio-2/" to "Misterio",
        "$mainUrl/category/musica/" to "Música",
        "$mainUrl/category/musical/" to "Musical",
        "$mainUrl/category/pixar/" to "Pixar",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/superheroes/" to "Superhéroes",
        "$mainUrl/category/thriller/" to "Suspenso",
        "$mainUrl/category/western/" to "Western"
    )
    private fun Element.card(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val title = selectFirst("h3")?.text()?.takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(title, fixUrl(a.attr("href")), TvType.Movie) {
            posterUrl = selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = (if (page == 1) request.data else nextPages["${request.data}|$page"])
            ?: return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        val doc = app.get(url).document
        val items = doc.select("article.p182-card").mapNotNull { it.card() }.distinctBy { it.url }
        val next = doc.selectFirst("a.next.page-numbers, a[rel=next], .pagination a.next")?.attr("href")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        if (next != null && next != url) nextPages["${request.data}|${page + 1}"] = next
        return newHomePageResponse(request.name, items, next != null && next != url)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        var url: String? = "$mainUrl/?s=$q"
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
            when {
                it.has("@graph") -> it.get("@graph").toList()
                it.isArray -> it.toList()
                else -> listOf(it)
            }
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
        val doc = app.get(data).document
        val frames = doc.select("#p182-theater iframe[src], #p182-theater iframe[data-src]").map { it.attr("src").ifBlank { it.attr("data-src") } }.filter { it.isNotBlank() }.map { fixUrl(it) }.distinct()
        var found = false
        for (frame in frames) loadExtractor(frame, data, subtitleCallback) { found = true; callback(it) }
        return found
    }
}

class Paulinito : ExtractorApi() {
    override val name = "Paulinito"
    override val mainUrl = "https://paulinito.com"
    override val requiresReferer = true
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val text = app.get(url, referer = referer ?: "https://pelis182.net/").text
        val sources = Regex("""sources\s*:\s*(\[[\s\S]*?\])""").find(text)?.groupValues?.get(1) ?: return
        val array = runCatching { mapper.readTree(sources) }.getOrNull() ?: return
        for (source in array) {
            val file = source.get("file")?.asText()?.takeIf { it.startsWith("https://") } ?: continue
            if (file.substringBefore('?').endsWith(".m3u8", true)) M3u8Helper.generateM3u8(name, file, url).forEach(callback)
        }
    }
}
