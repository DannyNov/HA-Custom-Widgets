# Аудит и реализация v0.6.1

## Проверенная база

Репозиторий: DannyNov/HA-Custom-Widgets. Точный main: `5b44a64da4f5a0ad99c2132e9fe8573e240764c8`.

Прочитаны исходники этого HEAD и исходники тега `v0.6.0.3`, а также данные опубликованного релиза. Сравнение исходных деревьев: код приложения совпадает; в main изменены только README.md, README.ru.md и добавлены docs/.nojekyll и docs/privacy-policy/index.html. В обоих исходных состояниях версия 0.6.0.3/code 33, compile/target 35, min 26, Glance 1.1.1, AGP 8.7.3.

В исходном DashboardWidget нет глобальной LoadingCard/«Загрузка…». Локальный кэш загружается до provideContent; список уже использует стабильные itemId. Яркий active timer blue `#2196F3` уже присутствует. Для RC1 About использует точный пользовательский HA-Custom-Widgets-Telegram-1024.png, скопированный без изменения байтов в res/drawable-nodpi/ha_custom_widgets_brand.png. SHA-256: 7FCEB1CC05DF3650708390496F819D227FC6CCAFBFB34C3C620B5DE2BE8B2DE1. Прежний drawable ic_home_assistant остаётся launcher icon.

## Фактический pipeline до исправлений

1. DashboardControlAction → repository.beginOperation: сохраняет optimistic overlay и операцию, публикует StateFlow и запрашивает render. Затем ставит DashboardActionWorker и вызывает wakeAsync с возможным общим reconciliation.
2. Worker записывает RUNNING отдельной revision/render, вызывает HA service, затем опрашивает сущность через REST с интервалом 500 мс до подтверждения/deadline. Таймерный worker отправляет команды устройству и HA timer, затем получает их состояния.
3. DashboardEventCoordinator ведёт общий WebSocket; EVENT/initial state и REST reconciliation попадают в тот же updateEntityStates.
4. StatePolicy отклоняет старые timestamp, но одинаковый принятый payload раньше тоже повышал revision. Atomic state store записывал весь record даже без изменения.
5. commitAndRequestRender сразу публиковал загруженный DashboardState и одновременно запрашивал DashboardRenderCoordinator. Coordinator сериализовал update по revision, но без временного объединения. DashboardWidget собирает StateFlow внутри Glance provideContent; вызов Glance update работает через существующую сессию либо запускает новую.
6. Timer presentation уже считала finishes_at минус Instant.now(), но следующий локальный пересчёт никто не планировал. Остаток менялся только при новой композиции.

## Реализованный план

- Одно место публикации — render coordinator с окном объединения 180 мс. Совпадающие payload не запрашивают visual revision; новый timestamp сохраняется для защиты от устаревших событий. Подтверждение активной операции не теряется при совпадающем payload. PENDING/RUNNING — только служебные изменения; их UI одинаков.
- Кэш не очищается при refresh, refresh flag не участвует в визуальном состоянии. Навигация и collapsed sections сохраняются в настройках. Неизменившиеся карточки повторно используют прежние объекты. У power-кнопки постоянная структура Image + Text, а не смена типа дочернего элемента.
- Активная Glance-сессия получает StateFlow publication; явный update нужен холодной сессии. Убрано лишнее общее REST reconciliation при action wakeup, поскольку worker уже согласует затронутые сущности.
- Уникальная цепочка WorkManager на виджет обновляет countdown не чаще обычного минутного шага, с отдельным финальным обновлением у истечения. Нет точных будильников, foreground service, посекундного цикла или локальной команды выключения устройства. По окончании/паузе цепочка прекращается. HA остаётся источником истины для управления.
- Для отсутствующего/некорректного finishes_at остаток привязывается к last_updated либо первому локальному получению. Одинаковый REST-снимок без timestamp не продлевает countdown.
- Овальные drawable для кнопок, текстовые батареи RU/EN, About и scenes. Schema структуры поднята с 5 до 6, чтобы существующие настройки получили сцены при следующем catalog reconciliation. Scene visibility/run настраиваются индивидуально, существующие переключатели automation/script сохраняются.
- 0.6.1/code 34; compile/target 36, min 26. AGP 8.9.1 + Gradle 8.11.1, обновлены версии Gradle в двух существующих workflows, добавлен wrapper. Signing configuration не изменена.

Основание выбора инструментов: официальная [таблица совместимости Android Gradle Plugin](https://developer.android.com/build/releases/about-agp) указывает AGP 8.9.1 как минимум для API 36, а Gradle 8.11.1 — для AGP 8.9. Проверены также исходники используемой Glance 1.1.1: на API 26–31 adapter intent зависит от widget ID, view ID и размера, имеет stable IDs; корневая layout-конфигурация не включает дочерние элементы LazyList. Доступного публичного Glance API для гарантированной точечной замены одной карточки на любом launcher нет.

## Выполненные проверки

Финальная команда (JDK 17, локальный Android SDK):

```text
gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease --no-daemon --console=plain
```

Результат: BUILD SUCCESSFUL. 376 тестов, 0 failures/errors/skipped. Добавлено 12 тестов: payload deduplication, подтверждение операции и отклонение старого state, кэш и стабильность ID, timer remaining/fallback/paused/expiry/120 минут, ограничение и остановка тиков, battery aliases/пороги/RU-EN, scenario policy, About URLs.

Первый прогон обнаружил две регрессии классификации батарей; они исправлены без ослабления существующих тестов. Повторный и заключительный прогоны успешны. Release vital lint прошёл. Сохраняются прежние предупреждения о menuAnchor и условии в ReorderableList; редизайн и изменение размеров шрифта не выполнялись.

APK manifest подтверждает application ID `com.danila.hacustomwidgets`, versionName `0.6.1`, code `34`, min `26`, target/compile `36`, без debuggable. У включённой нативной библиотеки `libandroidx.graphics.path.so` все LOAD-сегменты имеют alignment 16384 во всех четырёх ABI; проверка APK zipalign для 16-КБ страниц пройдена.

## Практические ограничения и следующий шаг

Результат — локальный кандидат на релиз, не опубликованный стабильный релиз. На физических Honor/Wileyfox и на современном устройстве запуск не выполнялся; визуальное сохранение scroll и формы кнопок требует проверки в их launcher. WorkManager может задерживать минутные тики при Doze/EMUI power management. Glance продолжает формировать RemoteViews для виджета, а не гарантированную частичную host-side замену одной карточки. Уменьшено число лишних публикаций, сохранена идентичность списка; абсолютное отсутствие OEM redraw не заявляется.

APK/AAB не подписаны и не подходят для установки поверх подписанного v0.6.0.3 или загрузки в Play без обычного шага подписи владельцем. Signing secrets не просматривались и не изменялись. Коммиты, push и публикации не выполнялись.

Исходники получены архивом точного SHA: установленный Git не имеет remote-https. Локальный Git index содержит базовое дерево для показа diff, но история/HEAD не созданы. Патч предназначен для применения поверх указанного main; это не отдельный коммит. В Linux wrapper можно запустить через `bash gradlew`.
