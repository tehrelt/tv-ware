# tv-ware

Прошивка LineageOS 24.0 и кастомный лаунчер для Xiaomi TV Box S 2nd Gen (`jaws`).

## Структура

- `tv-launcher/` — Android TV лаунчер (`com.tvware.launcher`, Jetpack Compose + TV Material3). Подробности сборки и установки: `tv-launcher/README.md`.
- `vendor/custom/` — изолированный слой для LineageOS 24.0 (`custom.mk`, `Android.bp`, оверлеи анимаций `0.25x`, `sysconfig` для `su.happ.proxyutility`, фикс `jaws_logo_deps.mk` для `siso`, скрипт `sync_custom_apks.py`).
- `manifests/roomservice.xml` — локальный манифест `repo` для `jaws`.
- `patches/device_xiaomi_jaws.patch` — подключение `vendor/custom/custom.mk` в `device/xiaomi/jaws/lineage_jaws.mk`.
- `screenshots/` — скриншоты лаунчера с эмулятора Android TV 12 (`home.png`, `grid.png`, `settings.png`, `context.png`).

## Бинарные зависимости

Сторонние APK (`vendor/custom/prebuilt/Happ/Happ.apk`, `~/custom_apks/*.apk`) и ключи подписи исключены из git через `.gitignore`.
