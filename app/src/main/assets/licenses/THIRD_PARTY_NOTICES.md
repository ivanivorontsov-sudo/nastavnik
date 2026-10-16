# Сторонние компоненты / Third-party notices

Компоненты, которые входят в собранное приложение «Наставник». Полный текст лицензии Apache 2.0 лежит в `app/src/main/assets/licenses/Apache-2.0.txt` и показывается в приложении: «Настройки → О приложении».

## Android Jetpack (AndroidX), Jetpack Compose, Material 3
- © The Android Open Source Project / Google LLC
- Лицензия: Apache License 2.0
- Артефакты: `androidx.compose:compose-bom:2024.10.01`, `androidx.compose.ui:ui`, `androidx.compose.material3:material3`, `androidx.compose.material:material-icons-extended`, `androidx.core:core-ktx:1.13.1`, `androidx.activity:activity-compose:1.9.3`, `androidx.lifecycle:lifecycle-runtime-compose:2.8.7`, `androidx.lifecycle:lifecycle-process:2.8.7`, `androidx.work:work-runtime-ktx:2.9.1`

## Room
- `androidx.room:room-runtime:2.6.1`, `androidx.room:room-ktx:2.6.1` (и `room-compiler` при сборке), © Google LLC
- Лицензия: Apache License 2.0

## CameraX
- `androidx.camera:camera-camera2:1.3.4`, `androidx.camera:camera-lifecycle:1.3.4`, `androidx.camera:camera-view:1.3.4`, © Google LLC
- Лицензия: Apache License 2.0

## Material Components for Android
- `com.google.android.material:material:1.12.0`, © Google LLC
- Лицензия: Apache License 2.0

## Kotlin и kotlinx
- Стандартная библиотека Kotlin, `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`, `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`, © JetBrains s.r.o. и участники проекта Kotlin
- Лицензия: Apache License 2.0

## Транзитивные зависимости
Транзитивные зависимости перечисленных библиотек распространяются по Apache License 2.0.

## Сертификаты
`res/raw/ru_root.pem`, `res/raw/ru_sub.pem` — публичные корневой и выпускающий сертификаты НУЦ Минцифры России, нужны для подключения к GigaChat.

## Только для тестов (в приложение не входят)
JUnit 4 — Eclipse Public License 1.0.
