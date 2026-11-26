# tv-ware

Прошивка LineageOS 24.0, кастомный лаунчер и утилита настройки для Xiaomi TV Box S 2nd Gen (`jaws`) и других устройств на Android TV.

## Структура

- `tv-launcher/` — Android TV лаунчер (`com.tvware.launcher`, Jetpack Compose + TV Material3): главный экран, сетка приложений, медиа-экран с текстами песен (Spicy Lyrics), заставка (`LauncherDreamService`), веб-настройки по QR-коду (`WebSettingsServer`) и автозапуск Happ. Подробности сборки и установки: `tv-launcher/README.md`.
- `tv-setup/` — GUI-утилита для Windows 11 (Python 3.12) для первоначальной настройки Android TV по ADB: поиск устройств (mDNS/сеть), установка APK (`tv-launcher`, `Happ`), отключение bloatware, настройка анимаций, заставки и лаунчера по умолчанию.
- `vendor/custom/` — изолированный слой для LineageOS 24.0 (`custom.mk`, `Android.bp`, оверлеи анимаций `0.25x`, `sysconfig` для `su.happ.proxyutility`, фикс `jaws_logo_deps.mk` для `siso`, скрипт `sync_custom_apks.py`).
- `manifests/roomservice.xml` — локальный манифест `repo` для `jaws`.
- `patches/device_xiaomi_jaws.patch` — подключение `vendor/custom/custom.mk` в `device/xiaomi/jaws/lineage_jaws.mk`.
- `screenshots/` — скриншоты лаунчера с эмулятора Android TV 12 (`home.png`, `grid.png`, `settings.png`, `context.png`).
- `docs/superpowers/` — спецификации и планы разработки (`specs/2026-11-25-tv-setup-design.md`, `plans/2026-11-26-tv-setup.md`).

## tv-setup

Запуск на Windows: `tv-setup\run.bat` создаёт `.venv`, при необходимости устанавливает зависимости из `tv-setup\requirements.txt` и запускает GUI (`python -m tv_setup`).

Тесты:

```bat
python -m unittest discover -s tv-setup\tests -t tv-setup
```

## CI и релизы лаунчера

Workflow `.github/workflows/release-launcher.yml` при изменениях в `tv-launcher/**` (или ручном запуске) прогоняет `:app:testDebugUnitTest`, собирает release APK и публикует `tv-launcher.apk` в релизе `launcher-v1.0.N` (где `N` — номер запуска workflow), а также обновляет тег `launcher-latest` для совместимости с `tv-setup`.

Для постоянной подписи задайте секреты `KEYSTORE_BASE64` и `KEYSTORE_PROPERTIES` как Base64 содержимого `release.jks` и `keystore.properties` соответственно; в свойствах укажите `storeFile=release.jks`. Без обоих секретов используется отладочная подпись.

## Бинарные зависимости

Сторонние APK (`vendor/custom/prebuilt/Happ/Happ.apk`, `~/custom_apks/*.apk`, `tv-setup/apks/*.apk`), локальные инструменты (`tv-setup/tools/`), состояние (`tv-setup/state/`) и ключи подписи (`*.jks`, `keystore.properties`) исключены из git через `.gitignore`.
