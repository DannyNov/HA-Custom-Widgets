# v0.6.1-rc4 audit

Base: test/v0.6.1-rc3, d906f159b47e5ac3a7d2acad97e9780482954a55.
Candidate: test/v0.6.1-rc4, versionName 0.6.1, versionCode 37. No main/tag/release/Play changes.

## Timer root cause found in RC3 code

DashboardRepository.updateEntityStates accepted fresh states through TimerResetPolicy.stale,
but only set confirmedHa for an active timer when TimerResetPolicy.confirms matched the
LOCAL requested duration. A different client's duration could be persisted in atomic state
while loadState still applied the LOCAL optimistic deadline/preset. accepted=true removed
any timeout from that overlay; it could survive for the whole run. A second failure window
was a WS acknowledgement before the HTTP worker marked accepted: !reset.accepted made
fresh server states stale. This is a code-reproduced failure mechanism; without logs from
the two physical phones it is not proof that every observed incident had this exact history.

RC4 separates server truth from local command identity. Any server state passing ordering
retires the overlay regardless of requested duration or HTTP acknowledgement order. Newer
server timestamp wins. A valid changed finishes_at also wins at equal/missing timestamp
when there is a known prior deadline; explicitly older timestamps are still rejected. A
server deadline continues to precede derived remaining snapshots. The local overlay has a
120-second maximum lifetime even when the HTTP start succeeded. Command identity is kept
to prevent an accepted start from being repeated just because its follow-up REST read failed.
A visual revision is required when an overlay retires even if the underlying payload already
matches the cache. selectedDurationIndex is a fallback; active preset/tap policy uses HA duration.

REST manual refresh, WS EVENT and reconciliation all call updateEntityStates. The compressed
parser's lu/lc fallback remains unchanged. Tests cover both sources and actual repository
persistence/presentation with isolated client preferences, plus old snapshot rejection.

## Auto-off

Removed the client expiry decision and network execution. No new expiry work/alarms are
scheduled. Worker and receiver class names remain as inert compatibility tombstones so
previously persisted callbacks cannot shut down a device or enqueue new jobs. One-time
startup migration cancels the default worker-class tag (including orphan work), both unique
work names and known alarm PendingIntents, then clears legacy timer-run ownership. It does
not repeat on later starts and erase new optimistic requests. Orphan alarms without a store
record can at most reach the inert receiver. Settings and RU/EN README now require HA automation.
The demo/main HA were not changed. docs/TIMER_AUTO_OFF.md contains an example only.

Six obsolete expiry-execution unit tests were replaced with six migration/architecture
regressions; all other existing tests retained. New repository instrumentation tests check
actual persistence and WorkManager/alarm cleanup. Physical end-to-end delivery still required.

## Scroll audit

RC3 already coalesces renders for 180ms, compares visible payloads, ignores timestamp-only
changes for visual revisions, publishes to an active StateFlow session and calls full Glance
update only for a cold session. Countdown and operation/status updates can legitimately change
visible content. No further proven safe suppression was found; RC4 leaves renderer/update
routing unchanged. Old OEM launcher/Glance scroll jump is a known limitation, not a release
blocker. Do not re-enable RC2 custom ListView path. Correct UI and clicks take precedence.

Power/timer geometry, temp/humidity drawables/colors, battery aliases/colors, scene play-only,
About/logo, compact cards, minSdk26/targetSdk36 and countdown code are unchanged.

## Physical acceptance

Install over RC3 on both old phones with realtime enabled. After 3 minutes change 30 -> 60
on A; B must immediately show HA's duration and remaining. Repeat B -> A, then 120 minutes,
manual refresh, reconnect and process restart. After >=60 seconds first tap resets the current
preset, next quick tap advances. Compare same server deadline within minute rounding. Test
normal clicks, metrics, scene, countdown expiry and HA automation independently of the phone.
Record scroll jumps separately. CI emulators do not replace this OEM acceptance.
