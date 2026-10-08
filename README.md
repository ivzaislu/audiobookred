<div align="center">

# 🎧 AudioBookRed

**Android-приложение для поиска, прослушивания и локального скачивания аудиокниг из нескольких источников.**

Поиск • Каталоги • Встроенный плеер • Скачивание • RuTracker + TorrServe

![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white)
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

- 🔎 поиск аудиокниг сразу по нескольким источникам;
- 📚 каталоги, жанры, авторы, чтецы и циклы;
- 🎛️ возможность отдельно включать и отключать источники для Каталога и общего поиска;
- 🏠 настраиваемая Главная: «Новинки», «Популярное», «Продолжить слушать» и «Скачанные»;
- ▶️ встроенный аудиоплеер с главами, скоростью воспроизведения и перемоткой;
- 🏷️ короткие названия глав «Глава 1», «Глава 2» и т. д. при желании;
- 🌙 таймер сна, пропуск тишины и закладки;
- ❤️ избранное, история, библиотека и сохранение прогресса;
- ⬇️ локальное скачивание аудиокниг;
- 💾 резервное копирование и восстановление пользовательских данных и настроек;
- 🚗 MediaSession и Android Auto;
- 🌓 светлая, тёмная и системная темы;
- 🧲 поддержка RuTracker и TorrServe.

## Источники

| **Источник** | **Каталог** | **Поиск** | **Прослушивание** |
| --- | :---: | :---: | :---: |
| Audiopolka | ✅ | ✅ | ✅ |
| уКниг | ✅ | ✅ | ✅ |
| Audioboo | ✅ | ✅ | ✅ |
| Книга в ухе | ✅ | ✅ | ✅ |
| Baza-Knig | ✅ | ✅ | ✅ |
| MY-AUDIOBOOKS | ✅ | ✅ | ✅ |
| Audiokniga.Life | ✅ | ✅ | ✅ |

Источники можно включать и отключать по отдельности в настройках приложения. Как минимум один источник всегда остаётся включённым.

> Доступность конкретного источника зависит от его текущей работы и структуры страниц. Для отдельных книг источник может предоставлять только ознакомительный фрагмент или метаданные.

### RuTracker

RuTracker доступен как отдельный источник аудиокниг.

- для работы **поиска** необходимо указать в настройках приложения **логин и пароль RuTracker**;
- для **прослушивания и скачивания** через торрент требуется настроенный **TorrServe**;
- после полного скачивания аудиокнига может воспроизводиться локально без TorrServe.

## Для разработчиков

<details>
<summary><strong>Стек и сборка из исходников</strong></summary>

### Стек

- Kotlin;
- Jetpack Compose;
- AndroidX Media3 / ExoPlayer / MediaSession;
- Room;
- Paging 3;
- Hilt;
- WorkManager;
- OkHttp;
- Kotlin Coroutines;
- KSP.

### Требования

- JDK 17;
- Android SDK;
- Android platform `android-37.0`;
- Build Tools `37.0.0`.

### Проверка проекта

```bash
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon compileDebugAndroidTestKotlin
./gradlew --no-daemon lintDebug
```

### Сборка APK

```bash
./gradlew --no-daemon assembleDebug
```

</details>

## Лицензия

Проект распространяется на условиях **GNU Affero General Public License v3.0 (AGPL-3.0-only)**.

Полный текст лицензии находится в файле [LICENSE](LICENSE).

## Отказ от ответственности

AudioBookRed **не размещает, не хранит и не распространяет аудиокниги или другие медиаматериалы**. Приложение предоставляет доступ к информации и контенту, доступным на сторонних общедоступных ресурсах.

Авторы проекта не контролируют содержимое сторонних источников и не несут ответственности за размещённые на них материалы, их доступность или правовой статус. Все права на контент принадлежат соответствующим правообладателям.

Пользователь самостоятельно несёт ответственность за соблюдение законодательства и правил сторонних сервисов при использовании приложения.
