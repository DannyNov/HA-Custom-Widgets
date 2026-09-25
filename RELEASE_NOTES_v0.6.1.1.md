<!-- telegram:start -->
RU: Исправлено обновление структуры Dashboard: новые поддерживаемые устройства и сущности, переименования, удаления и переносы между помещениями подхватываются без открытия настроек. Автоматически или по кнопке Refresh.

EN: Dashboard now picks up supported new devices and entities, renames, removals and room changes without opening settings. Updates arrive automatically or via Refresh.
<!-- telegram:end -->

## Русский

Финальный HA Custom Widgets 0.6.1.1 для Android 12+. Основан на RC1, успешно проверенном пользователем на реальном телефоне: изменения Home Assistant появляются автоматически либо после ручного Refresh. Production-код, ресурсы, манифест и зависимости совпадают с RC1.

### Исправление stale catalog / structure

- Уже настроенный Dashboard получает полный актуальный каталог Home Assistant без повторного сохранения настроек. Новые поддерживаемые устройства и сущности появляются автоматически, имена устройств, сущностей, помещений и этажей обновляются.
- Удалённые объекты исключаются из отображения; переносы между area/space и изменения automation/script/scene отражаются в структуре. Эти случаи покрыты автоматическими regression-проверками.
- Сохраняются пользовательские порядок, видимость, избранное, группировка и настройки таймеров. Настройки временно отсутствующих объектов остаются для восстановления с прежним HA ID; новые карточки добавляются после существующих.
- Срок актуальности каталога — 15 минут, независимо от обновлений состояний. WorkManager запускает фоновую синхронизацию с периодом 15 минут при доступной сети; Android может задерживать выполнение. Актуальность также проверяется при включении экрана, возвращении в приложение, взаимодействиях/обновлении Dashboard и восстановлении соединения.
- Refresh запрашивает полный каталог для выбранного Dashboard, даже если 15 минут ещё не прошли, обновляет структуру и состояния. Выполнение требует сети; при ошибке предыдущая структура сохраняется.
- Сохранены единственный современный Glance renderer и server-authoritative HA timers: автоматическое выключение выполняет Home Assistant.

Установите `HAWidgets-v0.6.1.1.apk` поверх RC1 или предыдущей версии без удаления приложения.

## English

Final HA Custom Widgets 0.6.1.1 for Android 12+. Based on RC1 successfully tested by the user on a physical phone: Home Assistant changes arrive automatically or after manual Refresh. Production sources, resources, manifest and dependencies are identical to RC1.

- Fixes stale Dashboard catalog/structure without reopening or resaving settings. Supported new devices/entities are discovered and device/entity/area/floor names are updated.
- Removed objects disappear from the current display. Area/space moves and automation/script/scene changes are reconciled; these cases have automated regression coverage.
- Preserves saved ordering, visibility, favorites, grouping and timer preferences. Preferences for temporarily absent objects remain available when the same HA IDs return. New cards follow existing cards.
- Catalog freshness is 15 minutes, independent of state events. Existing WorkManager synchronization runs every 15 minutes with network connectivity, subject to Android scheduling delays. Screen-on, app resume, Dashboard interactions/updates and connection recovery also check freshness.
- Refresh requests the full catalog for the selected Dashboard immediately, bypassing catalog age, and updates structure and states. Network access is required; failures preserve the previous snapshot.
- Keeps the single modern Glance renderer and Home Assistant-owned timer auto-off.

Install `HAWidgets-v0.6.1.1.apk` over RC1 or the previous version without uninstalling.

## Build identity

- VersionName: 0.6.1.1; versionCode: 46 (RC1: 45; previous stable: 43; internal corrective: 44).
- Package: com.danila.hacustomwidgets; minSdk: 31; targetSdk: 36; debuggable=false.
- Physically approved RC: v0.6.1.1-rc1 / 7fe94a6bee9c0994161a046b73f978b37074043c.
- Unchanged app/src/main tree: 4bac524b93e0236f69b7baedeec1cdc4a8898271.
- Signing certificate SHA-256: 8bbb9530cc7954da1fc576f1174dc0dbaaec574d15c024de1ebd6a090d48037a.
- Required gates: compile/unit/regression, API 31/36 host instrumentation, API 31 upgrade from v0.6.0.3, RC6, v0.6.1 and v0.6.1.1-rc1, signed release APK/AAB, certificate/manifest and RC-to-Final source checks.
- Upgrade tests rebuild the source and target debug APKs with one certificate and verify retained application data plus instrumentation. They do not replace a physical production-APK upgrade test.
