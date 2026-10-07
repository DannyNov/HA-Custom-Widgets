# HA Custom Widgets v0.9.1

## Русский

- Упрощён экран подключения: один понятный статус, текущий режим соединения и основные действия.
- Резервный локальный адрес и существующий Long-Lived Access Token доступны в закрытом разделе «Дополнительно». Технические адреса и время соединения — в закрытой «Диагностике подключения».
- «Отменить ожидающий вход» появляется только при незавершённом OAuth-входе.
- Проверка доступности больше не вызывает защищённый `/api/` без credentials: устранена причина ложных уведомлений Home Assistant «Login attempt failed» от probe.
- Сохраняются возможности v0.9.0: OAuth через Home Assistant без ручного LLAT, поиск в LAN, автоматическое переключение между внешним и доверенным локальным адресом, обновление OAuth-токена. Существующие LLAT-подключения работают; переход добровольный.
- Финальный анонс проверяется до публикации и до отправки. Отсутствующий или невалидный Telegram-блок останавливает процесс.

Установите APK поверх предыдущей версии без удаления приложения. Требуется Android 12 или новее.

## English

- A simpler connection screen with one clear status, current connection mode and primary actions.
- Local fallback and legacy Long-Lived Access Token controls are in collapsed Advanced; addresses and connection time are in collapsed Connection diagnostics.
- Cancel pending sign-in appears only while an OAuth login is pending.
- Reachability checks use the public root instead of unauthenticated `/api/`, preventing probe-induced Home Assistant “Login attempt failed” alerts.
- Includes v0.9.0 capabilities: Home Assistant OAuth without manually entering LLAT, LAN discovery, automatic external/trusted LAN failover and OAuth token refresh. Existing LLAT connections continue to work; migration is optional.
- Final announcements are validated before publication and sending. Missing or invalid Telegram copy stops the process.

Install over your existing app without uninstalling. Android 12 or newer is required.

<!-- telegram:start -->
RU: v0.9.1 — понятнее экран подключения Home Assistant. Диагностика скрыта, резервный адрес и LLAT перенесены в «Дополнительно». Кнопка отмены входа появляется только когда нужна. Проверка доступности больше не провоцирует ложные «Login attempt failed» в HA.

Также из v0.9.0: вход через Home Assistant OAuth без ручного LLAT, автоматический поиск HA в локальной сети, переключение между внешним и доверенным LAN-адресом и автоматическое обновление OAuth-токена. Существующие LLAT-подключения работают, переход добровольный. Android 12+.

EN: v0.9.1 simplifies the Home Assistant connection screen. Diagnostics are collapsed; fallback address and LLAT are in Advanced. Cancel sign-in appears only when needed. Reachability checks no longer cause false HA “Login attempt failed” alerts.

Also from v0.9.0: Home Assistant OAuth without manual LLAT, automatic LAN discovery, external/trusted LAN failover and automatic OAuth token refresh. Existing LLAT connections keep working; migration is optional. Android 12+.
<!-- telegram:end -->
