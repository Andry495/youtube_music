package com.youtubevoice.app.youtube

import com.youtubevoice.app.data.LibraryPlaylist
import com.youtubevoice.app.data.PlaylistInfo
import com.youtubevoice.app.data.ResolvedAudio
import com.youtubevoice.app.data.Subscription
import com.youtubevoice.app.data.Track
import com.youtubevoice.app.data.VideoRating
import com.youtubevoice.app.dpi.AppHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * Authenticated YouTube library via InnerTube + cookie session (device Google login).
 * No Google Cloud OAuth / Data API client required.
 */
class YoutubeLibraryApi {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private fun http() = AppHttp.client()

    // region Playlists

    suspend fun listMyPlaylists(cookie: String): List<LibraryPlaylist> =
        withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, LibraryPlaylist>()

            fun ingest(json: JSONObject) {
                walk(json) { obj ->
                    // Modern library cards
                    if (obj.has("lockupViewModel")) {
                        collectLibraryPlaylistFromLockup(obj.getJSONObject("lockupViewModel"))
                            ?.let { if (it.id !in found) found[it.id] = it }
                        return@walk
                    }
                    // Direct lockup node (BFS visits inner object too)
                    if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST" ||
                        looksLikePlaylistContentId(obj.optString("contentId"))
                    ) {
                        collectLibraryPlaylistFromLockup(obj)
                            ?.let { if (it.id !in found) found[it.id] = it }
                        return@walk
                    }

                    val playlistId = extractPlaylistId(obj) ?: return@walk
                    if (playlistId in found) return@walk
                    if (playlistId == "LL" || playlistId == "WL") {
                        val fallbackTitle = if (playlistId == "LL") "Понравившиеся" else "Смотреть позже"
                        val title = extractPlaylistTitleFromObj(obj) ?: fallbackTitle
                        found[playlistId] = LibraryPlaylist(
                            id = playlistId,
                            title = title,
                            itemCount = extractItemCount(obj),
                            thumbnailUrl = extractThumb(obj)
                                ?: extractThumbFromLockup(obj)
                        )
                        return@walk
                    }
                    val title = extractPlaylistTitleFromObj(obj) ?: return@walk
                    found[playlistId] = LibraryPlaylist(
                        id = playlistId,
                        title = title,
                        itemCount = extractItemCount(obj),
                        thumbnailUrl = extractThumb(obj) ?: extractThumbFromLockup(obj),
                        url = "https://youtube.com/playlist?list=$playlistId"
                    )
                }
            }

            // Library home + chip discovery
            val chipParams = linkedSetOf<String>()
            val libraryJson = innertube("browse", cookie, JSONObject().put("browseId", "FElibrary"))
            ingest(libraryJson)
            walk(libraryJson) { obj ->
                val label = extractText(obj.opt("text"))
                    ?: extractText(obj.opt("title"))
                    ?: return@walk
                val lower = label.lowercase()
                if (lower.contains("плейлист") || lower.contains("playlist") ||
                    lower.contains("ваши") || lower.contains("yours")
                ) {
                    val token = obj.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                        ?.optString("params")
                        ?.ifBlank { null }
                        ?: obj.optJSONObject("browseEndpoint")?.optString("params")?.ifBlank { null }
                        ?: obj.optString("params").ifBlank { null }
                    if (!token.isNullOrBlank()) chipParams += token
                }
            }
            // Known playlists filter + discovered chips
            chipParams += "EglwbGF5bGlzdHM%3D"
            for (params in chipParams) {
                runCatching {
                    ingest(
                        innertube(
                            "browse",
                            cookie,
                            JSONObject()
                                .put("browseId", "FElibrary")
                                .put("params", params)
                        )
                    )
                }
            }

            runCatching {
                ingest(
                    innertube(
                        "browse",
                        cookie,
                        JSONObject().put("browseId", "FEplaylist_aggregation")
                    )
                )
            }

            if ("LL" !in found) {
                found["LL"] = LibraryPlaylist("LL", "Понравившиеся", 0, null)
            }
            if ("WL" !in found) {
                found["WL"] = LibraryPlaylist("WL", "Смотреть позже", 0, null)
            }

            // Own / custom playlists first, then system shelves
            val system = setOf("LL", "WL")
            val custom = found.values.filter { it.id !in system }
            val shelves = found.values.filter { it.id in system }
            custom + shelves
        }

    private fun looksLikePlaylistContentId(id: String): Boolean {
        if (id.isBlank()) return false
        return id == "LL" || id == "WL" ||
            id.startsWith("PL") || id.startsWith("OL") ||
            id.startsWith("UU") || id.startsWith("RD") ||
            id.startsWith("VL")
    }

    private fun collectLibraryPlaylistFromLockup(lockup: JSONObject): LibraryPlaylist? {
        val rawId = lockup.optString("contentId").ifBlank {
            lockup.optJSONObject("rendererContext")
                ?.optJSONObject("commandContext")
                ?.optJSONObject("onTap")
                ?.optJSONObject("innertubeCommand")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
                .orEmpty()
        }.removePrefix("VL")
        if (!looksLikePlaylistContentId(rawId)) return null
        // Skip channel uploads shelf if it sneaks in
        if (rawId.startsWith("UU")) return null

        val meta = lockup.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel")
        val title = extractText(meta?.opt("title"))
            ?: extractText(lockup.opt("title"))
            ?: extractText(lockup.opt("accessibilityText"))
            ?: when (rawId) {
                "LL" -> "Понравившиеся"
                "WL" -> "Смотреть позже"
                else -> null
            }
            ?: return null

        val countText = extractText(meta?.opt("metadata"))
            ?: extractText(
                meta?.optJSONObject("metadata")
                    ?.optJSONObject("contentMetadataViewModel")
                    ?.optJSONArray("metadataRows")
                    ?.optJSONObject(0)
                    ?.optJSONArray("metadataParts")
                    ?.optJSONObject(0)
                    ?.opt("text")
            )
            ?: ""
        val itemCount = Regex("""(\d+)""").find(countText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 0

        return LibraryPlaylist(
            id = rawId,
            title = title,
            itemCount = itemCount,
            thumbnailUrl = extractThumb(lockup) ?: extractThumbFromLockup(lockup),
            url = "https://youtube.com/playlist?list=$rawId"
        )
    }

    private fun extractPlaylistTitleFromObj(obj: JSONObject): String? {
        extractText(obj.opt("title"))?.let { return it }
        extractText(obj.optJSONObject("metadata")?.opt("title"))?.let { return it }
        if (obj.has("lockupViewModel")) {
            return collectLibraryPlaylistFromLockup(obj.getJSONObject("lockupViewModel"))?.title
        }
        val meta = obj.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        return extractText(meta?.opt("title"))
    }

    private fun extractItemCount(obj: JSONObject): Int {
        obj.optJSONObject("contentDetails")?.optInt("itemCount")?.takeIf { it > 0 }?.let { return it }
        val text = extractText(obj.opt("videoCountText"))
            ?: extractText(obj.opt("videoCountShortText"))
            ?: extractText(obj.opt("thumbnailOverlays"))
            ?: ""
        return Regex("""(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun extractThumbFromLockup(obj: JSONObject): String? {
        val lockup = if (obj.has("lockupViewModel")) obj.getJSONObject("lockupViewModel") else obj
        return extractThumb(lockup)
    }

    suspend fun createPlaylist(
        cookie: String,
        title: String,
        description: String = "",
        privacyStatus: String = "PRIVATE"
    ): LibraryPlaylist = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("title", title)
            .put("description", description)
            .put("privacyStatus", privacyStatus.uppercase())
        val json = innertube("playlist/create", cookie, body)
        val id = json.optString("playlistId").ifBlank {
            json.optJSONObject("playlistId")?.optString("id").orEmpty()
        }
        require(id.isNotBlank()) { "Не удалось создать плейлист" }
        LibraryPlaylist(id = id, title = title, itemCount = 0, thumbnailUrl = null)
    }

    suspend fun deletePlaylist(cookie: String, playlistId: String) =
        withContext(Dispatchers.IO) {
            innertube("playlist/delete", cookie, JSONObject().put("playlistId", playlistId))
        }

    suspend fun loadPlaylist(cookie: String, playlistId: String): PlaylistInfo =
        withContext(Dispatchers.IO) {
            loadPlaylistTracks(playlistId, cookie)
        }

    /**
     * Public/authenticated playlist tracks.
     * Prefer /next (returns lockup cards for public PLs); browse VL is often nearly empty.
     */
    fun loadPlaylistTracks(playlistId: String, cookie: String? = null): PlaylistInfo {
        val id = playlistId.trim()
            .removePrefix("VL")
            .substringAfter("list=")
            .substringBefore("&")
            .substringBefore("?")
        require(id.isNotBlank()) { "Некорректный id плейлиста" }
        val browseId = "VL$id"

        val viaNext = runCatching { loadPlaylistViaNext(id, cookie) }.getOrNull()
        if (viaNext != null && viaNext.tracks.isNotEmpty()) return viaNext

        val guest = runCatching {
            loadBrowseVideosGuest(browseId, id)
        }.getOrNull()
        if (guest != null && guest.tracks.isNotEmpty()) return guest

        if (!cookie.isNullOrBlank()) {
            val authed = runCatching {
                loadBrowseVideos(cookie, browseId, id)
            }.getOrNull()
            if (authed != null && authed.tracks.isNotEmpty()) return authed

            val android = runCatching {
                loadBrowseVideosAndroid(cookie, browseId, id)
            }.getOrNull()
            if (android != null && android.tracks.isNotEmpty()) return android
        }

        return viaNext ?: guest ?: PlaylistInfo(id, id, "", null, emptyList())
    }

    /** WEB /next with playlistId — reliable for public channel playlists. */
    private fun loadPlaylistViaNext(playlistId: String, cookie: String?): PlaylistInfo {
        val tracks = mutableListOf<Track>()
        var title = playlistId
        var continuation: String? = null
        var first = true
        var guard = 0
        do {
            val payload = if (continuation == null) {
                JSONObject().put("playlistId", playlistId)
            } else {
                JSONObject().put("continuation", continuation)
            }
            val json = if (!cookie.isNullOrBlank()) {
                innertube("next", cookie, payload)
            } else {
                innertubeGuest("next", payload)
            }
            if (first) {
                title = extractPlaylistTitle(json) ?: title
                first = false
            }
            val before = tracks.size
            collectVideos(json, tracks, requirePlaylistId = playlistId)
            // Some clients omit playlistId on lockups — fall back once if filtered parse is empty
            if (tracks.size == before) {
                collectVideos(json, tracks, requirePlaylistId = null)
            }
            continuation = findContinuation(json)
            if (tracks.size == before && continuation != null) {
                guard++
                if (guard >= 2) break
            } else {
                guard = 0
            }
        } while (continuation != null && tracks.size < 500)

        return PlaylistInfo(
            id = playlistId,
            title = title,
            uploader = title,
            thumbnailUrl = tracks.firstOrNull()?.thumbnailUrl,
            tracks = tracks
        )
    }

    /**
     * Channel uploads via browse (Videos tab) or UU playlist.
     */
    suspend fun loadChannelVideos(cookie: String, channelId: String): PlaylistInfo =
        withContext(Dispatchers.IO) {
            val id = channelId.trim()
            val fromTab = runCatching {
                loadBrowseVideos(
                    cookie = cookie,
                    browseId = id,
                    resultId = id,
                    params = "EgZ2aWRlb3PyBgQKAjoA"
                )
            }.getOrNull()
            if (fromTab != null && fromTab.tracks.isNotEmpty()) return@withContext fromTab

            val uploadsId = "UU" + id.removePrefix("UC")
            loadBrowseVideos(cookie, "VL$uploadsId", id)
        }

    suspend fun loadChannelPlaylists(cookie: String, channelId: String): List<LibraryPlaylist> =
        withContext(Dispatchers.IO) {
            val json = innertube(
                "browse",
                cookie,
                JSONObject()
                    .put("browseId", channelId)
                    .put("params", "EglwbGF5bGlzdHPyBgQKAkIA") // playlists tab
            )
            val found = linkedMapOf<String, LibraryPlaylist>()
            walk(json) { obj ->
                val playlistId = extractPlaylistId(obj) ?: return@walk
                if (playlistId in found) return@walk
                // Skip uploads / system lists that aren't real channel playlists shelves sometimes
                val title = extractText(obj.opt("title"))
                    ?: extractText(obj.optJSONObject("metadata")?.opt("title"))
                    ?: return@walk
                val itemCount = Regex("""(\d+)""")
                    .find(extractText(obj.opt("videoCountText")).orEmpty())
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: 0
                found[playlistId] = LibraryPlaylist(
                    id = playlistId,
                    title = title,
                    itemCount = itemCount,
                    thumbnailUrl = extractThumb(obj)
                )
            }
            found.values.toList()
        }

    suspend fun search(cookie: String?, query: String): List<com.youtubevoice.app.data.SearchHit> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            require(q.isNotEmpty()) { "Введите запрос" }
            // Prefer session when available; InnerTube search also works lightly without cookie
            val authCookie = cookie.orEmpty()
            val json = if (authCookie.isNotBlank()) {
                innertube("search", authCookie, JSONObject().put("query", q))
            } else {
                // Guest WEB search still needs SAPISID for signed headers — fall back empty here;
                // repository NewPipe search is the primary path without login.
                return@withContext emptyList()
            }
            val hits = linkedMapOf<String, com.youtubevoice.app.data.SearchHit>()
            walk(json) { obj ->
                when {
                    obj.has("channelRenderer") -> {
                        val r = obj.getJSONObject("channelRenderer")
                        val id = r.optString("channelId").ifBlank { return@walk }
                        if (hits.containsKey("ch_$id")) return@walk
                        hits["ch_$id"] = com.youtubevoice.app.data.SearchHit.Channel(
                            id = "ch_$id",
                            title = extractText(r.opt("title")) ?: return@walk,
                            subtitle = extractText(r.opt("subscriberCountText")) ?: "Канал",
                            thumbnailUrl = extractThumb(r),
                            channelId = id
                        )
                    }
                    obj.has("playlistRenderer") -> {
                        val r = obj.getJSONObject("playlistRenderer")
                        val id = r.optString("playlistId").ifBlank { return@walk }
                        if (hits.containsKey("pl_$id")) return@walk
                        hits["pl_$id"] = com.youtubevoice.app.data.SearchHit.Playlist(
                            id = "pl_$id",
                            title = extractText(r.opt("title")) ?: return@walk,
                            subtitle = extractText(r.opt("shortBylineText")) ?: "Плейлист",
                            thumbnailUrl = extractThumb(r),
                            playlistId = id
                        )
                    }
                    obj.has("videoRenderer") -> {
                        val r = obj.getJSONObject("videoRenderer")
                        val id = r.optString("videoId").ifBlank { return@walk }
                        if (hits.containsKey("v_$id")) return@walk
                        hits["v_$id"] = com.youtubevoice.app.data.SearchHit.Video(
                            id = "v_$id",
                            title = extractText(r.opt("title")) ?: return@walk,
                            subtitle = extractText(r.opt("ownerText"))
                                ?: extractText(r.opt("shortBylineText"))
                                ?: "",
                            thumbnailUrl = extractThumb(r),
                            watchUrl = "https://youtube.com/watch?v=$id",
                            channelId = extractChannelId(r)
                        )
                    }
                }
            }
            hits.values.toList()
        }

    private fun loadBrowseVideos(
        cookie: String,
        browseId: String,
        resultId: String,
        params: String? = null
    ): PlaylistInfo = browseVideosPaged(browseId, resultId, params) { payload ->
        innertube("browse", cookie, payload)
    }

    private fun loadBrowseVideosGuest(
        browseId: String,
        resultId: String,
        params: String? = null
    ): PlaylistInfo = browseVideosPaged(browseId, resultId, params) { payload ->
        innertubeGuest("browse", payload)
    }

    private fun loadBrowseVideosAndroid(
        cookie: String,
        browseId: String,
        resultId: String
    ): PlaylistInfo = browseVideosPaged(browseId, resultId, null) { payload ->
        innertube("browse", cookie, payload, client = ClientType.ANDROID)
    }

    private fun browseVideosPaged(
        browseId: String,
        resultId: String,
        params: String?,
        fetch: (JSONObject) -> JSONObject
    ): PlaylistInfo {
        val tracks = mutableListOf<Track>()
        var title = resultId
        var continuation: String? = null
        var first = true
        var guard = 0
        do {
            val payload = if (continuation == null) {
                JSONObject().put("browseId", browseId).also {
                    if (!params.isNullOrBlank()) it.put("params", params)
                }
            } else {
                JSONObject().put("continuation", continuation)
            }
            val json = fetch(payload)
            if (first) {
                title = extractPlaylistTitle(json)
                    ?: extractChannelTitle(json)
                    ?: title
                first = false
            }
            val before = tracks.size
            collectVideos(json, tracks)
            continuation = findContinuation(json)
            if (tracks.size == before && continuation != null) {
                guard++
                if (guard >= 2) break
            } else {
                guard = 0
            }
        } while (continuation != null && tracks.size < 500)

        return PlaylistInfo(
            id = resultId,
            title = title,
            uploader = title,
            thumbnailUrl = tracks.firstOrNull()?.thumbnailUrl,
            tracks = tracks
        )
    }

    private fun collectVideos(
        json: JSONObject,
        tracks: MutableList<Track>,
        requirePlaylistId: String? = null
    ) {
        walk(json) { obj ->
            // Modern YouTube playlist / shelf cards
            if (obj.has("lockupViewModel")) {
                val lockup = obj.getJSONObject("lockupViewModel")
                val watchEndpoint = lockup.optJSONObject("rendererContext")
                    ?.optJSONObject("commandContext")
                    ?.optJSONObject("onTap")
                    ?.optJSONObject("innertubeCommand")
                    ?.optJSONObject("watchEndpoint")
                    ?: lockup.optJSONObject("onTap")
                        ?.optJSONObject("innertubeCommand")
                        ?.optJSONObject("watchEndpoint")
                if (requirePlaylistId != null) {
                    val pl = watchEndpoint?.optString("playlistId").orEmpty()
                    if (pl != requirePlaylistId && pl != "VL$requirePlaylistId") return@walk
                }
                val contentId = lockup.optString("contentId").ifBlank { null }
                val watchId = watchEndpoint?.optString("videoId")?.ifBlank { null }
                val videoId = sequenceOf(watchId, contentId)
                    .mapNotNull { it }
                    .firstOrNull { it.length == 11 }
                    ?: return@walk
                if (tracks.any { it.id == videoId }) return@walk
                val meta = lockup.optJSONObject("metadata")
                    ?.optJSONObject("lockupMetadataViewModel")
                val trackTitle = extractText(meta?.opt("title"))
                    ?: extractText(lockup.opt("title"))
                    ?: extractText(lockup.opt("accessibilityText"))
                    ?: "Видео"
                if (trackTitle.equals("Private video", true) ||
                    trackTitle.equals("Deleted video", true)
                ) return@walk
                tracks += Track(
                    id = videoId,
                    title = trackTitle,
                    artist = extractText(
                        meta?.optJSONObject("metadata")
                            ?.optJSONObject("contentMetadataViewModel")
                            ?.optJSONArray("metadataRows")
                            ?.optJSONObject(0)
                            ?.optJSONArray("metadataParts")
                            ?.optJSONObject(0)
                            ?.opt("text")
                    ).orEmpty(),
                    thumbnailUrl = extractThumb(lockup),
                    watchUrl = "https://youtube.com/watch?v=$videoId",
                    durationSeconds = 0L,
                    channelId = null,
                    playlistItemId = null
                )
                return@walk
            }

            val renderer = when {
                obj.has("playlistVideoRenderer") -> obj.getJSONObject("playlistVideoRenderer")
                obj.has("playlistPanelVideoRenderer") ->
                    obj.getJSONObject("playlistPanelVideoRenderer")
                obj.has("videoRenderer") -> {
                    if (requirePlaylistId != null) return@walk
                    obj.getJSONObject("videoRenderer")
                }
                obj.has("gridVideoRenderer") -> {
                    if (requirePlaylistId != null) return@walk
                    obj.getJSONObject("gridVideoRenderer")
                }
                obj.has("compactVideoRenderer") -> {
                    if (requirePlaylistId != null) return@walk
                    obj.getJSONObject("compactVideoRenderer")
                }
                obj.has("richItemRenderer") -> {
                    val content = obj.getJSONObject("richItemRenderer").optJSONObject("content")
                    content?.optJSONObject("videoRenderer")
                        ?: content?.optJSONObject("playlistVideoRenderer")
                        ?: content?.optJSONObject("playlistPanelVideoRenderer")
                }
                obj.has("videoId") &&
                    (obj.has("title") || obj.has("headline") || obj.has("lengthText") ||
                        obj.has("setVideoId")) -> obj
                else -> null
            } ?: return@walk

            if (requirePlaylistId != null) {
                val pl = renderer.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("playlistId")
                    .orEmpty()
                    .ifBlank {
                        renderer.optJSONObject("watchEndpoint")
                            ?.optString("playlistId")
                            .orEmpty()
                    }
                if (pl != requirePlaylistId && pl != "VL$requirePlaylistId") {
                    // playlistPanelVideoRenderer often has playlistId on the renderer itself
                    val directPl = renderer.optString("playlistId")
                    if (directPl != requirePlaylistId && directPl != "VL$requirePlaylistId") {
                        return@walk
                    }
                }
            }

            val videoId = sequenceOf(
                renderer.optString("videoId"),
                renderer.optJSONObject("onTap")
                    ?.optJSONObject("innertubeCommand")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId"),
                renderer.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")
            ).mapNotNull { it?.ifBlank { null } }
                .firstOrNull { it.length == 11 }
                ?: return@walk

            if (tracks.any { it.id == videoId }) return@walk
            val trackTitle = extractText(renderer.opt("title"))
                ?: extractText(renderer.opt("headline"))
                ?: extractText(
                    renderer.optJSONObject("title")
                        ?.optJSONObject("accessibility")
                        ?.optJSONObject("accessibilityData")
                        ?.opt("label")
                )
                ?: return@walk
            if (trackTitle.equals("Private video", true) ||
                trackTitle.equals("Deleted video", true)
            ) return@walk

            tracks += Track(
                id = videoId,
                title = trackTitle,
                artist = extractText(renderer.opt("shortBylineText"))
                    ?: extractText(renderer.opt("longBylineText"))
                    ?: extractText(renderer.opt("ownerText"))
                    ?: "",
                thumbnailUrl = extractThumb(renderer),
                watchUrl = "https://youtube.com/watch?v=$videoId",
                durationSeconds = parseDuration(extractText(renderer.opt("lengthText"))),
                channelId = extractChannelId(renderer),
                playlistItemId = renderer.optString("setVideoId").ifBlank { null }
            )
        }
    }

    private fun extractChannelTitle(json: JSONObject): String? {
        var title: String? = null
        walk(json) { obj ->
            if (title != null) return@walk
            if (obj.has("channelMetadataRenderer")) {
                title = obj.getJSONObject("channelMetadataRenderer")
                    .optString("title").ifBlank { null }
            }
            if (obj.has("pageHeaderViewModel")) {
                title = extractText(
                    obj.optJSONObject("pageHeaderViewModel")
                        ?.optJSONObject("title")
                        ?.optJSONObject("dynamicTextViewModel")
                        ?.opt("text")
                ) ?: title
            }
        }
        return title
    }

    suspend fun addVideoToPlaylist(cookie: String, playlistId: String, videoId: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("playlistId", playlistId)
                .put(
                    "actions",
                    JSONArray().put(
                        JSONObject()
                            .put("action", "ACTION_ADD_VIDEO")
                            .put("addedVideoId", videoId)
                    )
                )
            innertube("browse/edit_playlist", cookie, body)
            videoId
        }

    suspend fun removePlaylistItem(cookie: String, playlistItemId: String) =
        withContext(Dispatchers.IO) {
            // setVideoId is required; playlistId unknown — use ACTION_REMOVE_VIDEO by setVideoId
            val body = JSONObject()
                .put(
                    "actions",
                    JSONArray().put(
                        JSONObject()
                            .put("action", "ACTION_REMOVE_VIDEO")
                            .put("setVideoId", playlistItemId)
                    )
                )
            // playlistId required by API — try with empty and fall back via setVideoId-only clients
            // Most clients send playlistId; caller should pass "id|setVideoId" if needed.
            val parts = playlistItemId.split('|', limit = 2)
            if (parts.size == 2) {
                body.put("playlistId", parts[0])
                body.getJSONArray("actions").getJSONObject(0).put("setVideoId", parts[1])
            }
            innertube("browse/edit_playlist", cookie, body)
        }

    // endregion

    // region Subscriptions

    suspend fun listMySubscriptions(cookie: String): List<Subscription> =
        withContext(Dispatchers.IO) {
            val json = innertube("guide", cookie, JSONObject())
            val found = linkedMapOf<String, Subscription>()
            walk(json) { obj ->
                val browseId = obj.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")
                    ?.ifBlank { null }
                    ?: obj.optJSONObject("browseEndpoint")?.optString("browseId")?.ifBlank { null }
                    ?: return@walk
                if (!browseId.startsWith("UC")) return@walk
                if (browseId in found) return@walk
                val title = extractText(obj.opt("title"))
                    ?: extractText(obj.optJSONObject("formattedTitle"))
                    ?: return@walk
                found[browseId] = Subscription(
                    id = browseId,
                    channelId = browseId,
                    title = title,
                    description = "",
                    thumbnailUrl = extractThumb(obj)
                )
            }
            if (found.isEmpty()) {
                // Fallback: subscriptions feed channels
                val feed = innertube("browse", cookie, JSONObject().put("browseId", "FEsubscriptions"))
                walk(feed) { obj ->
                    val channelId = extractChannelId(obj) ?: return@walk
                    if (channelId in found) return@walk
                    val title = extractText(obj.opt("shortBylineText"))
                        ?: extractText(obj.opt("ownerText"))
                        ?: return@walk
                    found[channelId] = Subscription(
                        id = channelId,
                        channelId = channelId,
                        title = title,
                        description = "",
                        thumbnailUrl = null
                    )
                }
            }
            found.values.sortedBy { it.title.lowercase() }
        }

    suspend fun findSubscriptionId(cookie: String, channelId: String): String? =
        withContext(Dispatchers.IO) {
            listMySubscriptions(cookie).firstOrNull { it.channelId == channelId }?.id
        }

    suspend fun subscribe(cookie: String, channelId: String): String =
        withContext(Dispatchers.IO) {
            innertube(
                "subscription/subscribe",
                cookie,
                JSONObject().put("channelIds", JSONArray().put(channelId))
            )
            channelId
        }

    suspend fun unsubscribe(cookie: String, subscriptionId: String) =
        withContext(Dispatchers.IO) {
            // subscriptionId == channelId in cookie mode
            innertube(
                "subscription/unsubscribe",
                cookie,
                JSONObject().put("channelIds", JSONArray().put(subscriptionId))
            )
        }

    // endregion

    // region Rating

    suspend fun getRating(cookie: String, videoId: String): VideoRating =
        withContext(Dispatchers.IO) {
            val json = innertube("next", cookie, JSONObject().put("videoId", videoId))
            var liked = false
            var disliked = false
            walk(json) { obj ->
                if (obj.has("likeButton") || obj.has("dislikeButton") ||
                    obj.optString("targetId").contains("like", true)
                ) {
                    val toggle = obj.optJSONObject("defaultButton")
                        ?: obj.optJSONObject("toggleButtonRenderer")
                        ?: obj
                    if (toggle.optBoolean("isToggled") ||
                        toggle.optJSONObject("toggleButtonRenderer")?.optBoolean("isToggled") == true
                    ) {
                        val id = (obj.optString("targetId") + obj.toString()).lowercase()
                        when {
                            id.contains("dislike") -> disliked = true
                            id.contains("like") -> liked = true
                        }
                    }
                }
                val tbr = obj.optJSONObject("toggleButtonRenderer")
                if (tbr != null && tbr.optBoolean("isToggled")) {
                    val raw = tbr.toString().lowercase()
                    when {
                        raw.contains("dislike") -> disliked = true
                        raw.contains("\"like\"") || raw.contains("likebutton") -> liked = true
                    }
                }
            }
            when {
                liked -> VideoRating.LIKE
                disliked -> VideoRating.DISLIKE
                else -> VideoRating.NONE
            }
        }

    suspend fun setRating(cookie: String, videoId: String, rating: VideoRating) =
        withContext(Dispatchers.IO) {
            val endpoint = when (rating) {
                VideoRating.LIKE -> "like/like"
                VideoRating.DISLIKE -> "like/dislike"
                VideoRating.NONE, VideoRating.UNSPECIFIED -> "like/removelike"
            }
            innertube(
                endpoint,
                cookie,
                JSONObject().put("target", JSONObject().put("videoId", videoId))
            )
        }

    suspend fun resolveVideoChannelId(cookie: String, videoId: String): String? =
        withContext(Dispatchers.IO) {
            val json = innertube("player", cookie, JSONObject().put("videoId", videoId))
            json.optJSONObject("videoDetails")?.optString("channelId")?.ifBlank { null }
                ?: run {
                    var found: String? = null
                    walk(json) { obj ->
                        if (found != null) return@walk
                        found = extractChannelId(obj)
                    }
                    found
                }
        }

    /**
     * Resolve playable stream + basic metadata via InnerTube (googleapis),
     * avoiding youtube.com HTML which DPI often breaks.
     */
    fun loadPlayableVideo(videoId: String, cookie: String? = null): Pair<Track, ResolvedAudio> {
        val id = videoId.trim().substringAfter("v=").substringBefore("&").substringBefore("?")
        require(id.length == 11) { "Некорректный videoId: $videoId" }

        val payload = JSONObject()
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
                )
            )

        val visitor = fetchVisitorData()
        val attempts = mutableListOf<Pair<ClientType, () -> JSONObject>>()
        attempts += ClientType.VISIONOS to {
            innertubeGuestPlayer(payload, ClientType.VISIONOS, visitor)
        }
        if (!cookie.isNullOrBlank()) {
            attempts += ClientType.VISIONOS to {
                innertube("player", cookie, payload, client = ClientType.VISIONOS)
            }
            attempts += ClientType.IOS to {
                innertube("player", cookie, payload, client = ClientType.IOS)
            }
        }
        attempts += ClientType.IOS to {
            innertubeGuestPlayer(payload, ClientType.IOS, visitor)
        }

        var lastError: Throwable? = null
        for ((client, fetch) in attempts) {
            try {
                val json = fetch()
                val status = json.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                if (status != "OK") {
                    val reason = json.optJSONObject("playabilityStatus")
                        ?.optString("reason")
                        ?.ifBlank { null }
                        ?: status
                    lastError = IllegalStateException("$client: $reason")
                    continue
                }
                val details = json.optJSONObject("videoDetails")
                val resolved = pickStream(json, id, client, hlsOnly = true)
                    ?: pickStream(json, id, client, hlsOnly = false)
                    ?: run {
                        lastError = IllegalStateException("$client: нет аудио URL")
                        null
                    }
                    ?: continue
                val thumbs = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                var thumb: String? = null
                if (thumbs != null) {
                    for (i in 0 until thumbs.length()) {
                        thumb = thumbs.optJSONObject(i)?.optString("url")?.ifBlank { null } ?: thumb
                    }
                }
                val track = Track(
                    id = id,
                    title = details?.optString("title").orEmpty().ifBlank { id },
                    artist = details?.optString("author").orEmpty().ifBlank { "YouTube" },
                    thumbnailUrl = thumb,
                    watchUrl = "https://youtube.com/watch?v=$id",
                    durationSeconds = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L,
                    channelId = details?.optString("channelId")?.ifBlank { null }
                )
                return track to resolved
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException(
            "Не удалось открыть видео $id: ${lastError?.message}",
            lastError
        )
    }

    /**
     * Resolve a playable audio/media URL.
     * Progressive ANDROID googlevideo URLs are PO-token gated (HTTP 403).
     * Prefer VISIONOS HLS (works without PO token), then NewPipe/other clients.
     */
    fun resolveAudioStream(videoId: String, cookie: String? = null): ResolvedAudio {
        val id = videoId.trim().substringAfter("v=").substringBefore("&").substringBefore("?")
        require(id.length == 11) { "Некорректный videoId: $videoId" }

        val payload = JSONObject()
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
                )
            )

        val visitor = fetchVisitorData()
        val attempts = mutableListOf<Pair<ClientType, () -> JSONObject>>()

        // VISIONOS returns HLS without PO-token 403s (as of 2026)
        attempts += ClientType.VISIONOS to {
            innertubeGuestPlayer(payload, ClientType.VISIONOS, visitor)
        }
        if (!cookie.isNullOrBlank()) {
            attempts += ClientType.VISIONOS to {
                innertube("player", cookie, payload, client = ClientType.VISIONOS)
            }
            attempts += ClientType.TVHTML5 to {
                innertube("player", cookie, payload, client = ClientType.TVHTML5)
            }
            attempts += ClientType.IOS to {
                innertube("player", cookie, payload, client = ClientType.IOS)
            }
        }
        attempts += ClientType.IOS to {
            innertubeGuestPlayer(payload, ClientType.IOS, visitor)
        }

        var lastError: Throwable? = null
        for ((client, fetch) in attempts) {
            try {
                val json = fetch()
                val status = json.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                if (status != "OK") {
                    val reason = json.optJSONObject("playabilityStatus")
                        ?.optString("reason")
                        ?.ifBlank { null }
                        ?: status
                    lastError = IllegalStateException("$client: $reason")
                    continue
                }
                // Prefer HLS-only pick to avoid PO-token 403 on progressive
                pickStream(json, id, client, hlsOnly = true)?.let { return it }
                pickStream(json, id, client, hlsOnly = false)?.let { return it }
                lastError = IllegalStateException("$client: нет аудио URL")
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException(
            "Не удалось получить аудиопоток для $id: ${lastError?.message}",
            lastError
        )
    }

    private fun pickStream(
        json: JSONObject,
        videoId: String,
        client: ClientType,
        hlsOnly: Boolean
    ): ResolvedAudio? {
        val hls = json.optJSONObject("streamingData")
            ?.optString("hlsManifestUrl")
            ?.ifBlank { null }
        if (!hls.isNullOrBlank()) {
            return ResolvedAudio(
                trackId = videoId,
                streamUrl = hls,
                mimeType = "application/x-mpegURL",
                expiresAtMs = System.currentTimeMillis() + 5 * 60 * 60 * 1000L,
                userAgent = client.userAgent
            )
        }
        if (hlsOnly) return null

        val streaming = json.optJSONObject("streamingData") ?: return null
        val candidates = mutableListOf<JSONObject>()
        streaming.optJSONArray("adaptiveFormats")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { candidates += it }
            }
        }
        streaming.optJSONArray("formats")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { candidates += it }
            }
        }

        var bestUrl: String? = null
        var bestMime: String? = null
        var bestScore = -1
        for (format in candidates) {
            val url = format.optString("url").ifBlank { null } ?: continue
            // Skip PO-gated progressive from plain ANDROID
            if (client == ClientType.ANDROID) continue
            val mime = format.optString("mimeType")
            val itag = format.optInt("itag", 0)
            val bitrate = format.optInt("bitrate", 0)
                .coerceAtLeast(format.optInt("averageBitrate", 0))
            val score = when {
                mime.startsWith("audio/mp4") -> 2_000_000 + bitrate
                mime.startsWith("audio/") -> 1_500_000 + bitrate
                itag == 18 || mime.startsWith("video/mp4") -> 500_000 + bitrate
                else -> -1
            }
            if (score > bestScore) {
                bestScore = score
                bestUrl = url
                bestMime = mime.substringBefore(';').trim().ifBlank { null }
            }
        }
        val streamUrl = bestUrl ?: return null
        return ResolvedAudio(
            trackId = videoId,
            streamUrl = streamUrl,
            mimeType = bestMime,
            expiresAtMs = System.currentTimeMillis() + 5 * 60 * 60 * 1000L,
            userAgent = client.userAgent
        )
    }

    private fun fetchVisitorData(): String? = runCatching {
        val json = innertubeGuestPlayer(
            JSONObject(),
            ClientType.WEB,
            path = "visitor_id"
        )
        json.optJSONObject("responseContext")?.optString("visitorData")?.ifBlank { null }
            ?: json.optString("visitorData").ifBlank { null }
    }.getOrNull()

    // endregion

    // region InnerTube HTTP

    private fun innertubeGuestPlayer(
        payload: JSONObject,
        client: ClientType,
        visitorData: String? = null,
        path: String = "player"
    ): JSONObject {
        val clientJson = JSONObject()
            .put("hl", "ru")
            .put("gl", "RU")
            .put("clientName", client.clientName)
            .put("clientVersion", client.version)
            .put("userAgent", client.userAgent)
        when (client) {
            ClientType.ANDROID, ClientType.ANDROID_VR ->
                clientJson.put("androidSdkVersion", 34).put("platform", "MOBILE")
            ClientType.IOS -> clientJson
                .put("deviceMake", "Apple")
                .put("deviceModel", "iPhone16,2")
                .put("osName", "iPhone")
                .put("osVersion", "18.7.2.22H124")
                .put("platform", "MOBILE")
            ClientType.VISIONOS -> clientJson
                .put("deviceMake", "Apple")
                .put("deviceModel", "RealityDevice14,1")
                .put("osName", "visionOS")
                .put("osVersion", "25.6.0.23O471")
                .put("platform", "MOBILE")
            else -> Unit
        }
        if (!visitorData.isNullOrBlank()) {
            clientJson.put("visitorData", visitorData)
        }
        val body = JSONObject(payload.toString())
            .put("context", JSONObject().put("client", clientJson))
        val key = when (client) {
            ClientType.ANDROID, ClientType.ANDROID_VR -> ANDROID_API_KEY
            ClientType.IOS, ClientType.VISIONOS -> IOS_API_KEY
            else -> WEB_API_KEY
        }
        val requestBuilder = Request.Builder()
            .url(
                if (key.isBlank()) "$INNERTUBE_BASE/$path?prettyPrint=false"
                else "$INNERTUBE_BASE/$path?key=$key&prettyPrint=false"
            )
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", client.clientNameId)
            .header("X-YouTube-Client-Version", client.version)
            .header("User-Agent", client.userAgent)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .post(body.toString().toRequestBody(jsonMedia))
        if (!visitorData.isNullOrBlank()) {
            requestBuilder.header("X-Goog-Visitor-Id", visitorData)
        }
        return clientHttp(requestBuilder.build())
    }
    private fun innertube(
        path: String,
        cookie: String,
        payload: JSONObject,
        client: ClientType = ClientType.WEB
    ): JSONObject {
        val body = JSONObject(payload.toString()).put("context", clientContext(client))
        val origin = ORIGIN
        val sapisid = extractCookieValue(cookie, "__Secure-3PAPISID")
            ?: extractCookieValue(cookie, "SAPISID")
            ?: throw IllegalStateException("Нет SAPISID в сессии — войдите снова")
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val hash = sha1Hex("$ts $sapisid $origin")
        val key = when (client) {
            ClientType.ANDROID, ClientType.ANDROID_VR -> ANDROID_API_KEY
            ClientType.IOS, ClientType.VISIONOS -> IOS_API_KEY
            else -> WEB_API_KEY
        }

        val request = Request.Builder()
            .url("$INNERTUBE_BASE/$path?key=$key&prettyPrint=false")
            .header("Cookie", cookie)
            .header("Authorization", "SAPISIDHASH ${ts}_$hash")
            .header("X-Origin", origin)
            .header("Origin", origin)
            .header("Referer", "$origin/")
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", client.clientNameId)
            .header("X-YouTube-Client-Version", client.version)
            .header("User-Agent", client.userAgent)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        return clientHttp(request)
    }

    /** Public playlist/channel browse without login cookies. */
    private fun innertubeGuest(path: String, payload: JSONObject): JSONObject {
        val body = JSONObject(payload.toString()).put("context", clientContext(ClientType.WEB))
        val request = Request.Builder()
            .url("$INNERTUBE_BASE/$path?key=$WEB_API_KEY&prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", ClientType.WEB.clientNameId)
            .header("X-YouTube-Client-Version", ClientType.WEB.version)
            .header("User-Agent", ClientType.WEB.userAgent)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return clientHttp(request)
    }

    private fun clientHttp(request: Request): JSONObject {
        http().newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(parseError(response.code, text))
            }
            if (text.isBlank()) return JSONObject()
            return JSONObject(text)
        }
    }

    private fun clientContext(type: ClientType): JSONObject {
        val client = JSONObject()
            .put("hl", "ru")
            .put("gl", "RU")
            .put("clientName", type.clientName)
            .put("clientVersion", type.version)
            .put("userAgent", type.userAgent)
        when (type) {
            ClientType.ANDROID, ClientType.ANDROID_VR ->
                client.put("androidSdkVersion", 34).put("platform", "MOBILE")
            ClientType.IOS -> client
                .put("deviceMake", "Apple")
                .put("deviceModel", "iPhone16,2")
                .put("osName", "iPhone")
                .put("osVersion", "18.7.2.22H124")
                .put("platform", "MOBILE")
            ClientType.VISIONOS -> client
                .put("deviceMake", "Apple")
                .put("deviceModel", "RealityDevice14,1")
                .put("osName", "visionOS")
                .put("osVersion", "25.6.0.23O471")
                .put("platform", "MOBILE")
            else -> Unit
        }
        return JSONObject().put("client", client)
    }

    private enum class ClientType(
        val clientName: String,
        val clientNameId: String,
        val version: String,
        val userAgent: String
    ) {
        WEB(
            clientName = "WEB",
            clientNameId = "1",
            version = CLIENT_VERSION,
            userAgent = USER_AGENT
        ),
        TVHTML5(
            clientName = "TVHTML5",
            clientNameId = "7",
            version = "7.20250319.17.00",
            userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        ),
        VISIONOS(
            clientName = "VISIONOS",
            clientNameId = "101",
            version = "1.02",
            userAgent = "com.google.ios.youtube/1.02 (RealityDevice14,1; U; CPU OS 25_6_0 like Mac OS X;)"
        ),
        IOS(
            clientName = "IOS",
            clientNameId = "5",
            version = "21.03.2",
            userAgent = "com.google.ios.youtube/21.03.2 (iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X;)"
        ),
        ANDROID_VR(
            clientName = "ANDROID_VR",
            clientNameId = "28",
            version = "1.60.19",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 " +
                "(Linux; U; Android 12L; Quest 3) gzip"
        ),
        ANDROID(
            clientName = "ANDROID",
            clientNameId = "3",
            version = "21.03.36",
            userAgent = "com.google.android.youtube/21.03.36 (Linux; U; Android 14) gzip"
        )
    }

    // endregion

    // region JSON helpers

    private fun walk(node: Any?, visitor: (JSONObject) -> Unit) {
        // BFS with a hard cap — YouTube browse JSON is huge; deep recursion crashed the app
        val queue: ArrayDeque<Any?> = ArrayDeque()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_WALK_NODES) {
            when (val current = queue.removeFirst()) {
                is JSONObject -> {
                    visited++
                    visitor(current)
                    val keys = current.keys()
                    while (keys.hasNext()) {
                        queue.add(current.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until current.length()) {
                        queue.add(current.opt(i))
                    }
                }
            }
        }
    }

    private fun extractPlaylistId(obj: JSONObject): String? {
        val direct = obj.optString("playlistId").ifBlank { null }
        if (direct != null && !direct.startsWith("UC")) return direct.removePrefix("VL")

        // contentId directly on lockup / card nodes
        val contentId = obj.optString("contentId").ifBlank { null }
        if (contentId != null && looksLikePlaylistContentId(contentId) && !contentId.startsWith("UU")) {
            return contentId.removePrefix("VL")
        }

        // lockup cards on channel playlists tab / library
        if (obj.has("lockupViewModel")) {
            val lockup = obj.getJSONObject("lockupViewModel")
            val lockupId = lockup.optString("contentId").ifBlank { null }
            if (lockupId != null &&
                looksLikePlaylistContentId(lockupId) &&
                !lockupId.startsWith("UU")
            ) {
                return lockupId.removePrefix("VL")
            }
            val browse = lockup.optJSONObject("rendererContext")
                ?.optJSONObject("commandContext")
                ?.optJSONObject("onTap")
                ?.optJSONObject("innertubeCommand")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
                ?.ifBlank { null }
            if (browse != null && browse.startsWith("VL")) return browse.removePrefix("VL")
            if (browse == "LL" || browse == "WL") return browse
        }
        val browse = obj.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            ?.ifBlank { null }
            ?: obj.optJSONObject("browseEndpoint")?.optString("browseId")?.ifBlank { null }
        if (browse != null && (browse.startsWith("VL") || browse == "LL" || browse == "WL")) {
            return browse.removePrefix("VL")
        }
        return null
    }

    private fun extractChannelId(obj: JSONObject): String? {
        val browse = obj.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            ?.ifBlank { null }
            ?: obj.optJSONObject("browseEndpoint")?.optString("browseId")?.ifBlank { null }
        if (browse != null && browse.startsWith("UC")) return browse
        val cid = obj.optString("channelId").ifBlank { null }
        if (cid != null && cid.startsWith("UC")) return cid
        return null
    }

    private fun extractText(node: Any?): String? {
        when (node) {
            is String -> return node.ifBlank { null }
            is JSONObject -> {
                node.optString("simpleText").ifBlank { null }?.let { return it }
                node.optString("content").ifBlank { null }?.let { return it }
                node.optString("label").ifBlank { null }?.let { return it }
                val runs = node.optJSONArray("runs")
                if (runs != null) {
                    val sb = StringBuilder()
                    for (i in 0 until runs.length()) {
                        sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
                    }
                    sb.toString().ifBlank { null }?.let { return it }
                }
                node.optJSONObject("accessibility")
                    ?.optJSONObject("accessibilityData")
                    ?.optString("label")
                    ?.ifBlank { null }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun extractThumb(obj: JSONObject): String? {
        val thumbs = obj.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: obj.optJSONObject("thumbnails")?.optJSONArray("thumbnails")
            ?: obj.optJSONObject("thumbnailViewModel")
                ?.optJSONObject("image")
                ?.optJSONArray("sources")
            ?: obj.optJSONObject("thumbnailViewModel")
                ?.optJSONArray("sources")
        if (thumbs != null) {
            return thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")?.ifBlank { null }
                ?: thumbs.optJSONObject(0)?.optString("url")?.ifBlank { null }
        }
        // Nested under lockupViewModel.thumbnailViewModel
        val nested = obj.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
        return nested?.optJSONObject(nested.length() - 1)?.optString("url")?.ifBlank { null }
            ?: nested?.optJSONObject(0)?.optString("url")?.ifBlank { null }
    }

    private fun extractPlaylistTitle(json: JSONObject): String? {
        var title: String? = null
        walk(json) { obj ->
            if (title != null) return@walk
            if (obj.has("playlistHeaderRenderer")) {
                title = extractText(obj.optJSONObject("playlistHeaderRenderer")?.opt("title"))
            }
            if (obj.has("pageHeaderRenderer")) {
                title = extractText(
                    obj.optJSONObject("pageHeaderRenderer")
                        ?.optJSONObject("pageTitle")
                ) ?: title
            }
        }
        return title
    }

    private fun findContinuation(json: JSONObject): String? {
        var token: String? = null
        walk(json) { obj ->
            if (token != null) return@walk
            val c = obj.optString("continuation").ifBlank { null }
                ?: obj.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                    ?.ifBlank { null }
                ?: obj.optJSONObject("nextContinuationData")?.optString("continuation")?.ifBlank { null }
            if (!c.isNullOrBlank()) token = c
        }
        return token
    }

    private fun parseDuration(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val parts = text.trim().split(':').mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0L
        }
    }

    private fun extractCookieValue(cookie: String, name: String): String? {
        cookie.split(';').forEach { part ->
            val trimmed = part.trim()
            if (trimmed.startsWith("$name=")) {
                return trimmed.substringAfter('=').takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun sha1Hex(value: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun parseError(code: Int, body: String): String {
        val message = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()?.ifBlank { null }
        return message ?: "YouTube error HTTP $code"
    }

    companion object {
        private const val ORIGIN = "https://www.youtube.com"
        // www.youtube.com SNI is DPI-blocked on many RU ISPs; googleapis endpoint is not.
        private const val INNERTUBE_BASE = "https://youtubei.googleapis.com/youtubei/v1"
        private const val CLIENT_VERSION = "2.20260120.01.00"
        private const val WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val ANDROID_API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vzqGEJrZs"
        private const val IOS_API_KEY = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcz31HiqU"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private const val MAX_WALK_NODES = 25_000
    }

    // endregion
}
