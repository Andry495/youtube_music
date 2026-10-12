# Авторизация Google / YouTube

Cookie-сессия через WebView (без Google Cloud OAuth). Как устроен вход и что чинили в 1.6.1.

## Поток входа

1. Кнопка «Войти» → сразу `YoutubeLoginActivity` (системный AccountManager chooser не используем — на Android 11+ часто сразу «отмена»).
2. WebView: `accounts.google.com/ServiceLogin` (или AccountChooser с email) → continue на `https://www.youtube.com/`.
3. После редиректа на youtube.com опрашиваем `CookieManager` до полного набора cookie.
4. Сессия пишется в DataStore (`youtube_session`); дальше InnerTube ходит с `Cookie` + `Authorization: SAPISIDHASH …`.

## Какие cookie нужны

| Cookie | Зачем |
|---|---|
| `SAPISID` или `__Secure-3PAPISID` | Секрет для `SAPISIDHASH` |
| `SID` / `__Secure-3PSID` / `__Secure-1PSID` / `LOGIN_INFO` | Признак реальной сессии (не mid-login) |
| `__Secure-1PSIDTS` / `__Secure-3PSIDTS` | Freshness; без ротации сессия «тухнет» за минуты |

Если есть только `__Secure-3PAPISID`, в строку cookie добавляем plain `SAPISID` с тем же значением (`YoutubeSessionCookies.normalize`).

## InnerTube и Host / Origin

Аутентифицированные запросы идут на:

```
https://www.youtube.com/youtubei/v1/...
Origin / X-Origin: https://www.youtube.com
Authorization: SAPISIDHASH <ts>_<sha1(ts + " " + SAPISID + " " + origin)>
```

**Нельзя** слать SAPISIDHASH на `youtubei.googleapis.com` с Origin `www.youtube.com` — Google отвечает:

```text
HTTP 400 Bad request: Origin doesn't match Host for XD3.
```

Поэтому DPI-rewrite `www.youtube.com/youtubei/*` → `youtubei.googleapis.com` отключён. Пути `/youtubei/` при bypass не переписываются.

## Типичные поломки и фиксы (1.6.1)

| Симптом | Причина | Фикс |
|---|---|---|
| Сразу «Вход отменён» | AccountManager chooser + package visibility | Прямой WebView login; `<queries>` в манифесте |
| После 2FA приложение «сворачивается» | `intent://` открывал YT Music / Google; `finish()` в фоне | Не запускаем внешние intent; finish только в `onResume`; continue → www.youtube.com |
| «Сессия Google ещё не готова» / Bad Request после входа | Host≠Origin (googleapis + youtube Origin) | InnerTube base = `www.youtube.com/youtubei/v1` |
| Библиотека пустая через время | Протухли `*PSIDTS` | `YoutubeCookieRotator` → `POST accounts.google.com/RotateCookies` при старте и перед API |
| Вход обрывается слишком рано | Хватали только SAPISID mid-login | Ждём SID + settle ~1.2 с, затем нормализуем cookie |

## Ключевые классы

- `YoutubeLoginActivity` — WebView, опрос cookie, блокировка внешних intent
- `YoutubeAccountAuth` — DataStore, `persistSession` / `refreshSessionCookies`
- `YoutubeSessionCookies` — parse / normalize / isComplete
- `YoutubeCookieRotator` — ротация SIDTS
- `YoutubeLibraryApi.innertube` — Cookie + SAPISIDHASH на www.youtube.com

## Проверка

1. Выйти → Войти → подтверждение Google → возврат в плеер (не на рабочий стол).
2. Вкладка «Библиотека» — плейлисты без HTTP 400.
3. В `files/datastore/youtube_session.preferences_pb` есть SAPISID и SID (смотреть имена cookie, не значения).
