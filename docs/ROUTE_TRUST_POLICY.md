# OAuth route ownership and discovery

`mobile_app get_config` does not provide an `instance_id`. Its `hass_device_id`
identifies the mobile_app registration's device, not the Home Assistant instance.
It is stored separately as `registrationDeviceId`; the old `instance` storage
field is retained for compatibility and is never a trust decision.

The OAuth session is the local ownership boundary. A successful OAuth exchange
and authenticated `/api/config` inspection retain the initial endpoint as
DISCOVERED. Metadata from that trusted server supplies INTERNAL (`internal_url`),
EXTERNAL (`external_url`) and CLOUD (`remote_ui_url`) routes. Null/empty internal
URLs do not relabel discovery endpoints as INTERNAL. When multiple sources name
the exact same URL, the explicit metadata kind takes precedence; distinct URLs
coexist.

mDNS TXT UUID, service name, IP, and `/api/config` descriptive properties are not
cryptographic proof of server ownership. Unknown rediscovered addresses receive
neither bearer tokens nor webhook secrets. Even a matching TXT UUID cannot
silently bind a DHCP replacement address. Previously trusted endpoints remain
available to the connection manager; an unknown address requires explicit user
confirmation or a new OAuth onboarding session. This intentionally sacrifices
automatic DHCP rebinding when a trustworthy verification mechanism is absent.

For an existing session, “Add local fallback” names the destination and asks the
user to confirm that it belongs to the same HA before sending saved access there.
HTTP disclosure on the trusted LAN is explained in that confirmation. The
authenticated config is inspected, and an available existing registration device
ID must match. This is an additional consistency check, not an instance UUID or
a replacement for user trust. The fallback is DISCOVERED unless explicit HA
metadata assigns the same URL another kind. Adding it preserves the current
lastWorking route and session credentials.

Routing keeps lastWorking first. Transport failures may fall back to another
trusted route. Restoring an earlier endpoint does not force a healthy LAN
connection back to external; a later LAN transport failure can select external.
TLS and HTTP authorization/server errors retain the existing no-failover policy.

No browser authentication data, DevTools or cookies are used by this policy.
