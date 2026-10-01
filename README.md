# YouTube Voice — Android YouTube Audio Player

**YouTube Voice** — открытый Android-аудиоплеер для YouTube и YouTube Music: только звук, фон, уведомления, поиск, библиотека и плейлисты.

Текущая версия: **1.1.1** (`versionCode` 3)

Ключевые слова: `youtube audio player`, `android youtube music`, `background youtube audio`, `youtube playlist player`, `kotlin compose exoplayer`, `media3`, `newpipe`, `playback resume`, `network recovery`.

> Исходники и APK — только на GitHub. В Google Play / RuStore **не публикуется**.

**Репозиторий:** [github.com/Andry495/youtube_music](https://github.com/Andry495/youtube_music)

---

## Что это

Лёгкий фоновый плеер под Android 8+: вставляете ссылку на ролик или плейлист — играет **аудио** без видео. Управление из шторки и с экрана блокировки. После входа доступны свои плейлисты и подписки.

Подходит тем, кто ищет:

- YouTube / YouTube Music **только звук** на телефоне
- воспроизведение плейлистов **в фоне**
- открытый код на **Kotlin + Jetpack Compose + Media3**

---

## Возможности

- Ссылки на видео, плейлист, канал (YouTube / YouTube Music)
- Deep links: `youtube.com`, `youtu.be`, `music.youtube.com`
- Только аудио (без видеоплеера на экране)
- Фон: Foreground Service + Media3 MediaSession
- Вкладки: **Плеер** · **Поиск** · **Библиотека**
- Поиск видео, плейлистов и каналов
- Канал: плейлисты и видео
- Вход через WebView (cookie) — без Google Cloud OAuth
- Свои плейлисты, подписки, лайк / дизлайк
- Очередь, shuffle / repeat
- Кэш аудиопотоков (Media3)
- **Возобновление после смены сети** (Wi‑Fi ↔ мобильный интернет)
- **Память позиции**: после закрытия приложения продолжает с того же трека и времени

---

## Скачать APK

| Файл | Версия |
|---|---|
| [YouTubeVoice-1.1.1-debug.apk](releases/YouTubeVoice-1.1.1-debug.apk) | **1.1.1** (debug) |
| [YouTubeVoice-1.1.0-debug.apk](releases/YouTubeVoice-1.1.0-debug.apk) | 1.1.0 (debug, архив) |
| [YouTubeVoice-1.0.0-debug.apk](releases/YouTubeVoice-1.0.0-debug.apk) | 1.0.0 (debug, архив) |

Прямая ссылка (актуальная):  
https://github.com/Andry495/youtube_music/raw/main/releases/YouTubeVoice-1.1.1-debug.apk

На Android 8+ разрешите установку из неизвестных источников. Это сборка из исходников, не магазинный релиз.

История изменений: [CHANGELOG.md](CHANGELOG.md)

---

## Стек

| Слой | Технологии |
|---|---|
| UI | Kotlin, Jetpack Compose, Material 3 |
| Плеер | Media3 ExoPlayer, HLS, MediaSessionService |
| Сеть | OkHttp, DataStore, ConnectivityManager |
| Контент | InnerTube (cookie), [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) |
| Картинки | Coil |

**Требования:** Android 8.0+ (API 26), интернет, уведомления (Android 13+).

---

## Быстрый старт

```bash
git clone https://github.com/Andry495/youtube_music.git
cd youtube_music
```

Android Studio: `File → Open` → корень проекта → Run `app`.

CLI (Windows):

```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:installDebug
```

Подробнее: [docs/BUILD.md](docs/BUILD.md) · архитектура: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

---

## Авторизация

Google Cloud Console и OAuth client **не нужны**.

1. «Войти» в приложении  
2. При необходимости выбрать Google-аккаунт на устройстве  
3. Войти на YouTube в WebView  
4. Cookie-сессия сохраняется локально (библиотека, подписки, оценки)

Без входа доступны поиск и публичный контент. При выходе сессия очищается.

---

## Структура проекта

```
app/src/main/java/com/youtubevoice/app/
├── MainActivity.kt
├── YoutubeVoiceApp.kt
├── auth/          # WebView-вход, cookie / DataStore
├── data/          # модели Track, Playlist, SearchHit
├── player/        # ExoPlayer, MediaSession, кэш, resume state
├── ui/            # Compose UI + ViewModel
└── youtube/       # InnerTube + NewPipe
```

---

## О проекте

Учебный / персональный open-source проект. **Не** официальный продукт YouTube или Google.

- Распространение: GitHub (исходники + APK)
- Публикация в магазинах приложений **не планируется**
- Товарные знаки YouTube / YouTube Music принадлежат правообладателям

Подробнее: [docs/DISCLAIMER.md](docs/DISCLAIMER.md)

---

## Ограничения

- Форматы и клиенты потоков могут меняться — нужна поддержка резолвера
- Часть роликов (регион, возраст, Premium) может быть недоступна
- Нет официального «audio-only» API; используется сторонний резолв (в т.ч. HLS)

---

## Topics / keywords

`android` · `kotlin` · `jetpack-compose` · `material3` · `exoplayer` · `media3` · `youtube` · `youtube-music` · `audio-player` · `background-playback` · `playlist` · `newpipe` · `innertube` · `open-source`

---

## Лицензия

Код — [MIT License](LICENSE).  
Лицензия кода не передаёт права на товарные знаки YouTube / Google.

---

## Участие

Issues и PR по сборке, документации и стабильности воспроизведения приветствуются.  
Крупные изменения лучше сначала описать в issue.
