# Дизайн-документ: `tv-setup` — десктопное приложение для настройки Android TV с ноутбука

**Дата:** 2026-11-25  
**Статус:** Черновик на согласование  
**Расположение в репозитории:** `tv-setup/`

## 1. Цель и контекст

Личное десктопное приложение для Windows, которое позволяет с ноутбука по USB или Wi-Fi ADB без перепрошивки:
1. Автоматически находить Android TV / приставку в локальной сети (и по USB).
2. Устанавливать `tv-launcher` (`com.tvware.launcher`), `Happ` (`su.happ.proxyutility`) и дополнительные APK.
3. Назначать `tv-launcher` домашним экраном (`HOME`) и при необходимости отключать стоковый лаунчер.
4. Отключать ненужные предустановленные приложения (включая `Google Play Games`, рекламу и сервисы рекомендаций) с возможностью полного отката.
5. Применять системные оптимизации (`0.25x` масштаб анимаций, исключение энергосбережения для `Happ`).

### Не-цели
- Не является универсальным коммерческим комбайном для любых моделей ТВ и сторонних пользователей.
- Не требует root-прав, разблокировки загрузчика или модификации `/system` / `/product`.
- Не собирает прошивку LineageOS и не заменяет Gradle-сборку на `vm`.

## 2. Ограничения и среда

- **ОС ноутбука:** Windows 11, Python 3.12.10.
- **ADB:** на ноутбуке `adb.exe` не установлен в `PATH`; приложение должно само находить или скачивать официальный `platform-tools` для Windows.
- **Сеть ноутбука:** на машине присутствуют виртуальные интерфейсы (`vEthernet`, `Meta` TUN `198.18.0.1/30`, link-local `169.254.x.x`), которые нужно исключать при сканировании подсети, оставляя реальные LAN/Wi-Fi подсети (например, `192.168.31.0/24`).
- **Источники APK:**
  - Локальная папка `tv-setup/apks/` (исключена из git через `.gitignore`).
  - Скачивание актуальных релизов напрямую из публичных GitHub-репозиториев без авторизации (без зависимости от `vm`):
    - `tv-launcher`: публичный репозиторий `tehrelt/tv-ware`, фиксированный rolling-тег `launcher-latest` (`https://api.github.com/repos/tehrelt/tv-ware/releases/tags/launcher-latest`, ассет `tv-launcher.apk`, собирается через `.github/workflows/release-launcher.yml` с `make_latest: false`, чтобы не конфликтовать с будущими релизами прошивки).
    - `Happ`: публичный репозиторий `Happ-proxy/happ-android`, последний стабильный релиз (`https://api.github.com/repos/Happ-proxy/happ-android/releases/latest`, ассет `Happ.apk`, пакет `su.happ.proxyutility`).

## 3. Рассмотренные подходы

1. **Python 3.12 + CustomTkinter + `zeroconf` (выбран)**
   - Нативное окно Windows без браузера и локального HTTP-сервера.
   - Минимум зависимостей (`customtkinter`, `zeroconf`), простая работа с `subprocess`, сокетами, файловыми диалогами и GitHub Releases API (`urllib.request`).
   - При необходимости позже упаковывается в `.exe` через PyInstaller.
2. **Node.js 26 + окно Edge (`msedge --app`)** — отклонён: требует локального HTTP/WebSocket-моста к `adb.exe` и привязан к Edge.
3. **Electron / Tauri** — отклонены: Electron тянет ~150 МБ Chromium, для Tauri на ноутбуке нет Rust toolchain.

## 4. Архитектура и модули (`tv-setup/` и CI)

