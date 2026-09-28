# HA Custom Widgets v0.6.2-rc1

## Русский

Предварительная версия для физической проверки. Android 12+.

- Единственный тип виджета в системном списке — HA Dashboard. Отдельный виджет показателей устройства удалён.
- При обновлении Android удаляет экземпляры исчезнувшего provider. Приложение очищает их отдельные настройки, отметки синхронизации и известные кеши Glance.
- Настройки Dashboard, порядок и видимость карточек, подключение HA и таймеры сохраняются. Размеры Dashboard не изменены.

Устанавливайте APK поверх v0.6.1.1, не удаляя приложение. Нужна проверка на физическом телефоне: Dashboard после обновления, старый маленький виджет при наличии, системный picker, resize, настройки, realtime, Refresh и таймеры. Это RC, не Final; анонса в Telegram нет.

## English

Pre-release for physical phone testing. Android 12+.

- HA Dashboard is the only app widget offered by the system picker. The separate device metrics widget has been removed.
- Android removes instances of the missing provider during upgrade. The app clears their dedicated settings, sync freshness records and known Glance caches.
- Dashboard settings, card order/visibility, HA connection and timers are preserved. Dashboard resize limits are unchanged.

Install over v0.6.1.1 without uninstalling. Physical testing is required before Final: existing Dashboard, previously placed device widget if available, picker, resize/configuration, realtime, Refresh and timers. No Telegram announcement for this RC.
