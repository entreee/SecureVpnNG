# Руководство по сборке Secure.VPN (Android)

Это подробный гайд по сборке и запуску приложения Secure.VPN из исходников.

## 1. Требования к окружению

- ОС: Windows, macOS или Linux
- Android Studio (рекомендуется последняя стабильная версия)
- Android SDK:
  - SDK Platform: Android 13 (API 33) и/или Android 14 (API 34)
  - Android SDK Build-Tools (соответствующие версии)
- JDK:
  - Рекомендуется JDK 17 для современных версий Android Gradle Plugin
  - Если используется более старая версия AGP, может подойти JDK 11
- Установленные инструменты:
  - Git
  - ADB (входит в состав Android SDK)
- Устройства для тестирования:
  - Эмулятор Android (API 29+)
  - Или реальное устройство с включённым режимом разработчика и отладкой по USB

Примечание: NDK не требуется, приложение не использует нативный код.

## 2. Клонирование репозитория

```bash
git clone https://example.com/your-repo.git
cd your-repo
```

(Замените URL на фактический адрес вашего репозитория.)

Структура проекта:
- V2rayNG/app — основной Android-модуль (Kotlin, Gradle)
- V2rayNG/build.gradle.kts, settings.gradle.kts — конфигурация сборки на верхнем уровне

## 3. Открытие проекта в Android Studio

1. Запустите Android Studio.
2. Выберите “Open an existing project” и укажите директорию проекта (корень, в котором лежит папка V2rayNG).
3. Дождитесь завершения синхронизации Gradle.
4. В настройках SDK проверьте:
   - Android SDK Platforms: установлены выбранные API (33/34).
   - Android SDK Build-Tools: установлены последние версии.
5. Убедитесь, что используется корректная версия JDK (File → Settings → Build, Execution, Deployment → Gradle → Gradle JDK).

## 4. Сборка в Android Studio

- Debug сборка:
  - В верхнем меню Build → Make Project или запустите конфигурацию “app” (зелёная кнопка Run).
  - APK будет собран в: `V2rayNG/app/build/outputs/apk/debug/`
- Запуск на устройстве:
  - Подключите устройство/запустите эмулятор.
  - Нажмите Run (Shift+F10) и выберите устройство.

## 5. Сборка из командной строки (Gradle Wrapper)

В корне проекта (где лежит `gradlew`):

- Сборка debug APK:
  ```bash
  ./gradlew :V2rayNG:app:assembleDebug
  ```
- Сборка release APK (без подписи или с подписью, если настроено):
  ```bash
  ./gradlew :V2rayNG:app:assembleRelease
  ```
- Установка debug APK на подключённое устройство:
  ```bash
  adb install -r V2rayNG/app/build/outputs/apk/debug/app-debug.apk
  ```

Если вы используете Windows:
```bat
gradlew :V2rayNG:app:assembleDebug
```

## 6. Подписание релизной сборки

Для публикации в сторе нужен подписанный release-APK.

1. Создайте или используйте существующий keystore:
   - Android Studio → Build → Generate Signed Bundle/APK → APK
   - Или сгенерируйте вручную:
     ```bash
     keytool -genkeypair -v -keystore my-release-key.jks -alias securevpn -keyalg RSA -keysize 2048 -validity 10000
     ```
2. Добавьте подпись в `V2rayNG/app/build.gradle.kts` (пример):

   ```kotlin
   android {
       signingConfigs {
           create("release") {
               storeFile = file(System.getenv("KEYSTORE_FILE"))
               storePassword = System.getenv("KEYSTORE_PASSWORD")
               keyAlias = System.getenv("KEY_ALIAS")
               keyPassword = System.getenv("KEY_PASSWORD")
           }
       }
       buildTypes {
           getByName("release") {
               isMinifyEnabled = true
               signingConfig = signingConfigs.getByName("release")
               proguardFiles(
                   getDefaultProguardFile("proguard-android-optimize.txt"),
                   "proguard-rules.pro"
               )
           }
       }
   }
   ```

