# GitHub Pages / App Links: план публикации (07.10.2026)

Статус: подготовлено локально, НЕ опубликовано. OAuth остаётся основным новым способом, LLAT сохранён. Android-код, версия, signing configuration не менялись в этой задаче.

## Решение и официальный способ

client_id = https://dannynov.github.io/HA-Custom-Widgets/
redirect_uri = https://dannynov.github.io/HA-Custom-Widgets/auth/callback

Root domain обслуживает пользовательский Pages-репозиторий DannyNov/dannynov.github.io (GitHub рекомендует lowercase имя). Это тот же тип репозитория, который ранее назывался DannyNov/DannyNov.github.io; не создавать второй, если такой уже есть. Project Pages HA-Custom-Widgets сам не может управлять /.well-known/ на корне домена. Иного способа получить этот корень из обычного project Pages нет.

Проверка существования репозитория и текущих Pages settings не завершена: GitHub API/web недоступны, прямые запросы завершились DNS-ошибкой. Это НЕ доказательство отсутствия репозитория и НЕ новое подтверждение 404. Перед публикацией проверить существующий root сайт, его ветку, CNAME и ассоциации; не перезаписывать чужое содержимое.

## Точные файлы после отдельного разрешения

1. В DannyNov/dannynov.github.io: main, /(root), Settings → Pages → Deploy from a branch. Перенести СОДЕРЖИМОЕ infrastructure/domain-root/:
   - .nojekyll (пустой файл; отключает Jekyll и сохраняет .well-known)
   - index.html (статическая служебная страница)
   - .well-known/assetlinks.json
   Существующие записи assetlinks других приложений сохранить и объединить с нашей. Не добавлять CNAME или custom-domain redirect.
2. В DannyNov/HA-Custom-Widgets: предлагаемый источник main, /docs, Deploy from a branch. Сначала проверить реальную настройку и сохранить существующий сайт/privacy policy. Обновить docs/index.html, docs/.nojekyll, docs/auth/callback/index.html, docs/auth/clear-query.js. Не копировать infrastructure/domain-root в project docs. Если сайт уже публикуется Actions, сохранить способ публикации и включить эти файлы в его артефакт; не заводить конкурирующий workflow.
3. До разрешения не создавать удалённые репозитории, ветки, commits, push, releases или Pages deployments. Локальная ветка приложения не менялась.

