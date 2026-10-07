# HA Custom Widgets v0.9.0

## Русский
- Вход через Home Assistant OAuth без ручного LLAT для новых подключений.
- Автоматическое обнаружение Home Assistant в локальной сети.
- Переключение между внешним и доверенным LAN-адресом, автоматическое обновление OAuth-токенов.
- Существующие LLAT-подключения сохраняются; миграция на OAuth добровольная.
- Android App Links и инфраструктура возврата в приложение после авторизации.
- Исправлены русские строки подключения и доверия локальному резервному адресу.

Требуется Android 12+ (API 31). Установите APK поверх предыдущей версии, не удаляя приложение. Удалённый доступ, включая Nabu Casa Cloud, зависит от конфигурации пользователя.

## English
- Home Assistant OAuth sign-in without manually creating an LLAT for new connections.
- Automatic Home Assistant discovery on the local network.
- External/trusted LAN failover and automatic OAuth token refresh.
- Existing LLAT connections remain supported; OAuth migration is optional.
- Android App Links and callback infrastructure return users to the app after sign-in.
- Corrected Russian connection and local fallback trust messages.

Android 12+ (API 31) is required. Install the APK over the existing app without uninstalling. Remote access, including Nabu Casa Cloud, depends on the user's configuration.
