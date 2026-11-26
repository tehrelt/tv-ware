# tv-ware

Прошивка LineageOS 24.0 и кастомный лаунчер для Xiaomi TV Box S 2nd Gen (`jaws`).

## Структура

- `tv-launcher/` — Android TV лаунчер (`com.tvware.launcher`, Jetpack Compose + TV Material3). Подробности сборки и установки: `tv-launcher/README.md`.
- `vendor/custom/` — изолированный слой для LineageOS 24.0 (`custom.mk`, `Android.bp`, оверлеи анимаций `0.25x`, `sysconfig` для `su.happ.proxyutility`, фикс `jaws_logo_deps.mk` для `siso`, скрипт `sync_custom_apks.py`).
- `manifests/roomservice.xml` — локальный манифест `repo` для `jaws`.
- `patches/device_xiaomi_jaws.patch` — подключение `vendor/custom/custom.mk` в `device/xiaomi/jaws/lineage_jaws.mk`.
- `screenshots/` — скриншоты лаунчера с эмулятора Android TV 12 (`home.png`, `grid.png`, `settings.png`, `context.png`).

## tv-setup

`tv-setup/` — приложение для Windows 11 (Python 3.12) для настройки Android TV. Команда `tv-setup\run.bat` создаёт виртуальное окружение, при необходимости устанавливает зависимости и запускает графический интерфейс; тесты запускаются командой `python -m unittest discover -s tv-setup\tests -t tv-setup`.

[Спецификация tv-setup](docs/superpowers/specs/2026-11-25-tv-setup-design.md).

CI публикует `tv-launcher.apk` в релизе `launcher-latest`. Для постоянной подписи задайте `KEYSTORE_BASE64` и `KEYSTORE_PROPERTIES` как Base64 содержимого `release.jks` и `keystore.properties` соответственно; в свойствах укажите `storeFile=release.jks`. Без обоих секретов используется отладочная подпись.

## Бинарные зависимости

Сторонние APK (`vendor/custom/prebuilt/Happ/Happ.apk`, `~/custom_apks/*.apk`) и ключи подписи исключены из git через `.gitignore`.
