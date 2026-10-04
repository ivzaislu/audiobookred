package com.example.data.parser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class AbredAplConfig(
    val baseUrl: String,
    val referer: String,
    val searchUrlTemplate: String,
    val paginationPattern: String,
    val listPageSize: Int,
    val listItemSelector: String,
    val listTitleSelector: String,
    val listCoverSelector: String,
    val collectionItemSelector: String,
    val collectionTitleSelector: String,
    val detailRootSelector: String,
    val detailTitleSelector: String,
    val detailCoverSelector: String,
    val detailAuthorSelector: String,
    val detailPerformerSelector: String,
    val detailDurationSelector: String,
    val detailSeriesSelector: String,
    val detailDescriptionSelector: String,
    val playlistItemSelector: String,
    val playlistItemNameSelector: String,
    val playlistItemTimeSelector: String,
    val playlistUrlSelector: String,
    val playlistScriptMarker: String,
    val playlistKey: String,
    val playlistIdField: String,
    val playlistTitleField: String,
    val playlistDurationField: String,
    val playlistUrlField: String,
    val audioSelector: String,
    val audioUrlAttribute: String,
) {
    companion object {
        fun defaults() = AbredAplConfig(
            baseUrl = "https://audiopolka.club",
            referer = "https://audiopolka.club",
            searchUrlTemplate = "https://audiopolka.club/search/?q={query}",
            paginationPattern = "/p{N}/",
            listPageSize = 24,
            listItemSelector = "#BL .book-list-item",
            listTitleSelector = ".book-list-item-name-link",
            listCoverSelector = ".book-list-item-cover-img img",
            collectionItemSelector = ".book-list-item, .book-item, .book-card, article.book, [class*='book-item']",
            collectionTitleSelector = ".book-list-item-name-link, .book-list-item-name a, a.book-list-item-name-link, h2 a, h3 a, .book-title a, .title a",
            detailRootSelector = ".book-page-main",
            detailTitleSelector = ".book-page-title a",
            detailCoverSelector = ".book-page-cover img#book-page-cover-image",
            detailAuthorSelector = ".book-page-meta-line span[itemprop=\"author\"] a",
            detailPerformerSelector = ".book-page-meta-line:nth-of-type(2) a",
            detailDurationSelector = ".book-page-meta-line:nth-of-type(3)",
            detailSeriesSelector = ".book-page-meta-line a[href^=\"/series/\"]",
            detailDescriptionSelector = ".book-page-annotation-content",
            playlistItemSelector = ".book-page-player-playlist .book-player-playlist-item",
            playlistItemNameSelector = ".book-player-playlist-item-name",
            playlistItemTimeSelector = ".book-player-playlist-item-time",
            playlistUrlSelector = "[data-url], [data-file], .book-player-playlist-item-play[data-url], .book-player-playlist-item[data-url]",
            playlistScriptMarker = "KB.playerInit(",
            playlistKey = "playlist",
            playlistIdField = "fileId",
            playlistTitleField = "title",
            playlistDurationField = "duration",
            playlistUrlField = "src",
            audioSelector = "audio source, audio[src], source[src*='.mp3']",
            audioUrlAttribute = "src",
        )

        fun load(context: Context): AbredAplConfig {
            val fallback = defaults()
            return runCatching {
                val raw = context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
                val root = JSONObject(raw)
                val scraper = root.optJSONObject("scraper") ?: JSONObject()
                val selectors = scraper.optJSONObject("selectors") ?: JSONObject()
                val list = selectors.optJSONObject("list") ?: JSONObject()
                val detail = selectors.optJSONObject("detail") ?: JSONObject()
                val playlist = scraper.optJSONObject("playlist") ?: JSONObject()
                val fields = playlist.optJSONObject("fields") ?: JSONObject()
                val strategies = root.optJSONObject("extraction")
                    ?.optJSONObject("track")
                    ?.optJSONArray("strategies") ?: JSONArray()
                val css = strategy(strategies, "css")
                val js = strategy(strategies, "javascript")
                val audio = strategy(strategies, "audio")
                val audiobooks = root.optJSONObject("categories")?.optJSONObject("audiobooks")
                val pagination = audiobooks?.optJSONObject("pagination")
                val collectionSelectors = audiobooks?.optJSONObject("selectors")
                val search = root.optJSONObject("search")?.optJSONObject("generic")
                val headers = root.optJSONObject("headers")

                fallback.copy(
                    baseUrl = root.optString("baseUrl", fallback.baseUrl),
                    referer = headers?.optString("Referer", fallback.referer) ?: fallback.referer,
                    searchUrlTemplate = search?.optString("url", fallback.searchUrlTemplate) ?: fallback.searchUrlTemplate,
                    paginationPattern = pagination?.optString("pattern", fallback.paginationPattern) ?: fallback.paginationPattern,
                    listPageSize = selectors.optInt("paginationItemsPerPage", fallback.listPageSize)
                        .takeIf { it > 0 } ?: fallback.listPageSize,
                    listItemSelector = list.optString("item", fallback.listItemSelector),
                    listTitleSelector = list.optString("link", fallback.listTitleSelector),
                    listCoverSelector = list.optString("cover", fallback.listCoverSelector),
                    collectionItemSelector = collectionSelectors?.optString("item", fallback.collectionItemSelector)
                        ?: fallback.collectionItemSelector,
                    collectionTitleSelector = collectionSelectors?.optString("title", fallback.collectionTitleSelector)
                        ?: fallback.collectionTitleSelector,
                    detailRootSelector = detail.optString("root", fallback.detailRootSelector),
                    detailTitleSelector = detail.optString("title", fallback.detailTitleSelector),
                    detailCoverSelector = detail.optString("cover", fallback.detailCoverSelector),
                    detailAuthorSelector = detail.optString("author", fallback.detailAuthorSelector),
                    detailPerformerSelector = detail.optString("performer", fallback.detailPerformerSelector),
                    detailDurationSelector = detail.optString("duration", fallback.detailDurationSelector),
                    detailSeriesSelector = detail.optString("series", fallback.detailSeriesSelector),
                    detailDescriptionSelector = detail.optString("description", fallback.detailDescriptionSelector),
                    playlistItemSelector = detail.optString("playlistItem", css?.optString("itemSelector", fallback.playlistItemSelector) ?: fallback.playlistItemSelector),
                    playlistItemNameSelector = detail.optString("playlistItemName", css?.optString("nameSelector", fallback.playlistItemNameSelector) ?: fallback.playlistItemNameSelector),
                    playlistItemTimeSelector = detail.optString("playlistItemTime", css?.optString("timeSelector", fallback.playlistItemTimeSelector) ?: fallback.playlistItemTimeSelector),
                    playlistUrlSelector = css?.optString("urlSelector", fallback.playlistUrlSelector) ?: fallback.playlistUrlSelector,
                    playlistScriptMarker = playlist.optString("scriptMarker", js?.optString("scriptMarker", fallback.playlistScriptMarker) ?: fallback.playlistScriptMarker),
                    playlistKey = playlist.optString("playlistKey", fallback.playlistKey),
                    playlistIdField = fields.optString("id", fallback.playlistIdField),
                    playlistTitleField = fields.optString("title", fallback.playlistTitleField),
                    playlistDurationField = fields.optString("durationSeconds", fallback.playlistDurationField),
                    playlistUrlField = fields.optString("url", js?.optString("playlistUrlField", fallback.playlistUrlField) ?: fallback.playlistUrlField),
                    audioSelector = audio?.optString("audioSelector", fallback.audioSelector) ?: fallback.audioSelector,
                    audioUrlAttribute = audio?.optString("urlAttribute", fallback.audioUrlAttribute) ?: fallback.audioUrlAttribute,
                )
            }.getOrDefault(fallback)
        }

        private fun strategy(array: JSONArray, type: String): JSONObject? {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("type") == type) return item
            }
            return null
        }

        private const val CONFIG_ASSET = "providers/apl.json"
    }
}