## assetlinks.json: точное содержимое

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.danila.hacustomwidgets",
      "sha256_cert_fingerprints": [
        "8B:BB:95:30:CC:79:54:DA:1F:C5:76:F1:17:4D:C0:DB:AA:EC:57:4D:15:C0:24:DE:1E:BD:6A:09:0D:48:03:7A"
      ]
    }
  }
]
```

Это ассоциация домена с подписью приложения; точный callback path ограничен AndroidManifest.xml, а не полем relation. Файл должен возвращать 200, application/json, без HTTP redirect.

Повторно проверено apksigner verify --print-certs:
- Локальная копия публичного v0.8.0: ../v080-final-public-downloads/HAWidgets-v0.8.0.apk; package com.danila.hacustomwidgets, versionCode 107.
- SHA-256 файла: F465098F99A062E3349A8FFB0C0F0B7E7A3E3D3D701F65EFA02590A56F7B3F3F; совпадает с ../final-public107/apk/release/HAWidgets-v0.8.0.apk.
- SHA-256 сертификата совпадает с JSON выше. DN: CN=HA Custom Widgets Debug, O=Development. Слово Debug в DN не меняет установленное совпадение с публичным APK; это не доказательство политики хранения/защиты ключа. Новый download из GitHub в этой задаче не выполнен.
- Текущий app/build/outputs/apk/debug/app-debug.apk: 2C:60:19:55:5A:92:B7:21:F3:6A:FB:AB:D0:B3:4C:A0:43:F7:BE:45:8A:FD:06:76:18:91:37:E4:48:B4:D9:71, стандартный Android Debug DN. Его НЕ добавлять в production assetlinks.
- Приватный signing key не читался и его владение не подтверждено. Для проверки нужен OAuth APK, подписанный сертификатом публичного APK; публичный v0.8.0 сам по себе не содержит новые OAuth изменения.
- Play App Signing: сертификат неизвестен, совместимость не подтверждена. Взять SHA-256 App signing certificate из Play Console, а не Upload certificate. При отличии добавить вторым fingerprint и отдельно испытать установленную из Play сборку; при ротации учесть сертификаты всех поддерживаемых установок.

## IndieAuth и callback

В первых 10 kB docs/index.html уже есть точный тег:

```html
<link rel="redirect_uri" href="https://dannynov.github.io/HA-Custom-Widgets/auth/callback">
```

HA Authentication API допускает redirect с тем же host/port; выбранные URLs также имеют одинаковую HTTPS scheme. Тег сохранён как явная декларация, хотя для same-origin случая исключение через тег не требуется. В authorize/token должен использоваться один и тот же client_id, включая завершающий /. Страница не регистрирует GitHub OAuth client и не требует GitHub login.

Manifest: autoVerify=true, VIEW/DEFAULT/BROWSABLE, https, dannynov.github.io, точный android:path=/HA-Custom-Widgets/auth/callback. Query code/state не меняет совпадение пути. Android должен передать исходный URI приложению ДО HTTP GET страницы; приложение проверяет state и обменивает code непосредственно с HA.

Callback index — только статический fallback; нет token exchange, форм, analytics, автоматического forwarding или intent/custom-scheme обхода verification. clear-query.js выполняет только history.replaceState(null, "", location.pathname), удаляя query/fragment из текущей записи истории; no-referrer и CSP ограничивают страницу.

ВАЖНО: директория auth/callback/index.html может вызвать GitHub Pages redirect с /auth/callback на /auth/callback/. Нельзя обещать 200 на slashless URI до проверки реального Pages. Канонический fallback /auth/callback/ должен отдавать 200 text/html. URI OAuth/manifest менять на него нельзя: он намеренно остаётся slashless. Для проверенного App Link HTTP redirect вообще не должен выполняться. Если браузер уже загрузил fallback, не продолжать OAuth вручную: включить поддерживаемые ссылки и начать новый вход. Не добавлять blanket pathPrefix, JS relay или захват всех ссылок домена.

Если обязательным критерием будет именно прямой 200 на slashless callback, текущую directory-index структуру нельзя считать подтверждённым решением: проверить clean-URL поведение Pages на отдельном безопасном статическом пути после разрешения. Не обещать произвольные rewrite/headers, которых Pages не предоставляет. Это не препятствует первичному Android перехвату, но остаётся HTTP-проверкой перед вводом в эксплуатацию.

## Приватность

Файлы содержат только статическую информацию; логины/пароли вводятся на HA, access/refresh tokens и entity data не отправляются Pages. JS не читает и не передаёт callback values, telemetry не добавлена.

Нельзя обещать, что GitHub никогда не увидит authorization code/state: при неудачном перехвате браузер может запросить callback с query у Pages/CDN. history.replaceState не отменяет уже отправленный запрос. GitHub также документирует журналирование IP посещений. Поэтому рабочая схема требует verified App Link; fallback не является продолжением входа. Не использовать реальные code/state в curl, отчётах, скриншотах или тестах URL. Это ограничение выбранного HTTPS callback, а не добавленный канал telemetry.

## Проверка URL после разрешённой публикации

Проверить снаружи и из сети HA, без query с секретами:
- https://dannynov.github.io/.well-known/assetlinks.json → прямой 200 application/json, без Location, нужные package/fingerprint.
- https://dannynov.github.io/HA-Custom-Widgets/ → 200 text/html, точный link rel=redirect_uri в первых 10 kB.
- https://dannynov.github.io/HA-Custom-Widgets/auth/callback → проверить GET без follow redirects, записать фактический status/Location; прямой 200 пока не подтверждён. Возможный 301/308 должен вести только на тот же host /auth/callback/.
- https://dannynov.github.io/HA-Custom-Widgets/auth/callback/ → 200 text/html (fallback).
- https://dannynov.github.io/HA-Custom-Widgets/auth/clear-query.js → 200 JavaScript.
- https://dannynov.github.io/ → 200 служебной страницы.

Для каждого выполнить curl -sS -D - -o NUL URL (Windows); отдельным curl -L проверить конечную страницу. Для assetlinks НЕ считать успешным только итоговый 200 после -L. Проверить DevTools Network на fallback: нет запросов к analytics/третьим доменам.

## Android domain verification (Android 12+, minSdk проекта 31)

Установить OAuth APK с ассоциированной подписью на тестовое физическое устройство. Не удалять данные рабочей установки ради проверки.

```text
adb shell pm set-app-links --package com.danila.hacustomwidgets 0 all
adb shell pm verify-app-links --re-verify com.danila.hacustomwidgets
adb shell pm get-app-links com.danila.hacustomwidgets
adb shell am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "https://dannynov.github.io/HA-Custom-Widgets/auth/callback"
```

После verify подождать несколько минут и повторить get-app-links: dannynov.github.io должен быть verified, link handling allowed. Последний запуск без -p/-n проверяет реальный выбор обработчика, а не принудительный запуск приложения. Без code/state приложение должно безопасно отклонить callback. Не подменять verified ручной командой установки состояния.

Settings → Apps → HA Custom Widgets → Open by default / Открывать по умолчанию → Open supported links / Открывать поддерживаемые ссылки: включено, домен подтверждён. Один ручной выбор пользователя не заменяет DOMAIN_STATE_VERIFIED. Проверить и отказ OAuth при отключённых supported links.

## Физический OAuth end-to-end

1. На тестовом профиле сохранить LLAT-подключение и виджеты; проверить, что до успешной миграции они работают. Установить новую сборку совместимой подписью, проверить version/package без изменения версии в исходниках.
2. После verified домена запустить выбор HA → вход. В браузере адрес/пароль/MFA относятся к собственному HA, GitHub login отсутствует.
3. Успешный вход возвращает непосредственно в приложение по исходному slashless URI; fallback не показывается. Проверить тёплое и холодное приложение.
4. Проверить загрузку данных/работу существующих виджетов; затем реальный refresh после expires_in, перезапуск приложения и фоновое обновление. Обмен токенами должен идти в HA, не на Pages.
5. Отменить вход, отключить supported links, проверить неверный/повторный/просроченный callback: не заменяют LLAT. Не логировать реальные code/state/token.
6. Отдельно проверить доступ через внешний адрес/Cloud и последующий возврат в LAN. Полный предыдущий аудит routing повторять не требуется.
7. Если открылся fallback, считать попытку неуспешной; исправить verification и повторить с новым state/code. Не копировать code вручную.

Эти проверки на физическом устройстве/реальном HA в этой задаче не выполнялись.

## Недоступность github.io

Новая установка/переустановка и повторная domain verification могут не подтвердить домен; новый OAuth вход/миграция могут быть недоступны. На уже verified устройстве same-origin проверка HA не обязательно требует загрузки client_id страницы, но нельзя гарантировать новый вход для всех сочетаний HA/браузера/Android/cache. Не обещать немедленное восстановление после публикации: есть кеши Pages и Android.

Существующие OAuth сессии, refresh, REST/WebSocket и виджеты обращаются непосредственно к HA; сбой Pages сам по себе не отзывает токены. При недоступном HA, revoked refresh token или необходимости повторного входа это не гарантирует работу. LLAT остаётся рабочим резервом и не зависит от Pages; миграция добровольная.

## Официальные источники

- https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages
- https://docs.github.com/en/pages/getting-started-with-github-pages/creating-a-github-pages-site
- https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site
- https://developers.home-assistant.io/docs/auth_api/
- https://developer.android.com/training/app-links/verify-applinks
- https://developer.android.com/training/app-links/troubleshoot
