# tv-ware

Кастомный Android TV лаунчер (`tv-launcher`) и утилита первоначальной настройки приставки по ADB (`tv-setup`).

## Структура

- `tv-launcher/` — Android TV лаунчер (`com.tvware.launcher`, Jetpack Compose + TV Material3): главный экран, сетка приложений, медиа-экран с текстами песен (Spicy Lyrics), заставка (`LauncherDreamService`), веб-настройки по QR-коду (`WebSettingsServer`) и автозапуск Happ. Подробности сборки и установки: `tv-launcher/README.md`.
- `tv-setup/` — GUI-утилита для Windows 11 (Python 3.12) для настройки Android TV по ADB: поиск устройств (mDNS/сеть), установка APK (`tv-launcher`, `Happ`), отключение bloatware, настройка анимаций, заставки и лаунчера по умолчанию.
- `screenshots/` — скриншоты лаунчера с эмулятора Android TV 12 (`home.png`, `grid.png`, `settings.png`, `context.png`).
- `docs/superpowers/` — спецификации и планы разработки (`specs/2026-11-25-tv-setup-design.md`, `plans/2026-11-26-tv-setup.md`).
- `vendor/custom/`, `manifests/`, `patches/` — архивные наработки под сборку LineageOS 24.0 для `jaws` (больше не развиваются).

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