```text
.github/workflows/
└── release-launcher.yml     # Сборка tv-launcher release APK и публикация в GitHub Release

tv-setup/
├── run.bat                  # Создаёт .venv при первом запуске, ставит зависимости, запускает GUI
├── requirements.txt         # customtkinter, zeroconf
├── apks/                    # Локальные и скачанные APK (в .gitignore)
├── tools/                   # Скачанный platform-tools/adb.exe (в .gitignore)
├── state/                   # Снимки состояния устройств для отката (в .gitignore)
├── tv_setup/
│   ├── __init__.py
│   ├── __main__.py          # Точка входа
│   ├── adb_client.py        # Поиск/скачивание adb.exe и обёртка над adb-командами
│   ├── discovery.py         # Автопоиск ТВ: USB + mDNS + скан порта 5555 в LAN
│   ├── presets.py           # Каталог безопасного debloat и системных твиков
│   ├── optimizer.py         # Снятие снимка, применение оптимизаций, откат
│   ├── installer.py         # Сканирование apks/, загрузка релизов с GitHub, установка APK и HOME
│   └── gui.py               # Графитовый интерфейс CustomTkinter
└── tests/
    ├── test_discovery.py    # Фильтрация интерфейсов, дедупликация и классификация устройств
    ├── test_optimizer.py    # Парсинг состояния, план изменений и логика отката (на моках ADB)
    ├── test_installer.py    # Выбор ассетов из GitHub Releases API и проверка версий/метаданных
    └── test_adb_client.py   # Безопасная распаковка zip (zip-slip) и парсинг adb devices -l
```

## 5. Ключевые сценарии и поведение

### 5.1. Автопоиск `adb.exe` и загрузка `platform-tools`
- Порядок поиска: `tv-setup/tools/platform-tools/adb.exe` -> `shutil.which("adb")` -> `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.
- Если `adb.exe` не найден, кнопка/автопроверка при старте предлагает скачать `https://dl.google.com/android/repository/platform-tools-latest-windows.zip` в `tv-setup/tools/` с проверкой путей архива (защита от zip-slip) и отображением прогресса.
- Все вызовы `subprocess.run` выполняются с `CREATE_NO_WINDOW` и таймаутами, чтобы не мигали консольные окна.

### 5.2. Автопоиск телевизора в сети и по USB
Запускается автоматически при открытии окна и по кнопке «Найти ТВ»:
1. **USB:** `adb devices -l` — находит подключённые по кабелю устройства.
2. **mDNS (`zeroconf`):** в течение ~2.5 секунд слушает сервисы:
   - `_adb-tls-connect._tcp.local.` (Android 11+ Wireless Debugging, готов к `adb connect`)
   - `_adb-tls-pairing._tcp.local.` (порт сопряжения Android 11+, требует 6-значный код)
   - `_adb._tcp.local.` (классический ADB по TCP)
   - `_androidtvremote2._tcp.local.` (Android TV в сети, даже если отладка по сети ещё выключена)
3. **Активный скан подсети:** определяет локальные IPv4-интерфейсы, отбрасывает loopback, `169.254.0.0/16`, виртуальные адаптеры (`vEthernet`, `WSL`, `Hyper-V`, `Docker`, `VMware`, `VirtualBox`, `Meta`, `WireGuard`, `Tailscale`, `ZeroTier`, `TAP`/`TUN`) и сканирует TCP-порт `5555` в `/24` подсетях (пул потоков, таймаут `0.35s`).
4. **Объединение и статусы в списке:**
   - `ADB готов` — порт `5555` или `_adb-tls-connect._tcp` открыт; подключение в один клик (`adb connect <ip>:<port>`).
   - `Нужно сопряжение` — найден `_adb-tls-pairing._tcp`; при выборе открывается компактный ввод 6-значного кода (`adb pair <ip>:<pair_port> <code>`), затем автоматический `adb connect` на порт `_adb-tls-connect._tcp` (или ручной порт).
   - `ТВ найден (отладка выкл.)` — найден `_androidtvremote2._tcp`, но ADB-порты закрыты; показывается подсказка включить «Отладку по сети / USB» в настройках разработчика на ТВ.
5. **Ручное подключение:** поле `IP:порт` (по умолчанию порт `5555`) и кнопка «Сопряжение по коду» на случай, если mDNS заблокирован роутером.

