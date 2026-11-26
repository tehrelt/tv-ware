# TV Launcher

Android TV: `com.tvware.launcher`, minSdk 31, targetSdk 35.

## Сборка и тесты

Требуется JDK 21 и Android SDK (`ANDROID_HOME`). Из каталога `tv-launcher/`:

```sh
./gradlew --console=plain :app:testDebugUnitTest :app:checkReleaseAarMetadata :app:assembleRelease
```

На Windows используйте `.\gradlew.bat`.

Ожидаемый APK: `app/build/outputs/apk/release/app-release.apk`.
Без `keystore.properties` release подписывается отладочным ключом. Для последующих обновлений используйте постоянный ключ; обновление поверх APK с другой подписью не установится.

## Проверка по Wi-Fi

Выполнить с машины, которая видит приставку в локальной сети. Сначала включить беспроводную отладку и при необходимости выполнить `adb pair` с портом сопряжения. Для `TV` указать адрес и порт подключения, не порт сопряжения. Запускать из каталога `tv-launcher/` с готовым APK:

```sh
TV=192.168.1.50:5555; adb connect "$TV" && adb -s "$TV" install -r app/build/outputs/apk/release/app-release.apk && adb -s "$TV" shell cmd package set-home-activity --user current com.tvware.launcher/.MainActivity && adb -s "$TV" shell input keyevent KEYCODE_HOME
```

Перед сменой HOME запишите текущий компонент, чтобы восстановить его той же командой `set-home-activity`:

```sh
adb -s "$TV" shell cmd package resolve-activity --brief --user current -a android.intent.action.MAIN -c android.intent.category.HOME
```

Штатный лаунчер для пробной установки удалять не нужно (первоначальную настройку и отключение bloatware также можно выполнить через `tv-setup`). Для локальной проверки на эмуляторе есть скрипт `test-emu.ps1`.