3. Установите переменные окружения (без хранения секретов в репозитории):
   ```bash
   export KEYSTORE_FILE=/absolute/path/to/my-release-key.jks
   export KEYSTORE_PASSWORD=********
   export KEY_ALIAS=securevpn
   export KEY_PASSWORD=********
   ```
4. Соберите релиз:
   ```bash
   ./gradlew :V2rayNG:app:assembleRelease
   ```

Подписанный APK будет в `V2rayNG/app/build/outputs/apk/release/`.

## 7. Варианты импорта конфигураций и разрешения

Приложение поддерживает:
- Импорт из буфера обмена (кнопка “Импорт из буфера обмена” внизу главного экрана)
- Импорт из QR-кода (кнопка “Импорт из QR”)
- Управление подписками (“Добавить подписку” внизу главного экрана)
- Запросы разрешений:
  - Камера (для QR-сканера)
  - Уведомления (Android 13+, для уведомлений о состоянии)
  - Доступ к файлам/изображениям (для импорта из локального файла, если используется)

## 8. Тестирование и проверка

- Юнит-тесты/инструментальные тесты (если настроены):
  ```bash
  ./gradlew :V2rayNG:app:testDebug
  ./gradlew :V2rayNG:app:connectedAndroidTest
  ```
- Lint/статический анализ:
  ```bash
  ./gradlew :V2rayNG:app:lint
  ```

## 9. Тонкости темы и брендинга

В проект уже включены:
- Темы Material 3 (только тёмная тема)
- Палитра бренда (bg-dark #212529, неоновый зелёный #00FF99 и #00CC7A, тексты белый/светло-серый)
- Шрифты Inter через скачиваемые шрифты (font_certs.xml и inter.xml)
- Обновлённая разметка главного экрана, минималистичная интеграция функций
- Переименование приложения в Secure.VPN (strings.xml)

Если вы хотите изменить брендовые цвета/шрифты, правьте:
- `V2rayNG/app/src/main/res/values/colors.xml` и `values-night/colors.xml`
- `V2rayNG/app/src/main/res/values/themes.xml` и `values-night/themes.xml`
- `V2rayNG/app/src/main/res/font/inter.xml` (или добавить альтернативный шрифт)

## 10. Обновление зависимостей

- Синхронизация Gradle через Android Studio: File → Sync Project with Gradle Files
- Обновление плагинов/версий:
  - Верхний уровень: `V2rayNG/build.gradle.kts`, `settings.gradle.kts`
  - Модуль app: `V2rayNG/app/build.gradle.kts`
- После изменения версий — выполняйте clean:
  ```bash
  ./gradlew clean
  ```

## 11. Типичные проблемы и решения

- Ошибка совместимости JDK:
  - Переключитесь на JDK 17 (Android Studio → Gradle JDK)
- Не находится Android SDK/Build-Tools:
  - Установите нужные платформы и build-tools в SDK Manager
- Ошибки при подписании релиза:
  - Проверьте корректность путей keystore и переменных окружения
- Permission Denied при импорте:
  - Проверьте, что разрешения выданы (Камера/Уведомления/Доступ к файлам)
- Проблемы с QR-сканером:
  - Убедитесь, что камера доступна и разрешения выданы; протестируйте на реальном устройстве

## 12. CI/CD (опционально)

Вы можете настроить сборку в CI (GitHub Actions/GitLab CI/TeamCity):
- Установите JDK 17
- Прогоните:
  ```bash
  ./gradlew :V2rayNG:app:assembleDebug
  ./gradlew :V2rayNG:app:lint
  ./gradlew :V2rayNG:app:testDebug
  ```
- Для релизной сборки предоставьте секреты для подписания (keystore + пароли) через Secrets.

---

Если нужна автоматическая генерация релизного APK с подписанием и загрузкой артефакта в CI, или скрипт для локального релиза — могу добавить готовый GitHub Actions workflow/Gradle task по вашему запросу.