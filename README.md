# CodeDroid

Android app for remotely controlling [opencode](https://opencode.ai) sessions from your phone.

- Pair multiple hosts and keep separate live connections
- Monitor session state, tokens, and cost
- Stream assistant output, reasoning, and tool calls
- Steer or queue follow-up prompts while a turn is running
- Approve, reject, or auto-approve permission requests
- Interrupt running turns and send file attachments
- Keep sessions alive in the background with a foreground service

Built with Kotlin, Jetpack Compose, and Material 3 Expressive.

## Quick start

CodeDroid uses the same auth model as opencode: HTTP Basic with user `opencode` and a long-lived token as the password.

### Pair a host

On the host:

```sh
opencode api post /api/pair
# {"code":"2kA2AHQP17ZesbDG-CN4Bg","expires_in":300}
```

On the phone:

- Open CodeDroid
- Tap `Pair host`
- Enter the host address and paste the code

The app exchanges the code at `GET /auth/connect/{code}` and stores the returned token.

If you prefer, you can paste the server password directly instead of generating a one-time code.

### Reach the host from your phone

By default, `opencode serve` binds to loopback. For LAN access:

```sh
opencode serve --hostname 0.0.0.0 --port 4096
```

Then pair with `192.168.x.x:4096` from the phone.

> Only pair with hosts you control. Use a trusted network or VPN/Tailscale for remote access.

## Build

Requires JDK 17+ and the Android SDK.

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

Install the APK:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release and CI

- CI runs tests and builds a debug APK
- Manual release workflow creates a signed APK and GitHub release
- If no keystore is configured, the build falls back to the debug key and warns in the logs

To build a release version:

```sh
./gradlew :app:assembleRelease -PversionName=1.0.1
```

## Tests

```sh
./gradlew :app:testDebugUnitTest
```

The test suite exercises event decoding, transcript merging, live-server behavior, file uploads, and network config checks. Live-server tests skip automatically when no host is configured.

## Security note

This app intentionally permits cleartext traffic on local network hosts because Android network security config cannot scope cleartext by CIDR ranges. For real transport security, put the host behind a TLS-terminating reverse proxy and pair over `https://` instead.

## License

MIT
