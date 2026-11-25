# Android-приложение «Транспорт Подмосковья»

Самостоятельное нативное приложение: **Kotlin + Jetpack Compose**. Не WebView и не обёртка сайта.

Живёт в этом же репозитории рядом с сайтом, в папке `android/`.

## Быстрый старт

```bash
cd android
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Требуется JDK 17 и Android SDK (platform 34, build-tools 34.0.0).

## Архитектура отрисовки

Карта и схема нарисованы одним `Canvas` — без Compose-компонентов на каждый
объект. Это принципиально для производительности:

```
data/lines.json ──► Model.buildNetwork() ──► MapRenderData (один раз)
                                              │  координаты в долях мира 0..1
                                              ▼
                              Canvas { drawMap() }  ← состояние читается только в draw-фазе
                                              │
        TileCache (LruCache<ImageBitmap>) ────┤  тайлы рисуются drawImage, а не AsyncImage
        кэш TextLayoutResult ─────────────────┘  подписи измеряются один раз на имя
```

* Жест меняет `zoom`/`center` в обычном объекте и инкрементит счётчик; счётчик
  читается внутри draw-лямбды, поэтому инвалидируется только перерисовка,
  а не композиция. Тайлы и подписи не пересоздаются.
* Размещение подписей пересчитывается при смене масштабного «шага», а не на
  каждом кадре; между пересчётами используются готовые варианты смещения.
* Раскладка схемы (`buildSpreadLevels`) считается в `Dispatchers.Default`,
  а не в композиции.

## Тесты

```bash
./gradlew :app:testDebugUnitTest
```

17 тестов: 14 на маршрутизацию (направления, участки, совпадение карты и текста,
сценарий «Окружная», 8 сценариев поездок, пересадки) и 3 рендер-теста
интерфейса на JVM (Robolectric + Roborazzi).

## Данные

Данные проверенного сайта лежат в `app/src/main/assets/` и работают офлайн:

* `lines.json` — 20 линий: метро (15), МЦК, МЦД D1–D4;
* `project_lines.json` — 24 проектируемых направления Подмосковья;
* `cities.json` — справочник городов;
* `scheme_layout.json` — геометрия схемы (45°/90°).

Всего 382 станции (узла), 498 записей «станция на линии».
РЖД и МЦД-5 в приложении нет — это проверяется тестом.

## Иконка и запуск

* Adaptive Icon: `res/mipmap-anydpi-v26/ic_launcher.xml`
  (foreground `res/drawable/ic_launcher_foreground.xml`, фон `#0B1220`);
* растровые версии для старых лаунчеров: `mipmap-{m,h,xh,xxh,xxxh}dpi`;
* экран запуска: `Theme.Transport.Splash` на `androidx.core:core-splashscreen`.

## Сборка релиза

```bash
cd android
./gradlew :app:assembleRelease   # нужен keystore
```
