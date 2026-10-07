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

- `PlaybackService` — MediaSessionService, ExoPlayer, сеть; при скипе `rotateCacheForCenter` (окно, не полный wipe); offline-first play + prefetch fill
- `OfflinePlayback` — локальный m3u8 / `ytvcache://` из уже скачанных сегментов (без сети для старта)
- `TrackCacheProgress` — полнота по `durationSeconds` (ожидаемые gosq), contiguous ahead / diskUntil
- `StreamUrlStore` — сохранённый HLS URL для докачки (не обязателен для старта, если есть байты на диске)
- `CacheSettingsStore` — лимит МБ, ahead/behind, порядок prefetch, режим вытеснения
- `AudioCacheStore` / `AudioCacheKeys` — SimpleCache; стабильные ключи `vp|itag|g{gosq}|…`; `dedupeTrackSegments`; `retainOnly` по окну
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

**Кэш / офлайн:** старт с диска, если есть сегменты (`OfflinePlayback`). URL нужен, чтобы докачать окно и следующих в prefetch. При смене трека кэш не очищается целиком: окно → `retainOnly` → prefetch. Текущий трек заполняется от позиции вперёд до полноты по длительности; следующие в окне — после запаса ~90 с на текущем, по одному. Дубли CDN (один gosq, разные хосты) снимаются при warm и перед сборкой m3u8. Неполный офлайн-плейлист без `#EXT-X-ENDLIST`; `pauseAtEndOfMediaItems` — не автоскип на конец отрывка. Режимы: WINDOW / LRU / WINDOW_AND_LRU. Парсер m3u8 не считает media-сегменты (`videoplayback`) вложенными плейлистами.

**DPI:** выкл. по умолчанию — совместим с VPN телефона. Вкл. — локальный TUN только для этого пакета (вытесняет системный VPN).

## Сеть / DPI

Встроенный локальный DPI с тумблером в настройках. См. [NETWORK.md](NETWORK.md).

## Почему без YouTube Data API v3

Вход упрощён до WebView + cookie, без Google Cloud OAuth.  
Метаданные и потоки — InnerTube / NewPipe: удобно для личного проекта, но чувствительно к изменениям на стороне сервиса.

## Зависимости

См. `gradle/libs.versions.toml`: Media3 1.5.x, NewPipeExtractor v0.26.5, Compose BOM 2024.12, OkHttp, Coil, DataStore.
