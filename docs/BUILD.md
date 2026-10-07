# Сборка YouTube Voice (Android)

Как собрать Android YouTube audio player из исходников: Gradle, JDK 17, APK.

## Требования

- JDK **17**
- Android SDK (compile/target SDK **35**)
- Android Studio с плагином Android Gradle Plugin **8.7+**
- Устройство или эмулятор: **minSdk 26** (Android 8.0+)
- Интернет на устройстве

## Открытие в Android Studio

1. Клонируйте репозиторий:

```bash
git clone https://github.com/Andry495/youtube_music.git
cd youtube_music
```

2. `File → Open` → корень проекта (`settings.gradle.kts`).
3. Дождитесь Gradle Sync.
4. При запросе SDK укажите путь или создайте `local.properties` (см. ниже).

## local.properties

Файл **не** коммитится. Пример: [`local.properties.example`](../local.properties.example).

Windows:

```properties
sdk.dir=C\:\\Users\\USERNAME\\AppData\\Local\\Android\\Sdk
```

macOS / Linux:

```properties
sdk.dir=/Users/USERNAME/Library/Android/sdk
```

Google Cloud / `GOOGLE_WEB_CLIENT_ID` **не нужны** — вход через WebView cookie.

## Команды Gradle

Debug APK:

```bat
gradlew.bat :app:assembleDebug
```

```bash
./gradlew :app:assembleDebug
```

Установка на подключённое устройство:

```bat
gradlew.bat :app:installDebug
```

Release (подпишите своим keystore при необходимости):

```bat
gradlew.bat :app:assembleRelease
```

По умолчанию `isMinifyEnabled = false`.

## Package ID

```
com.youtubevoice.app
```

## Разрешения

| Разрешение | Зачем |
|---|---|
| `INTERNET` | Загрузка метаданных и аудио |
| `FOREGROUND_SERVICE` / `…_MEDIA_PLAYBACK` | Фон + уведомление |
| `WAKE_LOCK` | Не засыпать во время игры |
| `POST_NOTIFICATIONS` | Шторка на Android 13+ |

## Типичные проблемы

| Симптом | Что проверить |
|---|---|
| Gradle Sync failed | JDK 17, SDK 35, сеть до `google()` / Maven / JitPack |
| Нет звука / 403 | YouTube сменил клиент потока — смотрите `YoutubeLibraryApi.resolveAudioStream` |
| Пустая библиотека | Войдите заново; нужен cookie с `SAPISID` |
| Плеер «сворачивается» | Смотрите `filesDir/crash.log` (пишется из `YoutubeVoiceApp`) |

## Артефакты

После `assembleDebug`:

```
app/build/outputs/apk/debug/app-debug.apk
```

Готовый APK в репозитории (для скачивания без сборки):

```
releases/YouTubeVoice-1.5.0-debug.apk
```

Чтобы обновить файл в `releases/` после изменений кода:

```bat
gradlew.bat :app:assembleDebug
copy app\build\outputs\apk\debug\app-debug.apk releases\YouTubeVoice-1.5.0-debug.apk
```