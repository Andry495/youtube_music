# Архитектура YouTube Voice

Техническое описание Android YouTube audio player: Compose UI, Media3, InnerTube, NewPipe.

## Обзор

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────────┐
│ PlayerScreen│────▶│ PlayerViewModel  │────▶│ YoutubeRepository   │
│  (Compose)  │◀────│                  │◀────│ + YoutubeLibraryApi │
└──────┬──────┘     └────────┬─────────┘     └──────────┬──────────┘
       │                     │                          │
       │              MediaController            InnerTube / NewPipe
       ▼                     ▼                          ▼
┌─────────────┐     ┌──────────────────┐     ┌─────────────────────┐
│  UI / tabs  │     │ PlaybackService  │     │ audio stream / HLS  │
└─────────────┘     │ ExoPlayer+Session│     └─────────────────────┘
                    └──────────────────┘
```

## Слои

### UI (`ui/`)

- `PlayerScreen` — вкладки Player / Search / Library, очередь, now playing
- `PlayerViewModel` — навигация, auth, очередь, MediaController, сохранение/восстановление сессии

### Auth (`auth/`)

- Выбор аккаунта через `AccountManager`
- `YoutubeLoginActivity` — WebView, cookie-сессия
- `YoutubeAccountAuth` — DataStore (`SAPISID` / `__Secure-3PAPISID`)

### Плеер (`player/`)

- `PlaybackService` — MediaSessionService, ExoPlayer, сеть; при скипе `rotateCacheForCenter` (окно, не полный wipe)
- `CacheSettingsStore` — лимит МБ, ahead/behind, порядок prefetch, режим вытеснения
- `AudioCacheStore` / `AudioCacheKeys` — SimpleCache; ключи `{trackId}|…`; `retainOnly` по окну (кроме режима LRU)
- `PlaybackStateStore` — DataStore: очередь, индекс трека, позиция, флаг «играло»
- `DpiVpnService` — опциональный TUN → hev → ByeDPI (`addAllowedApplication` только свой пакет); по умолчанию выкл.
- `DpiProxyService` — запасной SOCKS ByeDPI без TUN

### Данные (`youtube/`)

| Компонент | Роль |
|---|---|
| `YoutubeLibraryApi` | InnerTube: библиотека, `/next`, player / HLS, поиск, подписки |
| `YoutubeRepository` | NewPipe + fallback на InnerTube |
| `NewPipeDownloader` | HTTP для NewPipe Extractor |

Резолв аудио: предпочтительно HLS; User-Agent потока должен совпадать с клиентом, выдавшим URL.

## Сценарии

**Ссылка:** intent / поле ввода → `loadFromUrl` → очередь ExoPlayer → `resolveAudio`.

**Библиотека:** cookie → `listMyPlaylists` → `loadPlaylistTracks` → очередь.

**Поиск / канал:** NewPipe (+ InnerTube при необходимости).

**Смена сети:** `ConnectivityManager` → invalidate URL cache → re-resolve → `seekTo` сохранённой позиции.

**Перезапуск приложения:** `PlaybackStateStore` → `setPlaylist(..., startPositionMs)` без потери трека.

**Кэш:** при смене трека не очищается целиком. Считается окно (текущий ± ahead/behind по политике), `retainOnly` удаляет чужие track id, затем prefetch добирает дырки. Режимы: WINDOW / LRU / WINDOW_AND_LRU. Текущий HLS пишет ExoPlayer; отдельный CacheWriter на играющий элемент не запускается (OOM на длинных плейлистах). Парсер m3u8 не считает media-сегменты (`videoplayback`, query `hls_playlist`) вложенными плейлистами.

**DPI:** выкл. по умолчанию — совместим с VPN телефона. Вкл. — локальный TUN только для этого пакета (вытесняет системный VPN).

## Сеть / DPI

Встроенный локальный DPI с тумблером в настройках. См. [NETWORK.md](NETWORK.md).

## Почему без YouTube Data API v3

Вход упрощён до WebView + cookie, без Google Cloud OAuth.  
Метаданные и потоки — InnerTube / NewPipe: удобно для личного проекта, но чувствительно к изменениям на стороне сервиса.

## Зависимости

См. `gradle/libs.versions.toml`: Media3 1.5.x, NewPipeExtractor v0.26.5, Compose BOM 2024.12, OkHttp, Coil, DataStore.
