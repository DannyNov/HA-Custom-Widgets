# HA Custom Widgets v0.8.0-rc1

Русский: автоматическое пространство «Обслуживание» объединяет батареи, доступные обновления и активные неигнорируемые Repairs Home Assistant. Ключ слева от настроек открывает это пространство; красный ключ с маленьким «!» означает необходимость внимания. Батарея в процентах требует внимания только при ≤5%; 6% и выше сохраняют обычное поведение индикатора. Низкое binary-состояние также требует внимания. Цвета и иконки батарей совпадают с Dashboard. Пространство можно независимо скрыть в каждом виджете; по умолчанию оно включено, включая обновление с прежних версий. Manual refresh работает без Notification Access; realtime использует прямое HA-соединение и события реестров.

English: the automatic Maintenance space combines batteries, available updates, and active non-ignored Home Assistant Repairs. The wrench beside settings opens it; a red wrench with a small exclamation mark indicates attention. Percentage batteries require attention only at ≤5%; 6% and above do not alert. Binary low-battery states also require attention. Battery icons and colors use the existing Dashboard scale. Maintenance can be hidden independently per widget and defaults to enabled, including upgrades. Manual refresh works without Notification Access; realtime uses the direct HA connection and registry events.

RC1 limitations / ограничения:
- Read-only Repairs and Updates: perform fixes and installations in Home Assistant. RC1 does not implement HA's dynamic multi-step repair forms, ignore issues, or install updates.
- Repairs titles are requested in RU and EN from HA translations with placeholders. Missing translations use a localized generic title, never a raw issue ID. Different untranslated issues may have identical titles. Severity and HA action guidance remain visible.
- Unavailable Repairs API or permissions are shown explicitly. A previously known alert is retained during a transient read failure. Registry subscriptions depend on HA support and the existing Android realtime lifecycle; manual refresh is always available.
- Multiple battery entities for one device remain separate measurements; a low binary state can alert even when its percentage sensor is above 5%.
- Physical validation on the user's Home Assistant and phone is pending. No Final release or Telegram announcement is authorized.

Package, SDK levels and permanent signing identity are preserved. Historical releases and tags are retained. This is a public Pre-release, not Latest.
