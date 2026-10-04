<div align="center">

# 🎧 AudioBookRed

**Android-приложение для поиска, прослушивания и локального скачивания аудиокниг из нескольких источников.**

![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-ExoPlayer-red)
![License](https://img.shields.io/badge/License-AGPL_v3-blue.svg)

</div>

---

## Интерфейс

<p align="center">
  <img src="assets/screenshots/home.jpg" width="30%" alt="Главная">
  <img src="assets/screenshots/catalog.jpg" width="30%" alt="Каталог">
  <img src="assets/screenshots/player.jpg" width="30%" alt="Плеер">
</p>

<p align="center">
  <img src="assets/screenshots/library.jpg" width="30%" alt="Библиотека">
  <img src="assets/screenshots/settings.jpg" width="30%" alt="Настройки">
</p>

## Возможности

- поиск и просмотр каталогов нескольких источников;
- карточки книг, авторы, чтецы, жанры и циклы;
- встроенный аудиоплеер с главами, скоростью, перемоткой, таймером сна, пропуском тишины и закладками;
- сохранение прогресса прослушивания;
- история, избранное, циклы и библиотека;
- локальное скачивание аудиокниг;
- резервное копирование и восстановление пользовательских данных;
- RuTracker + TorrServe;
- воспроизведение полностью скачанных книг локально;
- MediaSession и Android Auto;
- светлая, тёмная и системная темы.

Каталог, поиск и подготовка воспроизведения выполняются непосредственно на устройстве. Отдельный backend AudioBookRed для каталога и playback не используется.

## Источники

| **Источник** | **Каталог** | **Поиск** | **Прослушивание** |
| --- | :---: | :---: | :---: |
| Audiopolka | ✅ | ✅ | ✅ |
| уКниг | ✅ | ✅ | ✅ |
| Audioboo | ✅ | ✅ | ✅ |
| Книга в ухе | ✅ | ✅ | ✅ |
| Baza-Knig | ✅ | ✅ | ✅ |
| MY-AUDIOBOOKS | ✅ | ✅ | ✅ |

> Доступность конкретного источника зависит от его текущей работы и структуры страниц.

### RuTracker

RuTracker поддерживается как отдельный источник аудиокниг. Для **прослушивания и скачивания** требуется настроенный **TorrServe**.

После полного скачивания аудиокнига может воспроизводиться локально без TorrServe.

## Техническая часть

Проект написан на Kotlin и использует современный Android-стек:

- Jetpack Compose;
- AndroidX Media3 / ExoPlayer / MediaSession;
- Room;
- Paging 3;
- Hilt;
- WorkManager;
- OkHttp;
- Kotlin Coroutines;
- KSP.

Минимальная версия Android — **7.0 (API 24)**.

Для внешних сервисов RuTracker и TorrServe пользовательские логины и пароли задаются в приложении. Пароли сохраняются локально и шифруются через Android Keystore.

## Сборка

Требования:

- JDK 17;
- Android SDK;
- Android platform `android-37.0`;
- Build Tools `37.0.0`.

Проверка проекта:

```bash
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon compileDebugAndroidTestKotlin
./gradlew --no-daemon lintDebug
```

Сборка debug APK:

```bash
./gradlew --no-daemon assembleDebug
```

Debug-сборка использует applicationId `com.aistudio.audiobookred.player.debug` и может быть установлена рядом с основной версией приложения.

## Лицензия

Проект распространяется на условиях **GNU Affero General Public License v3.0 (AGPL-3.0-only)**.

Полный текст лицензии находится в файле [LICENSE](LICENSE).
