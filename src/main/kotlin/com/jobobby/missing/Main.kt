package com.jobobby.missing

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.addCookie
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Cookie
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.encodeBase64
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.jsoup.Jsoup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.simple.SimpleLogger
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private val prettyJson = Json {
    prettyPrint = true
}
val HiraganaRange1 = '\u3041'..'\u3096'
val HiraganaRange2 = '\u3099'..'\u309f'
val JapanesePunctuation = '\u3000'..'\u303f'
val KatakanaRange = '\u30a0'..'\u30ff'
val FullWidthRomanCharactersAndHalfWidthKatakana = '\uff00'..'\uffef'
val CjkUnifiedIdeographsCommonAndUncommonKanji = '\u4e00'..'\u9faf'

@OptIn(ExperimentalSerializationApi::class)
suspend fun main(args: Array<String>) {
    val debug = args.contains("debug")
    if (debug) {
        System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "DEBUG")
    }

    val logger = LoggerFactory.getLogger("Main")
    val apiKey = args.getOrElse(0) {
        logger.error("Missing LANraragi api key")
        exitProcess(201)
    }
    val lanraragiLink = args.getOrElse(1) {
        logger.error("Missing LANraragi link")
        exitProcess(202)
    }.trimEnd('/')

    val filterJpTitles = args.any { it.equals("filterJpTitles", true) }

    val sid = args.find { it.startsWith("fakku_sid=", true) }?.removePrefix("fakku_sid=")

    val disableNyaaSearch = args.any { it.equals("disableNyaaSearch", true) }

    val lanraragiClient = HttpClient(OkHttp) {
        expectSuccess = true
        install(DefaultRequest) {
            headers {
                append("Authorization", "Bearer " + apiKey.encodeBase64())
            }
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                }
            )
        }
        if (debug) {
            install(Logging) {
                level = LogLevel.INFO
            }
        }
        install(HttpTimeout)
    }

    val client = HttpClient(OkHttp) {
        engine {
            addInterceptor(RateLimitInterceptor(4, 1, TimeUnit.SECONDS))
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                }
            )
        }
        if (debug) {
            install(Logging) {
                level = LogLevel.INFO
            }
        }
        if (sid != null) {
            install(HttpCookies) {
                default {
                    addCookie(
                        "https://www.fakku.net/",
                        Cookie(
                            name = "fakku_sid",
                            value = sid,
                            httpOnly = true
                        )
                    )
                }
            }
        }
        install(HttpTimeout)
    }

    val nyaaClient = HttpClient(OkHttp) {
        engine {
            addInterceptor(RateLimitInterceptor(1, 1, TimeUnit.SECONDS))
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                }
            )
        }
        if (debug) {
            install(Logging) {
                level = LogLevel.INFO
            }
        }
        install(HttpTimeout)
    }

    logger.info("Getting all lanraragi galleries")
    val archives = lanraragiClient.get("$lanraragiLink/api/archives")
        .body<List<LanraragiArchive>>()


    logger.info(
        "Select a type of hentai you are searching through.\n${LinkType.entries.mapIndexed { index, linkType -> 
            "${index + 1} for ${linkType.name}"
        }.joinToString(separator = "\n")}"
    )
    val type = getInput(
        logger,
        mapper = { result ->
            result?.toIntOrNull()?.minus(1)?.let { LinkType.entries.getOrNull(it) }
        }
    ) { false }


    when (type) {
        LinkType.Publisher -> {
            logger.info("Find a publisher at https://www.fakku.net/hentai/publishers to check and paste the link:")
        }
        LinkType.Tag -> {
            logger.info("Find a tag at https://www.fakku.net/tags to check and paste the link:")
        }
        LinkType.Artist -> {
            logger.info("Find a artist at https://www.fakku.net/hentai/artists to check and paste the link:")
        }
        LinkType.Series -> {
            logger.info("Find a series at https://www.fakku.net/collections to check and paste the link:")
        }
        LinkType.Circle -> {
            logger.info("Find a circle at https://www.fakku.net/hentai/circles to check and paste the link:")
        }
        LinkType.Parody -> {
            logger.info("Find a parody at https://www.fakku.net/hentai/series to check and paste the link:")
        }
        LinkType.Magazine -> {
            logger.info("Find a magazine at https://www.fakku.net/hentai/magazines to check and paste the link:")
        }
    }

    val fakkuLink = getInput(logger, { it }) { result ->
        result.isBlank() || !result.startsWith("https://www.fakku.net/")
    }

    val hentai: MutableList<Pair<String, String>> = mutableListOf()
    var page = 1
    var selectionTitle = ""
    do {
        logger.info("Getting page $page")
        val hasNextPage: Boolean
        hentai += client.get(fakkuLink.trimEnd('/') + "/page/" + page) {
            expectSuccess = true
        }.bodyAsText()
            .let(Jsoup::parse)
            .also { document ->
                if (page == 1) {
                    selectionTitle = document.select("li.inline span").lastOrNull()!!.text()
                    logger.info(selectionTitle)
                }
                val lastPage = document.select("div.col-span-full .inline-block:not(.group)")
                    .lastOrNull()
                logger.debug(lastPage.toString())
                hasNextPage = lastPage != null && lastPage.tagName() != "span"
            }
            .select(".col-comic")
            .filter { element ->
                element.selectFirst(".shadow-lg a")
                    ?.attr("href")
                    ?.startsWith("/hentai/") == true
            }
            .mapNotNull { element ->
                element.selectFirst(".text-brand-light")?.let {
                    it.text() to ("https://fakku.net" + it.attr("href"))
                }
            }
        page++
    } while (hasNextPage)

    val total: Int
    var progress = 1
    val results = hentai
        .filterNot { (title) ->
            val archivesWithTitle = archives.filter { it.title.equals(title, true) }
            if (archivesWithTitle.isNotEmpty()) {
                archivesWithTitle.any { archive ->
                    when (type) {
                        LinkType.Publisher -> {
                            val index = archive.tags.indexOf("publisher:", ignoreCase = true)
                            if (index >= 0) {
                                archive.tags.substring(index + 10)
                                    .substringBefore(",")
                                    .trim()
                                    .equals(selectionTitle, true)
                            } else true
                        }
                        LinkType.Tag -> archive.tags.split(",").any {
                            it.trim().equals(selectionTitle, true)
                        }
                        LinkType.Artist -> {
                            val index = archive.tags.indexOf("artist:", ignoreCase = true)
                            if (index >= 0) {
                                archive.tags.substring(index + 7)
                                    .substringBefore(",")
                                    .trim()
                                    .equals(selectionTitle, true)
                            } else true
                        }
                        // Can't check if its part of a series
                        LinkType.Series -> true
                        LinkType.Circle -> {
                            val index = archive.tags.indexOf("circle:", ignoreCase = true)
                            if (index >= 0) {
                                archive.tags.substring(index + 7)
                                    .substringBefore(",")
                                    .trim()
                                    .equals(selectionTitle, true)
                            } else true
                        }
                        LinkType.Parody -> {
                            val index = archive.tags.indexOf("series:", ignoreCase = true)
                            if (index >= 0) {
                                archive.tags.substring(index + 7)
                                    .substringBefore(",")
                                    .trim()
                                    .equals(selectionTitle, true)
                            } else true
                        }
                        LinkType.Magazine -> {
                            val index = archive.tags.indexOf("magazine:", ignoreCase = true)
                            if (index >= 0) {
                                archive.tags.substring(index + 9)
                                    .substringBefore(",")
                                    .trim()
                                    .equals(selectionTitle, true)
                            } else true
                        }
                    }
                }
            } else false
        }
        .also {
            total = it.size
        }
        .asFlow()
        .map { (title, url) ->
            val searchLink =
                "https://sukebei.nyaa.si/?f=0&c=0_0&q=${title.encodeURLParameter(spaceToPlus = true)}"
            logger.info("Getting Nyaa.si links for $title")
            val searchResult = SearchResult(
                title,
                url,
                searchLink
            )
            if (disableNyaaSearch) {
                return@map searchResult
            }
            try {
                searchResult.copy(
                    torrents = nyaaClient.get(searchLink)
                        .bodyAsText()
                        .let(Jsoup::parse)
                        .select("table tbody tr")
                        .mapNotNull { element ->
                            val items = element.select("td")

                            NyaaTorrent(
                                category = when (items.getOrNull(0)?.selectFirst("a")?.attr("href")) {
                                    "/?c=1_2" -> "Doujinshi"
                                    "/?c=1_4" -> "Manga"
                                    else -> return@mapNotNull null
                                },
                                name = items.getOrNull(1)?.selectFirst("a:not(.comments)")?.attr("title")
                                    ?: return@mapNotNull null,
                                link = items.getOrNull(1)?.selectFirst("a:not(.comments)")?.attr("href")?.let {
                                    "https://sukebei.nyaa.si$it"
                                } ?: return@mapNotNull null,
                                torrent = items.getOrNull(2)?.selectFirst("i.fa-download")?.parent()?.attr("href")?.let {
                                    "https://sukebei.nyaa.si$it"
                                },
                                magnet = items.getOrNull(2)?.selectFirst("i.fa-magnet")?.parent()?.attr("href"),
                                size = items.getOrNull(3)?.text() ?: return@mapNotNull null,
                                date = items.getOrNull(4)?.text() ?: return@mapNotNull null,
                                seeders = items.getOrNull(5)?.text()?.toIntOrNull() ?: return@mapNotNull null,
                                leechers = items.getOrNull(6)?.text()?.toIntOrNull() ?: return@mapNotNull null
                            )
                        }
                        .let {
                            if (filterJpTitles) {
                                it.filterNot { (_, name) ->
                                    name.any {
                                        it in HiraganaRange1 ||
                                            it in HiraganaRange2 ||
                                            it in JapanesePunctuation ||
                                            it in KatakanaRange ||
                                            it in FullWidthRomanCharactersAndHalfWidthKatakana ||
                                            it in CjkUnifiedIdeographsCommonAndUncommonKanji
                                    }
                                }
                            } else {
                                it
                            }
                        }
                )
            } catch (e: Exception) {
                searchResult.copy(failed = true)
            }
        }
        .onEach { (title, url, _, links, failed) ->
            if (links.isNotEmpty()) {
                logger.info(
                    "\nFound links for $title ($url):${
                        links.joinToString(
                            separator = "\n",
                            prefix = "\n\n",
                            postfix = "\n"
                        ) { it.link }
                    }"
                )
            } else if (failed) {
                logger.error("Search failed for $title ($url)")
            } else {
                logger.info("No links found for $title ($url)")
            }
            logger.info("Finished ${progress++}/${total}")
        }
        .toList()

    logger.info("Writing $selectionTitle.txt")
    Path("$selectionTitle.txt").apply {
        deleteIfExists()
        writeText(
            buildString {
                results.filter { it.torrents.isNotEmpty() }.let { linkResults ->
                    if (linkResults.isNotEmpty()) {
                        appendLine("Search results:")
                        appendLine()
                        linkResults.forEach { (title, url, searchLink, links) ->
                            append(title)
                            append(" (")
                            append(url)
                            appendLine("):")
                            append("Search link: ")
                            appendLine(searchLink)
                            appendLine("Torrents:")
                            links.forEach {
                                appendLine(it.link)
                            }
                            appendLine()
                            appendLine()
                        }
                    } else {
                        appendLine("No search results found.")
                        appendLine()
                    }
                }
                results.filter { it.failed }.let { linkResults ->
                    if (linkResults.isNotEmpty()) {
                        appendLine("Search failed for:")
                        appendLine()
                        linkResults.forEach { (title, url, searchLink) ->
                            append(title)
                            append(" (")
                            append(url)
                            append("): ")
                            appendLine(searchLink)
                        }
                        appendLine()
                    }
                }
                results.filter { it.torrents.isEmpty() && !it.failed }.let { linkResults ->
                    if (linkResults.isNotEmpty()) {
                        appendLine("No results for:")
                        appendLine()
                        linkResults.forEach { (title, url, searchLink) ->
                            append(title)
                            append(" (")
                            append(url)
                            append("): ")
                            appendLine(searchLink)
                        }
                    } else {
                        appendLine("Results found for every hentai.")
                        appendLine()
                    }
                }
            }
        )
    }

    logger.info("Writing $selectionTitle.json")
    Path("$selectionTitle.json").apply {
        deleteIfExists()
        outputStream().use {
            prettyJson.encodeToStream(results, it)
        }
    }
    exitProcess(0)
}

@Serializable
data class SearchResult(
    val title: String,
    val fakkuLink: String,
    val nyaaSearchLink: String,
    val torrents: List<NyaaTorrent> = emptyList(),
    val failed: Boolean = false
)

@Serializable
data class NyaaTorrent(
    val category: String,
    val name: String,
    val link: String,
    val torrent: String? = null,
    val magnet: String? = null,
    val size: String,
    val date: String,
    val seeders: Int,
    val leechers: Int
)

enum class LinkType {
    Tag,
    Publisher,
    Artist,
    Series,
    Circle,
    Parody,
    Magazine
}

fun <T> getInput(
    logger: Logger,
    mapper: (String?) -> T,
    resultNotValid: (T & Any) -> Boolean
): T & Any {
    var result: T?
    do {
        result = readlnOrNull()?.trim()?.let(mapper)
        if (result == null || resultNotValid(result)) {
            logger.info("Retry")
        }
    } while (result == null || resultNotValid(result))
    return result
}