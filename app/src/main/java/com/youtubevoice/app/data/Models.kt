package com.youtubevoice.app.data

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val watchUrl: String,
    val durationSeconds: Long = 0L,
    val channelId: String? = null,
    /** playlistItems.id — нужен, чтобы удалить трек из плейлиста */
    val playlistItemId: String? = null
)

data class PlaylistInfo(
    val id: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val tracks: List<Track>
)

data class ResolvedAudio(
    val trackId: String,
    val streamUrl: String,
    val mimeType: String?,
    val expiresAtMs: Long,
    /** User-Agent that must be used when downloading this googlevideo URL */
    val userAgent: String? = null
)

data class LibraryPlaylist(
    val id: String,
    val title: String,
    val itemCount: Int,
    val thumbnailUrl: String?,
    val url: String? = null
)

data class Subscription(
    val id: String,
    val channelId: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String?
)

data class ChannelPage(
    val channelId: String,
    val title: String,
    val handle: String? = null,
    val thumbnailUrl: String? = null,
    val description: String = "",
    val tab: ChannelBrowseTab = ChannelBrowseTab.PLAYLISTS,
    val playlists: List<LibraryPlaylist> = emptyList(),
    val videos: List<Track> = emptyList()
)

sealed class SearchHit {
    abstract val id: String
    abstract val title: String
    abstract val subtitle: String
    abstract val thumbnailUrl: String?

    data class Channel(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        override val thumbnailUrl: String?,
        val channelId: String,
        val handle: String? = null
    ) : SearchHit()

    data class Playlist(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        override val thumbnailUrl: String?,
        val playlistId: String
    ) : SearchHit()

    data class Video(
        override val id: String,
        override val title: String,
        override val subtitle: String,
        override val thumbnailUrl: String?,
        val watchUrl: String,
        val channelId: String? = null
    ) : SearchHit()
}

enum class VideoRating {
    LIKE,
    DISLIKE,
    NONE,
    UNSPECIFIED;

    companion object {
        fun fromApi(value: String?): VideoRating = when (value?.lowercase()) {
            "like" -> LIKE
            "dislike" -> DISLIKE
            "none" -> NONE
            else -> UNSPECIFIED
        }

        fun toApi(rating: VideoRating): String = when (rating) {
            LIKE -> "like"
            DISLIKE -> "dislike"
            NONE, UNSPECIFIED -> "none"
        }
    }
}

enum class LibraryTab {
    PLAYLISTS,
    SUBSCRIPTIONS
}

enum class ChannelBrowseTab {
    PLAYLISTS,
    VIDEOS
}

enum class MainTab {
    PLAYER,
    SEARCH,
    LIBRARY
}