### 5.3. Инспекция устройства и снимок для отката
После подключения к устройству приложение читает и отображает:
- Модель (`ro.product.model`), версию Android (`ro.build.version.release`, SDK), способ подключения.
- Текущий `HOME`-компонент (`cmd package resolve-activity --brief --user current -a android.intent.action.MAIN -c android.intent.category.HOME`).
- Текущие масштабы анимаций (`window_animation_scale`, `transition_animation_scale`, `animator_duration_scale`).
- Статус `Happ` в `dumpsys deviceidle whitelist`.
- Установленные пакеты (`pm list packages` и `pm list packages -d`).

Перед первым изменением сохраняется снимок `tv-setup/state/<device_id>.json` (исходный `HOME`, исходные масштабы анимаций, список пакетов, отключённых/удалённых именно этим приложением, и способ отключения для каждого пакета). Повторные запуски не перезаписывают исходный `HOME` и исходные анимации, а дополняют список изменённых пакетов.

### 5.4. Установка APK, загрузка из репозиториев и смена лаунчера
- Папка `tv-setup/apks/` сканируется автоматически; для каждого `.apk` отображается имя файла, версия (если сохранена в метаданных загрузки) и чекбокс.
- Кнопка **«Скачать актуальные»** загружает по HTTPS через GitHub Releases API без авторизации (оба репозитория публичные, без зависимости от `vm`):
  - `tv-launcher.apk` из rolling-релиза `tehrelt/tv-ware` (`releases/tags/launcher-latest`; собирается GitHub Actions workflow `.github/workflows/release-launcher.yml` при изменениях в `tv-launcher/**` или ручном запуске с `make_latest: false`; при наличии секретов `KEYSTORE_BASE64` / `KEYSTORE_PROPERTIES` подписывается постоянным ключом, иначе debug-ключом).
  - `Happ.apk` из последнего релиза `Happ-proxy/happ-android` (`releases/latest`, ассет `Happ.apk`).
  - Сохраняет локальный файл метаданных `tv-setup/apks/.versions.json` (`tag_name`, `asset_id`, `updated_at`, `downloaded_at`), чтобы корректно определять обновления даже для rolling-тега `launcher-latest` и не перекачивать неизменившийся файл (с возможностью принудительного обновления).
- Кнопка **«Добавить APK...»** копирует выбранный пользователем `.apk` в `tv-setup/apks/`.
- Установка выполняется через `adb install -r -d <apk>` (при конфликте подписи `INSTALL_FAILED_UPDATE_INCOMPATIBLE` выводится понятное сообщение и запрос подтверждения на удаление старой версии перед повторной установкой).
- При включённой опции **«Сделать tv-launcher домашним экраном»**:
  1. Проверяется, что `com.tvware.launcher` установлен на устройстве.
  2. Выполняется `cmd package set-home-activity --user current com.tvware.launcher/.MainActivity`.
  3. Если включена опция **«Отключить стоковый лаунчер»**, прежний `HOME`-пакет (если это не `com.tvware.launcher` и не `com.android.settings`) отключается только после успешной установки и назначения `tv-launcher`.
  4. Отправляется `input keyevent KEYCODE_HOME`.

