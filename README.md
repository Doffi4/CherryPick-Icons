# CherryPick Icons

[Русский](#русский) · [English](#english)

## Русский

Модуль LSPosed, добавляющий стеклянное оформление для лаунчера ColorOS/OxygenOS/RUI

> **Бета-тестирование.** Модуль тестировался только на **ColorOS 17**.
> На других прошивках могут быть проблемы совместимости.

### Как создавался проект

Проект был полностью навайбкожен с помощью **GPT 6.1 Sol**.
Участие автора проекта - воплощение идеи, создание логики работы и интерфейса.

### Возможности

\- Матовые подложки Frosted и резкое преломление Liquid Glass без размытия, с мягким свечением и дымкой.<br>
\- Конструктор рабочего стола: сетка 4–6 столбцов и 5–8 строк, предпросмотр страниц и размеров элементов, штатное перетаскивание иконок и виджетов. Проверка размещения и запрет удаления элементов при переносе. Нестандартные сетки экспериментальны; смена сетки может переставить элементы.<br>
\- Общие размеры иконок и папок, отступы индикатора, внешние поля совместимых виджетов и выравнивание рабочего стола ближе к доку. Применяются после перезапуска лаунчера без переноса элементов по ячейкам.<br>
\- Живое и статичное размытие обоев, общий лимит обновлений материала до 60 FPS.<br>
\- Отдельные настройки подложек папок, миниатюр и фото-виджетов.<br>
\- Настройки цвета, прозрачности, контура и объёма, русский и английский интерфейс.

### Совместимость и установка

Приложению выдать рут доступ, LSPosed и выбрать в нём системный лаунчер, оформление иконок сменить на Flux, другие икон паки не поддерживаются на данный момент.

На данный момент поддерживается ColorOS17/OxygenOS17/RUI7, 16 версия почти не тестировалась, при разных версиях прошивки и системного лаунчера поведение может быть непредсказуемым.

1. Установите APK, выдайте рут и включите CherryPick Icons в LSPosed.
2. В области действия выберите установленный системный лаунчер и CherryPick Icons.
3. Выберите Flux в системных настройках иконок.
4. Откройте модуль, настройте эффекты и перезапустите лаунчер.

[Подробный гайд по установке](INSTALL.md#русский)

### Сборка

Нужны JDK 17 и Android SDK с платформой API 35. Укажите путь к SDK в локальном
`local.properties` (`sdk.dir=...`) или через `ANDROID_HOME`.

```powershell
.\gradlew.bat assembleRelease lintRelease
```

Linux / macOS:

```sh
./gradlew assembleRelease lintRelease
```

APK появится в `app/build/outputs/apk/release/app-release.apk`.
Первой сборке нужен интернет для загрузки Gradle и зависимостей.

Ключи подписи в репозиторий не входят. Если есть локальный `app/debug.keystore`,
сборка использует его для совместимости с существующими тестовыми APK.
Иначе Gradle создаёт обычный локальный debug-ключ. Такие сборки предназначены
для разработки; для распространения нужно отдельно настроить постоянную подпись.
Обновление поверх установленного APK требует того же сертификата.

### Исходники

\- `app/src/main/java/com/cosmod/launcherglass/ui` - настройки и обмен с лаунчером.<br>
\- `app/src/main/java/com/cosmod/launcherglass/xposed` - хуки и материалы иконок, папок и фото.<br>
\- `app/src/main/java/com/cosmod/launcherglass/util` - получение и обработка обоев, диагностика.<br>
\- [ARCHITECTURE.md](ARCHITECTURE.md) - устройство рендера и история изменений.

Исторические ссылки `../scratch/...` в архитектурных заметках относятся к локальным
проверкам разработчика и не входят в публичные исходники.

### Благодарности и лицензия

Спасибо [PixelLauncherEnhanced](https://github.com/Mahmud0808/PixelLauncherEnhanced) / Mahmud0808 за разрешение на использование файлов.
Спасибо [@anime_killer2009](https://t.me/anime_killer2009) и [Kostubet Topics](https://t.me/KostubetTopics) за помощь.
Подробности заимствований: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Лицензия: [GNU GPL v3](COPYING).

### Поддержать проект

[Поддержать разработку через CloudTips](https://pay.cloudtips.ru/p/e6230fb4).

## English

An LSPosed module that adds a glass appearance to the ColorOS/OxygenOS/RUI launcher.

> **Beta testing.** The module has only been tested on **ColorOS 17**.
> Other firmware versions may have compatibility issues.

### How this project was made

This project was entirely vibecoded with **GPT 6.1 Sol**.
The project author's contribution was bringing the idea to life, defining the product logic,
and creating the interface.

### Features

\- Frosted backgrounds and sharp Liquid Glass refraction without blur, with soft bloom and haze.<br>
\- Desktop designer: 4–6 columns and 5–8 rows, page and item-footprint previews, and native icon/widget dragging. Placement validation and item deletion protection during migration. Custom grids are experimental; changing the grid can rearrange items.<br>
\- Shared icon/folder size, page-indicator spacing, supported widget exterior padding and workspace alignment towards the dock. Applied after restarting the launcher without changing item cell positions.<br>
\- Live and static wallpaper blur, with a shared material update limit of up to 60 FPS.<br>
\- Separate settings for folder plates, miniature icons, and photo widgets.<br>
\- Color, opacity, contour, and depth controls; Russian and English interfaces.

### Compatibility and installation

Grant the app root access, enable the module in LSPosed and select the system launcher in its scope. Switch the icon style to Flux; other icon packs are currently unsupported.

Currently supports ColorOS17/OxygenOS17/RUI7. Version 16 has barely been tested; behavior may be unpredictable across firmware and system launcher versions.

1. Install the APK, grant root access and enable CherryPick Icons in LSPosed.
2. Select the installed system launcher and CherryPick Icons in the module scope.
3. Select Flux in the system icon settings.
4. Open the module, adjust the effects, and restart the launcher.

[Detailed installation guide](INSTALL.md#english)

### Building

Use JDK 17 and Android SDK platform API 35. Set the SDK location in your local
`local.properties` (`sdk.dir=...`) or through `ANDROID_HOME`.

```powershell
.\gradlew.bat assembleRelease lintRelease
```

Linux / macOS:

```sh
./gradlew assembleRelease lintRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.
The first build needs an internet connection to download Gradle and dependencies.

Signing keys are excluded from this repository. If a local `app/debug.keystore` exists,
the build uses it to remain compatible with the existing test APKs. Otherwise, Gradle
generates a standard local debug key. These builds are intended for development;
distribution requires a separately configured, permanent signing identity.
Updating an installed APK requires the same signing certificate.

### Source layout

\- `app/src/main/java/com/cosmod/launcherglass/ui` - settings and launcher communication.<br>
\- `app/src/main/java/com/cosmod/launcherglass/xposed` - hooks and icon, folder, and photo materials.<br>
\- `app/src/main/java/com/cosmod/launcherglass/util` - wallpaper capture and processing, diagnostics.<br>
\- [ARCHITECTURE.md](ARCHITECTURE.md) - rendering architecture and change history.

Historical `../scratch/...` links in the architecture notes refer to local developer
validation artifacts and are not included in the published source.

### Credits and license

Thanks to [PixelLauncherEnhanced](https://github.com/Mahmud0808/PixelLauncherEnhanced) / Mahmud0808 for permission to use the files.
Thanks to [@anime_killer2009](https://t.me/anime_killer2009) and [Kostubet Topics](https://t.me/KostubetTopics) for their help.
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for adaptation details.
License: [GNU GPL v3](COPYING).

### Support the project

[Support development through CloudTips](https://pay.cloudtips.ru/p/e6230fb4).
