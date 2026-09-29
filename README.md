<p align="center" style="text-align: center">
  <img src="https://github.com/yumata/lampa/blob/main/img/logo-icon.svg" width="25%"><br/>
</p>
  <br/>
<p align="center">
  LAMPA client browser for Android and Android TV
  <br/>
  <br/>
  <a href="https://github.com/lampa-app/LAMPA/issues">
    <img src="https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat" alt="CodeFactor" />
  </a>
  <a href="https://github.com/lampa-app/LAMPA/actions/workflows/android.yml" rel="nofollow">
    <img src="https://img.shields.io/github/actions/workflow/status/lampa-app/LAMPA/android.yml?logo=Github" alt="Build" />
  </a>
  <a href="https://github.com/lampa-app/LAMPA/tags" rel="nofollow">
    <img alt="GitHub tag (latest SemVer pre-release)" src="https://img.shields.io/github/v/tag/lampa-app/LAMPA?include_prereleases&label=version"/>
  </a>
</p>
<p align="center">
System requirements: Android 4.1+ (API level 16+)
</p>

### Last release links:
- [Release page](https://github.com/lampa-app/LAMPA/releases/latest)
- [Direct apk download link](https://github.com/lampa-app/LAMPA/releases/latest/download/app-lite-release.apk)

## Локальный встроенный плеер: медиасессия, 29.09.2026

`PlayerActivity` теперь создаёт `PlayerMediaSession` рядом с ExoPlayer и
освобождает её до освобождения плеера. Сессия публикует название, состояние,
позицию и доступную обложку; команды play/pause/seek передаются тому же плееру.
Маршрут возврата результата, история просмотра и продолжение серии не менялись.

`launchInternalPlayer` передаёт картинку из thumbnail элемента либо из полей
background_image/img/poster карточки. `MediaMetadata.artworkUri` хранит адрес;
Glide асинхронно загружает уменьшенный Bitmap, который публикуется в системной
MediaMetadata. При смене элемента старая картинка очищается, поздний результат
предыдущей загрузки игнорируется. Если у источника нет изображения или загрузка
не удалась, публикация названия и состояния продолжается без картинки.

Сборка `:app:assembleFullDebug` прошла. Для вычисления неиспользуемого release-
конфига Gradle требуется непустой KEYSTORE_FILE; использовался существующий
локальный debug.keystore. Новые ключи не создавались, подпись не менялась.
APK установлен поверх `top.rootu.lampa.builtin` на A95X через `install -r`.
Подпись совпала с предыдущей; хеши 7 файлов shared_prefs до и после совпали;
хеш установленного APK совпал со сборкой. Во время установки был открыт YouTube,
просмотр не переключался. Реальный прогон нового плеера с фильмом ещё нужен.

## Браузерный плеер: проверено на устройстве, 29.09.2026

Подключён `BrowserMediaSession` к onResume/onPause/onDestroy `MainActivity`.
`pult-web-media.js` читает реальный HTML-плеер Lampa; `WebPlaybackSnapshot`
проверяет и ограничивает ответ. Наблюдатель не запускает воспроизведение:
раз в секунду при открытом приложении публикует название, сериал, состояние,
позицию и доступную обложку. Зависший запрос ограничен двумя секундами,
устаревшие ответы после ухода с экрана игнорируются.

Обложка карточки загружается через существующий Glide и настройки TMDB,
уменьшается до 720 пикселей и передаётся в системную MediaSession как Bitmap.
При смене элемента старая загрузка отменяется. Медиаклавиши передаются в
существующий обработчик WebView; скрипт считывания не содержит команд плееру.

`:app:assembleFullDebug` и 6 тестов `WebPlaybackSnapshotTest` прошли.
Проверены также 9 утверждений для JS-считывателя в Node и чтение живого плеера.
APK установлен через `install -r`; подпись совпала с предыдущей,
хеш установленного APK совпал со сборкой, shared_prefs сохранились.

На A95X подтверждены серия «Слепой бандит», «Аватар: Легенда об Аанге»,
состояния воспроизведения/паузы и передача JPEG (10780 байт).
На OPPO визуально подтверждены название и приглушённая обложка фоном пульта.
При уходе Lampa в фон сессия исчезает, телефон убирает старые данные и обложку.
После проверки Lampa возвращена на экран, воспроизведение продолжено.
Снимки и итоговый журнал находятся локально в проекте pult; в Git не добавлялись.
