# HA timer auto-off / Автоотключение таймера HA

RC4 controls and displays the timer; Home Assistant performs the actual shutdown.
RC4 управляет таймером и отображает его; фактическое выключение выполняет Home Assistant.

Example only: replace both entity IDs with the linked timer and switch from your HA.
For a light, use light.turn_off and its light entity. Paste into an automation's YAML editor.
Только пример: замените оба entity_id на связанные таймер и выключатель своего HA.
Для света используйте light.turn_off и light-сущность. Вставьте в YAML-редактор автоматизации.
No external server was modified / Внешние серверы не изменялись.

```yaml
alias: Living Room Socket Timer - auto off
trigger:
  - platform: event
    event_type: timer.finished
    event_data:
      entity_id: timer.living_room_socket_timer
action:
  - service: switch.turn_off
    target:
      entity_id: switch.living_room_socket
mode: single
```

Cancellation also produces idle, so use timer.finished rather than any transition to idle.
Отмена также переводит таймер в idle, поэтому используйте timer.finished, а не любой переход в idle.
If HA is stopped when the timer expires, this event automation does not run on startup;
server restart recovery is a separate HA configuration concern.
Если таймер истёк при остановленном HA, эта автоматизация не запускается при старте;
восстановление после остановки настраивается отдельно на сервере.

Reference / Документация: https://www.home-assistant.io/integrations/timer/
