# HA Custom Widgets: OAuth, discovery и маршруты подключения

Дата: 7 октября 2026. Рабочая копия: `oauth-ha`. База: `DannyNov/HA-Custom-Widgets`, `main`, commit `d159ba3b17232f212344520739f1389f0b5a789e`.

## Статус

Локальная реализация и тестовые APK подготовлены. Коммитов, push, releases и публикации сайта не выполнялось. Версия осталась `0.8.0`, versionCode `107`, minSdk `31`, targetSdk `36`.

**OAuth ещё нельзя считать готовым к использованию конечным пользователем:** публичные client_id, callback и domain-root assetlinks на момент проверки возвращают HTTP 404. Приложение намеренно не начинает OAuth без подтверждённого Android App Link. Файлы для сайта подготовлены; их размещение требует отдельного разрешения и контроля корня домена. Обычный локальный debug APK подписан другим сертификатом и не соответствует подготовленной production-ассоциации.

Существующие LLAT-подключения работают без этой инфраструктуры и без миграции.

## Аудит фактической прежней реализации

* `MainActivity.ConnectionScreen`: ручной URL и Long-Lived Access Token; пустое поле токена сохраняло прежнее значение. Перед сохранением вызывался `/api/`, затем загружались сущности.
* `SecureConnectionStore`: `SharedPreferences` с именем `ha_connection`; `base_url` в открытом виде, `access_token` — AES/GCM ciphertext, `token_iv` — отдельный IV. Ключ `ha_widget_access_token_v1` в Android Keystore. OAuth, срока действия и refresh token не было. `allowBackup=false` уже присутствовал.
* `HomeAssistantConnection(baseUrl, token)` был единственной моделью подключения. Постоянного HA instance ID, набора адресов, discovery и resolver не было.
* `HomeAssistantClient`: REST `/api/states`, service calls; постоянный WebSocket событий и отдельный WebSocket registry. `RepairsTransport` открывал ещё один короткий WebSocket. Каждый использовал переданный URL/токен напрямую.
* Единый `AppContainer` уже обслуживал Dashboard, workers действий/обновлений/таймеров, конфигуратор и `BrightnessCoordinator`. `DashboardEventCoordinator` управлял фоновыми событиями через NotificationListenerService. `WidgetSyncWorker` и startup/recovery также работают без Activity.
* `DashboardRepository` и другие локальные хранилища отдельно сохраняют tabs, сущности, группировку, порядок, визуальные настройки, таймеры, состояния и операции. Идентификатор достоверного состояния яркости зависел от строки токена; pending timer actions привязаны к исходному URL. Это учтено в новой реализации.

## Архитектура

`HomeAssistantConnection` остаётся совместимым с прежним конструктором. Режим определяется наличием `refreshToken`: legacy LLAT или OAuth. Добавлены expiration, точный client_id, sessionId и `ServerMetadata`.

`ServerMetadata` содержит HA `instanceId`, имя, типизированные маршруты DISCOVERED/INTERNAL/EXTERNAL/CLOUD, последний успешный адрес/время и сведения native registration. Исходный `baseUrl` сохраняется как стабильный ключ старой конфигурации, а не как единственный транспортный адрес. Поэтому существующие pending timers не отбрасываются при переключении LAN/Cloud или миграции через другой известный маршрут.

`HAConnectionManager` выбирает endpoint для REST, WebSocket handshake, refresh и revoke. `AccessTokenManager` выдаёт актуальный токен и сериализует refresh. Сетевые операции не выполняются под блокировкой дискового хранилища: экран не ждёт сетевого таймаута при чтении настроек. Перед записью результата проверяется, что сессия не была удалена или заменена.

Равенство OAuth connection привязано к сессии, а не к очередной строке access token или маршруту. Это сохраняет проверки актуальности фоновых результатов. Яркость также использует стабильный sessionId вместо меняющегося access token.

## Discovery и onboarding

При первом запуске выполняется ограниченный 12 секундами поиск `_home-assistant._tcp.` через Android `NsdManager`; это механизм DNS-SD для официального `_home-assistant._tcp.local.`. Результаты разрешаются последовательно для совместимости с API 31. TXT UUID используется для сопоставления, имя — для списка, URL — для предложенного подключения. Один или несколько серверов всегда требуют выбора пользователя.

Если сервер не найден, доступны ручной адрес и повторный поиск. Пароль HA вводится только на странице своего HA в системном браузере; приложение не содержит формы логина/пароля или собственного WebView. HTTP требует явного подтверждения, поскольку передаёт credentials без TLS. HTTPS проверяется штатным trust manager и hostname verifier.

После подключения UI показывает имя, сохранённый режим входа, проверку соединения и диагностику последнего рабочего маршрута. Известный адрес не выдаётся за проверенную текущую доступность. При отсутствии внешнего маршрута предложены «Указать внешний HTTPS-адрес» и «Только локальная сеть». LLAT находится в дополнительном разделе; для старых пользователей есть «Перейти на новый способ» и «Позже».

