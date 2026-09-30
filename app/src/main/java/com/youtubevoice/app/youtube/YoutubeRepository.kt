package com.youtubevoice.app.youtube

import com.youtubevoice.app.data.ChannelBrowseTab
import com.youtubevoice.app.data.ChannelPage
import com.youtubevoice.app.data.LibraryPlaylist
import com.youtubevoice.app.data.PlaylistInfo
import com.youtubevoice.app.data.ResolvedAudio
import com.youtubevoice.app.data.SearchHit
import com.youtubevoice.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap

class YoutubeRepository(
    private val libraryApi: YoutubeLibraryApi = YoutubeLibraryApi()
) {
    private val audioCache = ConcurrentHashMap<String, ResolvedAudio>()

    suspend fun loadFromUrl(url: String): PlaylistInfo = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(url)
        when {
            isChannelUrl(normalized) -> error("CHANNEL:$normalized")
            isPlaylistUrl(normalized) -> loadPlaylist(normalized)
            else -> loadSingleVideo(normalized)
        }
    }

    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        require(q.isNotEmpty()) { "Введите запрос" }
        val extractor = ServiceList.YouTube.getSearchExtractor(q)
        extractor.fetchPage()
        val hits = mutableListOf<SearchHit>()
        var page = extractor.initialPage
        var pages = 0
        while (pages < 3 && hits.size < 40) {
            page.items.forEach { item ->
                item.toSearchHit()?.let { hits += it }
            }
            val next = page.nextPage
            if (!Page.isValid(next)) break
            page = extractor.getPage(next)
            pages++
        }
        hits
    }

    suspend fun loadChannelPage(
        channelRef: String,
        tab: ChannelBrowseTab = ChannelBrowseTab.PLAYLISTS
    ): ChannelPage = withContext(Dispatchers.IO) {
        val channelUrl = resolveChannelUrl(channelRef)
        val channelExtractor = ServiceList.YouTube.getChannelExtractor(channelUrl)
        channelExtractor.fetchPage()
        val channelId = channelExtractor.id?.takeIf { it.startsWith("UC") }
            ?: channelIdFromUploaderUrl(channelExtractor.url)
            ?: channelIdFromUploaderUrl(channelUrl)
            ?: throw IllegalStateException("Не удалось определить канал")

        val handle = channelExtractor.url
            ?.substringAfter("/@", "")
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { "@$it" }

        val base = ChannelPage(
            channelId = channelId,
            title = channelExtractor.name.orEmpty().ifBlank { "Канал" },
            handle = handle,
            thumbnailUrl = channelExtractor.avatars.maxByOrNull { it.height }?.url,
            description = channelExtractor.description.orEmpty(),
            tab = tab
        )

        when (tab) {
            ChannelBrowseTab.PLAYLISTS -> base.copy(
                playlists = loadChannelTabPlaylists(channelExtractor)
            )
            ChannelBrowseTab.VIDEOS -> {
                val uploadsId = "UU" + channelId.removePrefix("UC")
                val videos = runCatching {
                    loadPlaylist("https://www.youtube.com/playlist?list=$uploadsId").tracks
                }.getOrElse { loadChannelViaTabs(channelId).tracks }
                base.copy(videos = videos.map { t ->
                    if (t.channelId == null) t.copy(channelId = channelId) else t
                })
            }
        }
    }

    suspend fun loadChannel(channelId: String): PlaylistInfo = withContext(Dispatchers.IO) {
        val id = channelId.trim()
        require(id.startsWith("UC")) { "Некорректный id канала" }
        val uploadsId = "UU" + id.removePrefix("UC")
        val uploadsUrl = "https://www.youtube.com/playlist?list=$uploadsId"
        runCatching { loadPlaylist(uploadsUrl) }
            .map { it.copy(id = id, uploader = it.uploader.ifBlank { it.title }) }
            .getOrElse { loadChannelViaTabs(id) }
    }

    private fun loadChannelTabPlaylists(
        channelExtractor: org.schabi.newpipe.extractor.channel.ChannelExtractor
    ): List<LibraryPlaylist> {
        val playlistsTab = channelExtractor.tabs.firstOrNull { tab ->
            val filters = tab.contentFilters.joinToString(",").lowercase()
            tab.url.contains("/playlists", ignoreCase = true) || filters.contains("playlist")
        } ?: return emptyList()

        val tabExtractor = ServiceList.YouTube.getChannelTabExtractor(playlistsTab)
        tabExtractor.fetchPage()
        val playlists = linkedMapOf<String, LibraryPlaylist>()
        var page = tabExtractor.initialPage
        var pages = 0
        while (pages < 8) {
            page.items.forEach { item ->
                if (item is PlaylistInfoItem) {
                    val id = extractPlaylistIdFromUrl(item.url) ?: return@forEach
                    if (id.isBlank() || id in playlists) return@forEach
                    playlists[id] = LibraryPlaylist(
                        id = id,
                        title = item.name.orEmpty().ifBlank { id },
                        itemCount = item.streamCount.coerceAtLeast(0L).toInt().coerceAtLeast(0),
                        thumbnailUrl = item.thumbnails.maxByOrNull { it.height }?.url,
                        url = "https://www.youtube.com/playlist?list=$id"
                    )
                }
            }
            val next = page.nextPage
            if (!Page.isValid(next)) break
            page = tabExtractor.getPage(next)
            pages++
        }
        return playlists.values.toList()
    }

    private fun loadChannelViaTabs(id: String): PlaylistInfo {
        val channelUrl = "https://www.youtube.com/channel/$id"
        val channelExtractor = ServiceList.YouTube.getChannelExtractor(channelUrl)
        channelExtractor.fetchPage()
        val videosTab = channelExtractor.tabs.firstOrNull { tab ->
            val filters = tab.contentFilters.joinToString(",").lowercase()
            tab.url.contains("/videos", ignoreCase = true) ||
                filters.contains("videos") || filters.contains("video")
        } ?: channelExtractor.tabs.firstOrNull { tab ->
            !tab.url.contains("/shorts", ignoreCase = true) &&
                !tab.url.contains("/streams", ignoreCase = true) &&
                !tab.url.contains("/playlists", ignoreCase = true)
        } ?: channelExtractor.tabs.firstOrNull()
            ?: throw IllegalStateException("Не удалось открыть вкладку видео канала")

        val tabExtractor = ServiceList.YouTube.getChannelTabExtractor(videosTab)
        tabExtractor.fetchPage()
        val tracks = mutableListOf<Track>()
        var page = tabExtractor.initialPage
        var pages = 0
        while (pages < 10) {
            page.items.forEach { item ->
                if (item is StreamInfoItem) {
                    tracks += item.toTrack().let { t ->
                        if (t.channelId == null) t.copy(channelId = id) else t
                    }
                }
            }
            val next = page.nextPage
            if (!Page.isValid(next) || tracks.size >= 200) break
            page = tabExtractor.getPage(next)
            pages++
        }
        if (tracks.isEmpty()) throw IllegalStateException("На канале не найдено видео")
        return PlaylistInfo(
            id = id,
            title = channelExtractor.name.orEmpty().ifBlank { "Канал" },
            uploader = channelExtractor.name.orEmpty(),
            thumbnailUrl = channelExtractor.avatars.maxByOrNull { it.height }?.url
                ?: tracks.firstOrNull()?.thumbnailUrl,
            tracks = tracks
        )
    }

    suspend fun resolveAudio(trackId: String, watchUrl: String): ResolvedAudio =
        withContext(Dispatchers.IO) {
            val cached = audioCache[trackId]
            if (cached != null && cached.expiresAtMs > System.currentTimeMillis() + 60_000) {
                val ua = cached.userAgent.orEmpty()
                // Plain ANDROID progressive URLs are PO-token gated (HTTP 403) — don't reuse
                val brokenAndroid = ua.contains("com.google.android.youtube/") &&
                    !ua.contains("youtube.vr")
                if (!brokenAndroid) return@withContext cached
            }
            val videoId = trackId.takeIf { it.length == 11 }
                ?: extractVideoId(watchUrl)
                ?: trackId

            val resolved = runCatching {
                libraryApi.resolveAudioStream(videoId, NewPipeDownloader.cookieOrNull())
            }.recoverCatching { innerError ->
                android.util.Log.w(
                    "YoutubeVoice",
                    "InnerTube resolve failed for $videoId, trying NewPipe",
                    innerError
                )
                org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
                    .setFetchIosClient(true)
                val extractor = ServiceList.YouTube.getStreamExtractor(watchUrl)
                extractor.fetchPage()
                val hls = runCatching { extractor.hlsUrl }.getOrNull()?.takeIf { it.isNotBlank() }
                if (!hls.isNullOrBlank()) {
                    return@recoverCatching ResolvedAudio(
                        trackId = videoId,
                        streamUrl = hls,
                        mimeType = "application/x-mpegURL",
                        expiresAtMs = System.currentTimeMillis() + 4 * 60 * 60 * 1000L,
                        userAgent = "com.google.ios.youtube/21.03.2 (iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X;)"
                    )
                }
                val hlsAudio = extractor.audioStreams.firstOrNull {
                    it.deliveryMethod == DeliveryMethod.HLS
                }
                if (hlsAudio != null) {
                    return@recoverCatching ResolvedAudio(
                        trackId = videoId,
                        streamUrl = hlsAudio.content,
                        mimeType = hlsAudio.format?.mimeType ?: "application/x-mpegURL",
                        expiresAtMs = System.currentTimeMillis() + 4 * 60 * 60 * 1000L,
                        userAgent = "com.google.ios.youtube/21.03.2 (iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X;)"
                    )
                }
                val stream = pickBestAudio(extractor.audioStreams)
                    ?: error("Аудиопоток не найден для: ${extractor.name}")
                ResolvedAudio(
                    trackId = videoId,
                    streamUrl = stream.content,
                    mimeType = stream.format?.mimeType,
                    expiresAtMs = System.currentTimeMillis() + 4 * 60 * 60 * 1000L
                )
            }.getOrElse { error ->
                throw IllegalStateException(
                    "Не удалось получить аудио: ${error.message}",
                    error
                )
            }

            audioCache[videoId] = resolved
            audioCache[trackId] = resolved
            resolved
        }

    fun invalidate(trackId: String) {
        audioCache.remove(trackId)
    }

    fun isChannelUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/@") ||
            lower.contains("youtube.com/channel/") ||
            lower.contains("youtube.com/c/") ||
            lower.contains("youtube.com/user/")
    }

    private fun resolveChannelUrl(channelRef: String): String {
        val raw = channelRef.trim()
        return when {
            raw.startsWith("UC") && !raw.contains("/") -> "https://www.youtube.com/channel/$raw"
            raw.startsWith("@") -> "https://www.youtube.com/$raw"
            raw.startsWith("http") -> raw
                .substringBefore("/playlists")
                .substringBefore("/videos")
                .substringBefore("/streams")
                .substringBefore("/featured")
                .trimEnd('/')
            raw.startsWith("www.") -> resolveChannelUrl("https://$raw")
            else -> "https://www.youtube.com/$raw"
        }
    }

    suspend fun loadPlaylistById(playlistId: String, maxTracks: Int = 40): PlaylistInfo =
        withContext(Dispatchers.IO) {
            val id = playlistId.trim().removePrefix("VL")
            require(id.isNotBlank()) { "Пустой id плейлиста" }
            val url = "https://www.youtube.com/playlist?list=$id"
            val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
            extractor.fetchPage()
            val tracks = mutableListOf<Track>()
            var page = extractor.initialPage
            var pages = 0
            while (pages < 8 && tracks.size < maxTracks) {
                page.items.forEach { item ->
                    if (tracks.size >= maxTracks) return@forEach
                    if (item is StreamInfoItem) tracks += item.toTrack()
                }
                val next = page.nextPage
                if (!Page.isValid(next) || tracks.size >= maxTracks) break
                page = extractor.getPage(next)
                pages++
            }
            PlaylistInfo(
                id = extractor.id.orEmpty().ifBlank { id },
                title = extractor.name.orEmpty().ifBlank { "Плейлист" },
                uploader = extractor.uploaderName.orEmpty(),
                thumbnailUrl = extractor.thumbnails.maxByOrNull { it.height }?.url,
                tracks = tracks
            )
        }

    private fun loadPlaylist(url: String): PlaylistInfo {
        val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
        extractor.fetchPage()
        val tracks = mutableListOf<Track>()
        var page = extractor.initialPage
        var pages = 0
        while (pages < 8) {
            page.items.forEach { item ->
                if (item is StreamInfoItem) tracks += item.toTrack()
            }
            val next = page.nextPage
            if (!Page.isValid(next) || tracks.size >= 40) break
            page = extractor.getPage(next)
            pages++
        }
        return PlaylistInfo(
            id = extractor.id.orEmpty().ifBlank {
                extractPlaylistIdFromUrl(url).orEmpty()
            },
            title = extractor.name.orEmpty().ifBlank { "Плейлист" },
            uploader = extractor.uploaderName.orEmpty(),
            thumbnailUrl = extractor.thumbnails.maxByOrNull { it.height }?.url,
            tracks = tracks
        )
    }

    private fun extractPlaylistIdFromUrl(url: String): String? {
        val fromQuery = url.substringAfter("list=", "")
            .substringBefore('&')
            .substringBefore('#')
            .removePrefix("VL")
        if (fromQuery.isNotBlank()) return fromQuery
        val fromPath = url.substringAfter("/playlist/", "")
            .substringBefore('?')
            .substringBefore('/')
            .removePrefix("VL")
        return fromPath.ifBlank { null }
    }

    private fun loadSingleVideo(url: String): PlaylistInfo {
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        val track = Track(
            id = extractor.id,
            title = extractor.name.orEmpty(),
            artist = extractor.uploaderName.orEmpty(),
            thumbnailUrl = extractor.thumbnails.maxByOrNull { it.height }?.url,
            watchUrl = extractor.url,
            durationSeconds = extractor.length,
            channelId = channelIdFromUploaderUrl(extractor.uploaderUrl)
        )
        pickBestAudio(extractor.audioStreams)?.let { stream ->
            audioCache[track.id] = ResolvedAudio(
                trackId = track.id,
                streamUrl = stream.content,
                mimeType = stream.format?.mimeType,
                expiresAtMs = System.currentTimeMillis() + 4 * 60 * 60 * 1000L
            )
        }
        return PlaylistInfo(
            id = track.id,
            title = track.title,
            uploader = track.artist,
            thumbnailUrl = track.thumbnailUrl,
            tracks = listOf(track)
        )
    }

    private fun pickBestAudio(streams: List<AudioStream>): AudioStream? {
        if (streams.isEmpty()) return null
        val progressive = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val pool = progressive.ifEmpty { streams }
        return pool.sortedWith(
            compareByDescending<AudioStream> { it.averageBitrate }.thenByDescending { it.bitrate }
        ).firstOrNull()
    }

    private fun InfoItem.toSearchHit(): SearchHit? = when (this) {
        is ChannelInfoItem -> {
            val channelId = channelIdFromUploaderUrl(url)
                ?: url.substringAfter("/channel/", "").substringBefore('/').takeIf { it.startsWith("UC") }
                ?: url
            SearchHit.Channel(
                id = "ch_$channelId",
                title = name.orEmpty(),
                subtitle = url.substringAfter("youtube.com/").take(48).ifBlank { "Канал" },
                thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
                channelId = channelId,
                handle = url.substringAfter("/@", "").substringBefore('/').takeIf { it.isNotBlank() }
                    ?.let { "@$it" }
            )
        }
        is PlaylistInfoItem -> {
            val playlistId = url.substringAfter("list=", "").substringBefore("&")
            if (playlistId.isBlank()) null else SearchHit.Playlist(
                id = "pl_$playlistId",
                title = name.orEmpty(),
                subtitle = uploaderName.orEmpty().ifBlank { "Плейлист" },
                thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
                playlistId = playlistId
            )
        }
        is StreamInfoItem -> {
            val videoId = extractVideoId(url) ?: return null
            SearchHit.Video(
                id = "v_$videoId",
                title = name.orEmpty(),
                subtitle = uploaderName.orEmpty(),
                thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
                watchUrl = "https://www.youtube.com/watch?v=$videoId",
                channelId = channelIdFromUploaderUrl(uploaderUrl)
            )
        }
        else -> null
    }

    private fun StreamInfoItem.toTrack(): Track {
        val videoId = extractVideoId(url) ?: url
        return Track(
            id = videoId,
            title = name.orEmpty(),
            artist = uploaderName.orEmpty(),
            thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
            watchUrl = if (videoId.startsWith("http")) url else "https://www.youtube.com/watch?v=$videoId",
            durationSeconds = duration,
            channelId = channelIdFromUploaderUrl(uploaderUrl)
        )
    }

    private fun extractVideoId(url: String): String? {
        val watch = url.substringAfter("watch?v=", "").substringBefore("&").substringBefore("#")
        if (watch.length == 11) return watch
        val shorts = url.substringAfter("/shorts/", "").substringBefore('/').substringBefore('?')
        if (shorts.length == 11) return shorts
        val embed = url.substringAfter("/embed/", "").substringBefore('/').substringBefore('?')
        if (embed.length == 11) return embed
        val shortLink = url.substringAfter("youtu.be/", "").substringBefore('?').substringBefore('/')
        if (shortLink.length == 11) return shortLink
        return null
    }

    private fun channelIdFromUploaderUrl(uploaderUrl: String?): String? {
        if (uploaderUrl.isNullOrBlank()) return null
        val channel = uploaderUrl.substringAfter("/channel/", missingDelimiterValue = "")
            .substringBefore('/').substringBefore('?')
        return channel.takeIf { it.startsWith("UC") }
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("www.") -> "https://$trimmed"
            trimmed.startsWith("@") -> "https://www.youtube.com/$trimmed"
            trimmed.startsWith("youtu.be/") || trimmed.startsWith("youtube.com/") -> "https://$trimmed"
            else -> trimmed
        }
    }

    private fun isPlaylistUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("list=") || lower.contains("/playlist?")
    }
}

