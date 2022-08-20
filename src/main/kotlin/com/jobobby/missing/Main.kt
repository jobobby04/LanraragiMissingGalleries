package com.jobobby.missing

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
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
import org.slf4j.impl.SimpleLogger
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private val prettyJson = Json {
    prettyPrint = true
}

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

    val lanraragiClient = HttpClient(OkHttp) {
        expectSuccess = true
        install(DefaultRequest) {
            headers {
                append("Authorization", "Bearer " + apiKey.encodeBase64())
            }
        }
        install(ContentNegotiation) {
            json()
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
            json()
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


    logger.info("Select a type of hentai you are searching through.\nt for Tags\np for Publisher")
    val validTypes = listOf("t", "p", "a", "c")
    val type = getInput(logger) {
        it !in validTypes
    }.toType()

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
        LinkType.Collection -> {
            logger.info("Find a artist at https://www.fakku.net/collections to check and paste the link:")
        }
        else -> {
            logger.info("Invalid type")
            exitProcess(301)
        }
    }

    val fakkuLink = getInput(logger) { result ->
        result.isNullOrBlank() || !result.startsWith("https://www.fakku.net/")
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
                        // Can't check if its part of a collection
                        LinkType.Collection -> true
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
            SearchResult(
                title,
                url,
                searchLink,
                client.get(searchLink)
                    .bodyAsText()
                    .let(Jsoup::parse)
                    .select("table tr.default td[colspan=\"2\"] a:not(.comments)")
                    .map { "https://sukebei.nyaa.si" + it.attr("href") }
            )
        }
        .onEach { (title, url, _, links) ->
            if (links.isNotEmpty()) {
                logger.info(
                    "\nFound links for $title ($url):${
                        links.joinToString(
                            separator = "\n",
                            prefix = "\n\n",
                            postfix = "\n"
                        )
                    }"
                )
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
                                appendLine(it)
                            }
                            appendLine()
                            appendLine()
                        }
                    } else {
                        appendLine("No search results found.")
                        appendLine()
                    }
                }
                results.filter { it.torrents.isEmpty() }.let { linkResults ->
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
        prettyJson.encodeToStream(results, outputStream())
    }
}

@Serializable
data class SearchResult(
    val title: String,
    val fakkuLink: String,
    val nyaaSearchLink: String,
    val torrents: List<String>
)

enum class LinkType {
    Tag,
    Publisher,
    Artist,
    Collection
}

fun String.toType() = when (this) {
    "t" -> LinkType.Tag
    "p" -> LinkType.Publisher
    "a" -> LinkType.Artist
    "c" -> LinkType.Collection
    else -> null
}

fun getInput(logger: Logger, resultNotValid: (String?) -> Boolean): String {
    var result: String?
    do {
        result = readlnOrNull()?.trim()
        if (resultNotValid(result)) {
            logger.info("Retry")
        }
    } while (result == null || resultNotValid(result))
    return result
}