Официальный источник discovery и native registration: [Connecting to an instance](https://developers.home-assistant.io/docs/api/native-app-integration/setup/).

## OAuth и callback

1. Выбор HA или ручной URL.
2. Проверка Android domain verification и разрешённого открытия ссылок.
3. Генерация случайного 256-bit state; encrypted pending record содержит выбранный HA URL, время и идентификатор прежней конфигурации.
4. Браузер открывает `/auth/authorize` с client_id, redirect_uri, state и response_type=code.
5. `MainActivity` принимает HTTPS App Link на холодном старте или через `onNewIntent`.
6. Проверяются точные scheme/host/port/path, отсутствие userinfo/fragment, единственность state/code, совпадение state и возраст до 10 минут. Ошибка/отмена с валидным state завершает pending flow; поддельный state не заменяет существующую конфигурацию.
7. Pending record удаляется до обмена кода, исключая повторный обмен после дубликата callback или перезапуска.
8. POST `/auth/token`, form-urlencoded, grant_type=authorization_code, code, тот же client_id и redirect_uri.
9. Проверяется доступ к `/api/config`, собираются маршруты/native metadata, затем OAuth credentials атомарно заменяют прежние.

При ошибке проверки API/записи новый grant по возможности отзывается, старый LLAT остаётся. Сетевой сбой уже использованного code требует нового входа; автоматического повторного обмена одноразового кода нет.

client_id: `https://dannynov.github.io/HA-Custom-Widgets/`.

redirect_uri: `https://dannynov.github.io/HA-Custom-Widgets/auth/callback`.

Использованы одинаковые HTTPS scheme/host/port, как требует [HA Authentication API](https://developers.home-assistant.io/docs/auth_api/). Страница дополнительно содержит link rel=redirect_uri. Custom scheme и чужой whitelisted client_id Companion не используются. Защита native redirect основана на проверенном HTTPS App Link; PKCE не заявляется как обязательная возможность всех версий HA.

`MainActivity` экспортирована для callback, имеет singleTop и узкий BROWSABLE/DEFAULT intent filter. Одного пользовательского выбора обработчика ссылки недостаточно: проверяется DOMAIN_STATE_VERIFIED. Сертификат в assetlinks сверён с существующим публичным APK v0.8.0. Правила domain-root размещения и Play App Signing описаны в [APP_LINKS.md](../infrastructure/APP_LINKS.md); основание — [Android App Links verification](https://developer.android.com/training/app-links/verify-applinks).

GitHub OAuth, GitHub permissions и аккаунт GitHub отсутствуют. GitHub Pages служит только публичной инфраструктурой владельца проекта.

## Получение адресов и identity

| Данные | Фактический источник |
|---|---|
| Начальный адрес | NSD после выбора пользователя либо ручной ввод |
| Internal URL | Аутентифицированный REST `/api/config`, поле `internal_url` |
| External URL | Тот же API, поле `external_url`; для удалённого маршрута принимается HTTPS |
| Cloud URL | Ответ POST `/api/mobile_app/registrations`, поле `remote_ui_url`; обновление через native webhook `get_config` |
| HA instance ID | Native webhook `get_config`, поле `instance_id` |
| UUID discovery | TXT `uuid`; подсказка для поиска того же сервера, не криптографическое доказательство |

`/api/config` не предполагается источником UUID. OAuth не предполагается источником внешнего URL. `homeassistant.helpers.network.get_url()` на Android не вызывается. Frontend scraping отсутствует. Источники: [официальный REST APIConfigView](https://github.com/home-assistant/core/blob/dev/homeassistant/components/api/__init__.py), [config.as_dict](https://github.com/home-assistant/core/blob/dev/homeassistant/core_config.py), [native get_config](https://github.com/home-assistant/core/blob/dev/homeassistant/components/mobile_app/webhook.py), [zeroconf broadcast](https://github.com/home-assistant/core/blob/dev/homeassistant/components/zeroconf/__init__.py).

Регистрация выполняется только при загруженном `mobile_app`, с отдельным случайным device ID. Сохраняются webhook ID и device ID. Координаты, сенсоры, push и device tracking не отправляются. Создание config entry асинхронно; начальный get_config имеет ограниченный повтор при 404. Если registration недоступна/запрещена, OAuth остаётся рабочим, но Cloud/identity не выдумываются. Доступен ручной внешний адрес. `cloudhook_url` не используется как REST/WebSocket endpoint.

Native webhook registration использует `supports_encryption=false`; webhook ID — долгоживущий секрет и хранится в том же encrypted storage. Приложение не реализует Sodium encryption. Для HTTPS транспорт защищён TLS; для HTTP доверие к LAN существенно. Без mobile_app/stable instance ID автоматическое принятие нового DHCP-адреса не выполняется.

## Маршруты, failover и фон

Порядок: последний успешный URL → external/cloud → internal → исходный/остальные известные. Рабочий внешний URL может оставаться выбранным дома. При его недоступности доступен LAN, включая конфигурации без hairpin NAT. Постоянный возврат на LAN при работающем external не требуется и не выполняется.

Reachability probe не содержит credentials, использует `/api/`, connect/read 2 с, общий предел 3 с. Результат кешируется 30 с; после сетевого отказа маршрут исключается на 60 с. Метаданные последнего успеха сохраняются с ограничением частоты записи. Поэтому каждый widget refresh не повторяет заведомо неуспешный LAN timeout.

DNS, connection refused, no-route и transport timeout допускают выбор следующего адреса. HTTP 401/403, 5xx и TLS/certificate errors не подменяются переключением адреса. Redirects с credentials запрещены. Чтения можно повторить после сетевого сбоя; service POST после неоднозначного обрыва не повторяется, поскольку действие могло выполниться. Следующая операция использует новый маршрут. Повтор POST после определённого 401 выполняется только один раз, после refresh.

При смене сети кеш сбрасывается; фоновый слой пытается обновить metadata и на Wi-Fi выполняет ограниченное rediscovery. Новый LAN URL принимается только при совпадении TXT UUID с сохранённым native instance ID и подтверждении instance_id через сохранённую native registration. Случайный другой HA не принимается. mDNS и HTTP сами по себе не защищают от злоумышленника внутри LAN; для недоверенных сетей нужен HTTPS с корректным сертификатом.

Все существующие workers получают `container.client`; транспортная логика действует без Activity, в том числе для light/switch/input_boolean/button/script/scene/timer service calls, фоновой синхронизации, событий и registry/repairs. Unit-тест воспроизводит истёкший access token + недоступный LAN + Cloud refresh + REST states без Activity. Реальные WorkManager/RemoteViews/Doze остаются проверкой на устройстве.

Выбор remote-first при наличии рабочего адреса согласуется с [Native sending data home](https://developers.home-assistant.io/docs/api/native-app-integration/sending-data/); SSID/location permissions для определения «домашнего Wi-Fi» не добавлены. При targetSdk 36 Android 16 local-network protection является opt-in; проект не включает этот режим. На Android 12–16 для используемого framework NSD дополнительных location/multicast permissions не добавлено. Будущий targetSdk 37 потребует отдельной адаптации: [Android local network permission](https://developer.android.com/privacy-and-security/local-network-permission).

## Токены, migration и logout

AES/GCM с рандомизированным IV и Android Keystore сохранён. OAuth JSON целиком шифрует access/refresh tokens и URL metadata, включая webhook ID. Pending state также шифруется. Используется синхронная атомарная запись credentials; старый формат LLAT читается тем же ключом без переименования. Backup отключён, дополнительно credentials исключены из cloud backup/device transfer. Ошибка расшифровки показана в UI и не удаляет настройки виджетов.

Refresh начинается за 60 с до истечения или после 401. Одновременные запросы используют один refresh. Новый access token сохраняется до возврата вызывающему коду; HA может не возвращать новый refresh token, тогда сохраняется прежний. Сетевой сбой/5xx не удаляет credentials. Отклонённый refresh или повторный REST 401 помечает сессию требующей входа; URL и настройки сохраняются. Коды, тела token errors и credentials не выводятся в production logs; строковые представления моделей скрывают секреты.

Миграция добровольна. LLAT заменяется только после успешного обмена, проверки API и сохранения. Tabs, card order, grouping, timers и оформление не очищаются. Отмена, неверный state, сбой exchange или API оставляют LLAT. Старый LLAT удаляется локально после успешной миграции; его серверный отзыв через OAuth не имитируется — пользователь может удалить его в профиле HA.

«Отозвать и отключить» вызывает `/auth/revoke`; для старых HA с 404/405 используется совместимый `/auth/token` + action=revoke. При ошибке revoke локальные credentials остаются для повтора. Отдельное явно выбранное «Удалить только локально» работает offline, с предупреждением о действительности серверного доступа. Удаляются connection credentials, pending state и metadata, закрывается текущий realtime transport; настройки виджетов остаются. Native mobile_app device registration на стороне HA автоматически не удаляется: отдельного unregister webhook API здесь нет, её можно удалить в HA Integrations. Это не заявляется как полный серверный отзыв webhook registration.

## Тесты и артефакты

Результаты окончательного запуска и SHA-256 APK находятся в `deliverables/oauth-validation/evidence.json`. Лог сборки: `deliverables/oauth-validation/build-oauth-final.log`. Unit HTML: `app/build/reports/tests/testDebugUnitTest/index.html`.

* PASS: 546 unit/regression tests, включая 40 новых; failures=0, errors=0, skipped=0. Подробности зафиксированы в evidence.json.
* PASS: debug APK и Android instrumentation APK собраны.
* PASS: git diff --check.
* FAIL: полный lintDebug — 54 RestrictedApi errors в неизменённых Glance-файлах; ни одной ошибки в изменённых файлах на проверенном запуске. Проверки не отключались. Отчёт: `app/build/reports/lint-results-debug.html`.
* SKIPPED: исполнение instrumentation/Keystore/реального Dashboard/Widgets — `adb devices` не обнаружил устройства; эмулятор в доступном SDK отсутствует.
* SKIPPED: реальный HA OAuth, Nabu Casa, публичный App Link и физические сетевые переходы.

Новые unit tests: state/expiry/duplicate/malformed/cancelled callback; form exchange, сохранённый pending после перезапуска, migration success/failure, cleanup нового grant при ошибке API; refresh expiry/concurrency/network failure/rejection/logout race; REST 401 retry limit, 403, redirect rejection, LLAT; WebSocket registry/repairs refresh; POST ambiguous disconnect; маршруты LAN/remote/lastWorking/cooldown/network change; background refresh через Cloud; metadata persistence; один/несколько/ноль discovery и смена IP; native registration/get_config/403/instance mismatch. Два новых host tests проверяют реальные Android Keystore/prefs и сохранение dashboard bytes, но пока только скомпилированы.

## Проверка на физическом Android

1. После отдельного разрешения разместить сайт и domain-root assetlinks; использовать тестовый APK с ассоциированным сертификатом. Убедиться, что Android действительно верифицировал домен и разрешил supported links.
2. Использовать отдельное тестовое устройство/профиль. Очистить только тестовую конфигурацию.
3. Подключиться к домашнему Wi-Fi. Проверить обнаружение одного и нескольких HA, ручной fallback и повтор поиска.
4. Выбрать сервер, пройти обычный HA login, включая MFA при наличии. Проверить холодный/тёплый callback, Back, отмену, disabled supported links и просроченный state.
5. Проверить Dashboard, tabs/grouping/order, light/switch/input_boolean/button/script/scene/timer и Android Widget.
6. Убедиться, что external/Cloud route получен; при отсутствии — явно задать свой HTTPS URL. Не считать Nabu Casa доступной без подписки/remote UI.
7. Заблокировать телефон, выключить Wi-Fi, оставить мобильную сеть. Проверить смену на external/Cloud, background worker и Widget без нового входа.
8. Вернуть Wi-Fi. Проверить продолжение через работающий external либо LAN fallback, если внешний URL дома недоступен.
9. Дождаться истечения access token, проверить один refresh при нескольких одновременных обновлениях и повтор после server-side 401.
10. Перезапустить приложение и устройство; проверить credentials/routes/lastWorking и сохранение всех настроек.
11. В отдельной LLAT-конфигурации проверить «Позже», отменённую/неудачную и успешную миграцию без изменения Dashboard/таймеров.
12. Изменить DHCP IP; проверить native instance match, непринятие другого HA и документированный fallback без mobile_app.
13. Проверить TLS certificate/hostname errors, 403, 5xx, отключённый Nabu Casa и offline режим; команды после неоднозначного сбоя не должны дублироваться.
14. Проверить revoke+disconnect, offline local-only removal и последующее удаление native device registration в HA. Проверить Android 12 и 16, OEM battery restrictions/Doze.

## Нерешённые внешние условия и ограничения

* Публикация client_id/callback и assetlinks не разрешена и не выполнена; все три URL сейчас 404.
* Корень `dannynov.github.io` — отдельная инфраструктура, его нельзя заменить содержимым project Pages. Нужен доступ владельца либо отдельный контролируемый домен.
* Для Play нужен фактический app-signing fingerprint. Подготовленная ассоциация основана на существующем публичном APK.
* Нет реального HA, Nabu Casa или Android device; end-to-end и instrumentation не подтверждены.
* При отсутствии mobile_app нельзя гарантировать native instance identity/Cloud URL; адреса не выдумываются, доступен ручной fallback. Для HTTP нет криптографической защиты LAN/mDNS; Sodium webhook encryption не реализована.
* Offline local-only logout и обычный OAuth revoke не удаляют server-side native registration; это явно отражено в UI/отчёте.
* Известный общий lint FAIL остаётся вне этой функциональности; подавление ошибок не добавлено.

## Изменённые файлы

Точный полный список исходников, тестов и инфраструктуры сохранён рядом: [OAUTH_CHANGED_FILES.txt](OAUTH_CHANGED_FILES.txt). Список не включает автоматически созданные build outputs. Логи и evidence лежат в ignored `deliverables/oauth-validation`, APK — в ignored `app/build/outputs/apk`.