### 5.5. Оптимизация и безопасный debloat
- **Анимации `0.25x`:** `settings put global window_animation_scale 0.25`, `transition_animation_scale 0.25`, `animator_duration_scale 0.25`.
- **Happ без энергосбережения:** `dumpsys deviceidle whitelist +su.happ.proxyutility` (и `+com.happproxy`, если установлен).
- **Список лишних приложений (`presets.py`):** в UI показываются только те пакеты из каталога, которые реально найдены на подключённом ТВ, с пометкой статуса (`Активен` / `Отключён`):
  - **Google TV / Android TV (отмечены по умолчанию):** `com.google.android.play.games`, `com.google.android.videos`, `com.google.android.music`, `com.google.android.tvrecommendations`, `com.google.android.feedback`, `com.google.android.partnersetup`, `com.android.dreams.basic`, `com.android.htmlviewer`.
  - **Опциональные Google/стриминг (сняты по умолчанию):** `com.google.android.katniss` (Google Assistant), `com.google.android.youtube.tvmusic`, `com.netflix.ninja`, `com.amazon.amazonvideo.livingroom`, `com.disney.disneyplus`.
  - **Xiaomi / PatchWall (отмечены по умолчанию, если найдены):** `com.xiaomi.mitv.advertise`, `com.miui.systemAdSolution`, `com.xiaomi.mitv.shop`, `com.xiaomi.mitv.payment`, `com.xiaomi.mitv.pay`, `com.xiaomi.mibox.gamecenter`, `com.xiaomi.mitv.updates`, `com.xiaomi.tv.appupgrade`.
  - **Стоковый лаунчер** (`com.google.android.apps.tv.launcherx`, `com.google.android.tvlauncher`, `com.mitv.tvhome`, `org.lineageos.tv.launcher`) — управляется отдельной защищённой галочкой, чтобы не оставить ТВ без домашнего экрана.
- **Механизм отключения:** сначала `pm disable-user --user 0 <pkg>`; если прошивка запрещает `disable-user` для системного пакета, выполняется `pm uninstall -k --user 0 <pkg>`. Способ фиксируется в `state/<device_id>.json`.
- **Откат:** кнопка «Откатить изменения» восстанавливает пакеты (`pm enable` или `cmd package install-existing`), возвращает исходный `HOME` (`cmd package set-home-activity`) и исходные масштабы анимаций.

### 5.6. Интерфейс (текстовый эскиз)

```text
+-----------------------------------------------------------------------------------+
| tv-setup                                        [ADB: готов]  [Найти ТВ] [Обновить]|
+-----------------------------------------------------------------------------------+
| Устройства: [ 192.168.31.42:5555 · MiTV-AFKR0 · ADB готов v ] [Подключить] [IP...] |
| Статус: Подключено · Android 14 · HOME: com.google.android.apps.tv.launcherx      |
+-----------------------------------------+-----------------------------------------+
| Приложения (tv-setup/apks/)             | Оптимизация и очистка                   |
| [x] tv-launcher.apk (com.tvware...)     | [x] Анимации 0.25x                      |
| [x] Happ.apk (su.happ.proxyutility)     | [x] Happ без энергосбережения           |
| [ ] SmartTube.apk                       | [x] Сделать tv-launcher домашним        |
|                                         | [x] Отключить стоковый лаунчер          |
| [Скачать актуальные]  [Добавить APK...]   |                                         |
|                                         | Найдено лишнее на ТВ:                   |
|                                         | [x] Play Games (com.google...games)     |
|                                         | [x] Рекомендации (tvrecommendations)    |
|                                         | [ ] Google Assistant (katniss)          |
+-----------------------------------------+-----------------------------------------+
| [ Применить выбранное ]   [ Откатить изменения ]                                  |
| Лог: > Найдено устройство 192.168.31.42:5555 (ADB готов)                          |
+-----------------------------------------------------------------------------------+
```

Палитра окна повторяет графитовую тему `tv-launcher`: фон `#0A0A0B`, карточки `#1C1C1E`, элементы `#2C2C2E`, текст `#F5F5F7` / `#A1A1A6`, акцент `#0A84FF`, статус успеха `#4CC38A`.

## 6. Что остаётся на этап реализации

- Точные размеры виджетов, отступы и асинхронная очередь задач в Tkinter (`after()` / фоновые потоки).
- Детали парсинга `aapt2` / `zipfile` (чтение `AndroidManifest.xml` или имени пакета из APK, если нужно показать `packageName` до установки — достаточно простого парсера или отображения имени файла с опциональным чтением метаданных).
- Набор unit-тестов для `discovery`, `optimizer` и `adb_client` с моками `subprocess` и сокетов.
