# HA Custom Widgets v0.7.0

## Русский
- Управление цветовой температурой и цветом для поддерживаемых ламп Home Assistant.
- Контуры вокруг капсулы яркости показывают возможности лампы: температура, цвет или оба вложенных контура.
- В расширенном плавающем окне доступны яркость, цветовая температура и цвет — только при поддержке соответствующей возможности.
- Диапазон и целевые значения температуры в Кельвинах берутся из HA. Подтверждённые температура и цвет запоминаются; выбранное значение можно применить при включении выключенной лампы.
- Цветовой круг, оттенок и насыщенность используют поддерживаемые цветовые режимы и возможности, предоставляемые Home Assistant.
- Существующее управление яркостью, питанием и Dashboard сохранено.

## English
- Color temperature and color controls for supported Home Assistant lights.
- Brightness capsule contours show temperature, color, or both capabilities with nested contours.
- The expanded floating window shows Brightness, Color temperature and Color only when supported.
- Kelvin ranges and targets come from HA. Confirmed temperature and color are remembered and can be applied when turning a light on.
- The color wheel, hue and saturation use the color modes and capabilities provided by Home Assistant.
- Existing brightness, power and Dashboard behavior is preserved.

Final retains the physically approved RC4 application behavior, including the 90° temperature contour and two-line color help. Only application version metadata changes: 0.7.0 / 88. Required release gates: compile, all unit/regression tests, API31/API36 host tests, all 21 mandatory upgrade scenarios, and permanently signed APK/AAB verification.

<!-- telegram:start -->
RU: Добавлено управление цветовой температурой и цветом поддерживаемых ламп. Контуры на Dashboard показывают возможности лампы, а расширенное окно предлагает доступные настройки температуры и цвета. Диапазоны и цветовые режимы берутся из Home Assistant. Управление яркостью и питанием сохранено.

EN: Added color temperature and color controls for supported lights. Dashboard contours show each light's capabilities, and the expanded window offers its available temperature and color settings. Ranges and color modes come from Home Assistant. Brightness and power controls are preserved.
<!-- telegram:end -->
