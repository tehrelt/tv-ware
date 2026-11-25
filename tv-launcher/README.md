# TV Launcher

Android TV: `com.tvware.launcher`, minSdk 31, targetSdk 35.

## Сборка на VM

```sh
cd ~/tv-launcher
export JAVA_HOME="$HOME/android/lineage/prebuilts/jdk/jdk21/linux-x86"
export ANDROID_HOME="$HOME/android-sdk"
"$HOME/tools/gradle-8.14.3/bin/gradle" --console=plain :app:checkReleaseAarMetadata :app:assembleRelease
```

Ожидаемый APK: `app/build/outputs/apk/release/app-release.apk`.
Без `keystore.properties` release подписывается тестовым ключом. Для прошивки и последующих обновлений используйте постоянный ключ; обновление поверх APK с другой подписью не установится.

## Проверка по Wi-Fi

Выполнить с машины, которая видит приставку в локальной сети. Сначала включить беспроводную отладку и при необходимости выполнить `adb pair` с портом сопряжения. Для `TV` указать адрес и порт подключения, не порт сопряжения. Запускать из каталога проекта с готовым APK:

```sh
TV=192.168.1.50:5555; adb connect "$TV" && adb -s "$TV" install -r app/build/outputs/apk/release/app-release.apk && adb -s "$TV" shell cmd package set-home-activity --user current com.tvware.launcher/.MainActivity && adb -s "$TV" shell input keyevent KEYCODE_HOME
```

Перед сменой HOME запишите текущий компонент, чтобы восстановить его той же командой `set-home-activity`:

```sh
adb -s "$TV" shell cmd package resolve-activity --brief --user current -a android.intent.action.MAIN -c android.intent.category.HOME
```

Штатный лаунчер для пробной установки удалять не нужно. Вшивание в `/product/priv-app` выполняется отдельно при сборке образа. Тестирование на `jaws`, включая холодный старт, плавность и PSS, на VM не заменяется.
