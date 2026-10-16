# CodeDroid

An Android app for driving [opencode](https://opencode.ai) sessions from your phone. Pair it
with a host, watch agents work, steer running turns, and approve or deny permission requests
without touching your laptop.

Built with Kotlin, Jetpack Compose and **Material 3 Expressive**.

---

## What it does

| | |
|---|---|
| **Hosts** | Pair multiple opencode servers; each keeps its own live connection. |
| **Live sessions** | Session list with working / needs-approval / idle states, token and cost counters. |
| **Streaming transcript** | Assistant text, collapsible reasoning, and per-tool-call cards with input and output. |
| **Steer or queue** | Inject a correction into a running turn, or queue a follow-up for when it finishes. |
| **Approvals** | `once` / `always` / `reject` inline in the chat **and** as a notification with inline actions. |
| **Interrupts** | Stop a running turn from the phone. |
| **Background sync** | A foreground service holds the event streams so alerts arrive with the app closed. |

---

## Pairing

CodeDroid authenticates the way opencode expects: HTTP Basic with the user `opencode` and a
long-lived token as the password.

**On the host** — generate a single-use code (valid 5 minutes):

```sh
opencode api post /api/pair
# {"code":"2kA2AHQP17ZesbDG-CN4Bg","expires_in":300}
```

**On the phone** — open CodeDroid, tap **Pair host**, enter the host address and paste the code.

The app redeems the code at `GET /auth/connect/{code}`, stores the returned token, and never
needs the code again.

<details>
<summary>Alternative: paste the password directly</summary>

If you would rather not mint codes, switch the form to **Password** and paste the value from the
host's `~/.config/opencode/service.json` (or whatever `OPENCODE_SERVER_PASSWORD` is set to).

</details>

### Reaching the host from the phone

`opencode serve` binds to loopback by default. For LAN access:

```sh
opencode serve --hostname 0.0.0.0 --port 4096
```

Then pair with `192.168.x.x:4096` from the phone.

> The host is trusted with full read/write access to your filesystem. Only pair with servers you
> control, and prefer a trusted network or a VPN/tailscale address.

#### Why cleartext is allowed everywhere

`res/xml/network_security_config.xml` sets `cleartextTrafficPermitted="true"` on the base config.

This is deliberate, and the obvious "scoped it to private ranges" version is silently broken.
Android's `<domain>` entries match an **exact hostname or a domain suffix** — they do not
understand CIDR ranges. `<domain>10.0.0.0</domain>` matches only the literal address `10.0.0.0`,
never a neighbour on the subnet, so every other host falls through to the base config. Since the
paired server's address is not known until you type it, there is no way to scope this in advance.

`NetworkSecurityConfigTest` asserts both halves of that: cleartext is on at the base config, and no
literal-IP `<domain>` entries creep back in.

If you want real transport security, put the host behind a TLS-terminating reverse proxy with a
certificate Android trusts and pair over `https://` instead. Certificate validation is unchanged
either way — only the system trust store is used, never user-installed CAs.

Tailscale works out of the box; its `100.64.0.0/10` range was one of the things the old,
range-shaped config failed to cover.

---

## Build

Requires JDK 17+ and the Android SDK (compileSdk 37).

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

### Tests

The interesting logic here is hand-written wire handling, so the suite is pinned to recorded
server traffic rather than to assumptions:

```sh
# fixture-only: event decoding + transcript merging against captured payloads
./gradlew :app:testDebugUnitTest

# additionally exercise a real server (these skip themselves when unset)
./gradlew :app:testDebugUnitTest \
  -Dopencode.url=http://127.0.0.1:4096 \
  -Dopencode.token="$(python3 -c "import json;print(json.load(open('~/.config/opencode/service.json'))['password'])")"
```

| Suite | Covers |
|---|---|
| `EventDecoderTest` | every event type observed on a live server, plus malformed frames |
| `TranscriptTest` | replays a real 120-frame turn through the decoder and the merge |
| `LiveServerTest` | info/projects/sessions/messages decoding, SSE over a real socket, full pairing round trip |

Fixtures live in `app/src/test/resources/` and were captured from opencode `2.0.28`.
`docs/opencode-openapi.json` is the spec the models were written against.

Three bugs these tests caught, worth keeping in mind if you change the net layer:

- `/api/project` returns a **bare array** while almost every other list endpoint is wrapped in
  `{ "data": ... }` — and `GET /api/session/{id}` *is* wrapped.
- A tool's `name` arrives on `session.tool.input.started`, but its arguments arrive on
  `session.tool.called`. Treating the missing name as `""` erases it.
- Delta frames carry no timestamp and no finish reason, so entries must be stamped relative to
  the transcript and closed when `session.execution.*` reports the turn is over.

### Dependencies

Pinned deliberately:

| | |
|---|---|
| `androidx.compose.material3` | **1.5.0-beta01** — the first version shipping the Expressive component set (`LoadingIndicator`, `ContainedLoadingIndicator`, `WavyProgressIndicator`, `ToggleButton`, `FloatingToolbar`). The BOM pins 1.4.0, which has the Expressive *theming* but not those components, so this one is overridden. |
| `compose-bom` | 2026.09.00 |
| AGP / Gradle / Kotlin | 9.4.1 / 9.8.1 / 2.4.21 |
| OkHttp | 5.5.0 |
| kotlinx.serialization | 1.11.0 |

AGP 9 provides Kotlin support itself, so `org.jetbrains.kotlin.android` is not applied — only
the Compose and serialization plugins are.

> `ButtonGroup` is deliberately **not** used. In material3 1.5.0-beta01 its `content` parameter is
> typed `ButtonGroupScope.() -> Unit` without `@Composable`, so it cannot host composable children
> at all. `SegmentedSelector` in `ui/components/Common.kt` reproduces the connected-control look.

---

## How it fits together

```
core/net/OpenCodeClient      HTTP + hand-rolled SSE parsing, maps the event union to OcEvent
core/data/ServerStore        DataStore-backed list of paired hosts
core/data/OpenCodeRepository one monitor per host: reconcile, SSE, reconnect, actions
core/data/Transcript         merges REST history with live deltas into Entry/Part
core/notify/Notifier          permission + completion notifications
core/notify/ActionReceiver    replies to permissions straight from a notification action
core/sync/SyncService         foreground service that keeps connections alive
ui/…                          Compose screens, Material 3 Expressive
```

### Two things worth knowing

**The event stream is live-only.** It has no replay and no server-side buffering, so anything
that happened while the phone was offline is picked up by the periodic reconcile
(`GET /api/session` + `GET /api/permission/request`) rather than the SSE connection.

**Session events are partial.** A `session.created` payload carries no token or cost counters.
`OpenCodeRepository.merge` keeps the previous value for absent fields instead of resetting them
to zero, otherwise counters visibly reset whenever a session is touched.

---

## API surface used

Verified against opencode v2 `2.0.28`.

| Endpoint | Purpose |
|---|---|
| `POST /api/pair`, `GET /auth/connect/{code}` | pairing |
| `GET /api/info` | reachability + version |
| `GET /api/project`, `GET /api/session` | hosts and session list |
| `GET /api/session/{id}/message` | transcript history |
| `POST /api/session`, `DELETE /api/session/{id}` | create / delete sessions |
| `POST /api/session/{id}/prompt` | send (`delivery: steer \| queue`) |
| `POST /api/session/{id}/interrupt` | stop a turn |
| `POST /api/session/{id}/permission/{rid}/reply` | `{ "decision": "once\|always\|reject" }` |
| `GET /api/permission/request` | pending approvals |
| `GET /api/agent`, `GET /api/model` | agent and model pickers |
| `GET /api/event` | SSE stream |
