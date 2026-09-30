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
- `PlayerViewModel` — навигация, auth, очередь, MediaController

### Auth (`auth/`)

- Выбор аккаунта через `AccountManager`
- `YoutubeLoginActivity` — WebView, cookie-сессия
- `YoutubeAccountAuth` — DataStore (`SAPISID` / `__Secure-3PAPISID`)

### Данные (`youtube/`)

| Компонент | Роль |
|---|---|
| `YoutubeLibraryApi` | InnerTube: библиотека, `/next`, player / HLS, поиск, подписки |
| `YoutubeRepository` | NewPipe + fallback на InnerTube |
| `NewPipeDownloader` | HTTP для NewPipe Extractor |

Резолв аудио: предпочтительно HLS; User-Agent потока должен совпадать с клиентом, выдавшим URL.

### Плеер (`player/`)

- `PlaybackService` — MediaSessionService, ExoPlayer на главном потоке
- `AudioCacheStore` — кэш Media3

## Сценарии

**Ссылка:** intent / поле ввода → `loadFromUrl` → очередь ExoPlayer → `resolveAudio`.

**Библиотека:** cookie → `listMyPlaylists` → `loadPlaylistTracks` → очередь.

**Поиск / канал:** NewPipe (+ InnerTube при необходимости).

## Почему без YouTube Data API v3

Вход упрощён до WebView + cookie, без Google Cloud OAuth.  
Метаданные и потоки — InnerTube / NewPipe: удобно для личного проекта, но чувствительно к изменениям на стороне сервиса.

## Зависимости

См. `gradle/libs.versions.toml`: Media3 1.5.x, NewPipeExtractor v0.26.5, Compose BOM 2024.12, OkHttp, Coil, DataStore.
