# SecureVPN Android 1.1.6 / code 11 — battery and lifecycle changes

Authoritative source: `scratch/SecureVPN-Android-source`. Legacy root `app/` is unchanged.

## Backup completed before implementation

- Private GitHub repository: https://github.com/morezaGeek/SecureVPN-Android
- Source commit: `bfe55bcb5f823f319711b72ffc241a8873470b12`.
- Branch: `codex/backup-android-1.1.5-before-battery-20261021`.
- Backup tag/release: https://github.com/morezaGeek/SecureVPN-Android/releases/tag/backup-android-1.1.5-20261021
- The ignored, matching 16KB `libbox.aar` is attached to that private release, with a SHA256 manifest. No user signing keys, tokens or private configurations were uploaded.
- Working branch: `codex/android-battery-lifecycle-1.1.6`.

## Implemented

- Duration uses an injected monotonic session clock; calendar history retains wall time.
- Always-on persists a profile selection, reconstructing current profile fields and settings at restore. Legacy serialized selections remain readable; a deleted profile is not replaced with another profile.
- An independent 4-second deadline covers history/ad bookkeeping, LAN and native cleanup and final notification IPC. UI can confirm the dedicated worker PID's exit without depending on a final status broadcast.
- Origin/exit-IP and connected-profile probes register sockets before connect/TLS handshake and close resources on cancellation. Platform DNS calls themselves are not made interruptible by this change.
- History checkpoints run every 30 seconds and on disconnect. Unchanged samples skip writes; idle boundaries are retained in memory; old session pruning is infrequent. A crash can lose traffic since the last checkpoint (normally up to the 30-second sampling window).
- History invalidates via package-scoped events, including time/date/timezone changes, while the screen is STARTED. Historical ranges do not reload for unrelated current-day changes.
- UI stats run at 1 second only while visibility is signalled. Resume gets a current cumulative snapshot. Internal core status callbacks remain unchanged to preserve final accounting.
- Transfer speed uses actual elapsed sample intervals, avoids duplicate snapshots and resets its baseline across UI gaps/reconnects.
- VPN/LAN network changes are observed by passive callbacks; routine 5-second LAN scanning/status broadcasts were removed. Explicit refresh still returns a current response. The bounded 128-tunnel relay is unchanged.
- Ad eligibility waits for cycle changes and one cooldown deadline instead of reading a file every second. Reservation work is serialized so later broadcasts cannot cancel an already committed reservation.
- A connected-screen test exposed a legacy Material ripple/newer Foundation clickable incompatibility. Implicit app click handlers now use the explicit indication overload; no theme or dependency upgrade was made.

## Verification and limits

Implementation and final Test APK verified on 2026-10-21. Source branch: `codex/android-battery-lifecycle-1.1.6`. The completed pre-change private GitHub backup remains available above. The final implementation revision is recorded in the delivery folder's `source-revision.json` and the branch history.

- Final offline JDK17 preview build: `testDebugUnitTest assembleRelease` succeeded. **91 JVM tests, zero failures/errors**.
- **11 selected Android acceptance tests passed** in 57.955 seconds on the owned `SecureVPN_Release_16KB_Test` emulator (page size 16384). This is the selected battery/lifecycle regression suite, not a claim that every Android instrumentation test was run. Evidence: `docs/verification/android-1.1.6.json` and `android-1.1.6-runtime.txt`.
- The core acceptance test completed five encrypted VLESS/TLS connect/disconnect cycles, active-route/DNS checks, FakeIP LAN HTTP CONNECT relay, listener closure, notification dismissal/restoration, absence of post-disconnect notification reposting, background telemetry pause and immediate/current resume statistics. The earlier failure was in the test fixture's AndroidKeyStore/Conscrypt signing path. The repaired fixture uses an EC PKCS12 identity generated at instrumentation build time, with a pinned local certificate; TLS1.2 and TLS1.3 both passed. The identity is neither committed nor packaged into the main/release APK. Production encryption policy was not weakened.
- Other acceptance checks cover current-profile Always-on reconstruction and deleted selections, checkpoint/reopen/stale-sample accounting, event-driven ad reservations, forced worker-death UI confirmation, and four network/history UI cases. An ad reservation cancellation race and a UI fixture startup race were resolved during validation.
- Final minified, signed APK: `SecureVPN-Updates-2026-10-21-1.1.6/SecureVPN-1.1.6-test-alongside-play.apk`. Package `com.rahanetmci.securevpn.preview`, versionCode11, versionName1.1.6-test. **It updates the existing SecureVPN Test app; it installs separately from Google Play production.** No uninstall/data clear is required for the compatible Test installation.
- Prior1.1.5 and final1.1.6 Test certificate SHA256 both equal `1dba6f54f5d0e8c70480c26fe373447d470f659821c6146b7329ad613880b5d4`. APK signature and zipalign -P16 verification passed. All64-bit native LOAD/RELRO alignment passed; native library bytes are identical to1.1.5 and accompanying symbols match. R8 mapping corresponds to this exact final minified APK.
- The final APK was installed with `adb install -r` over the existing preview installation on the owned emulator. Cold launch succeeded; home/history screens opened, Yesterday was present and the crash buffer was empty. The emulator's current test installation had no profiles. No user profile was bundled into the APK.
- Delivery includes APK, R8 mapping, matching native symbols, native audit, unit/runtime summaries, verification JSON and SHA256 manifest. No production AAB or Google Play upload was performed. Legacy root `app/` and Windows implementation remain unchanged.

No physical phone was attached. No measured battery percentage saving, physical overnight stability, real network handover, or new physical hotspot egress test is claimed for1.1.6. CPU/Wi-Fi wake locks, DPD, the15-second notification watchdog and internal1-second native status callback remain unchanged until repeated unplugged phone tests. History flushes every30seconds and on disconnect; a crash can lose the uncommitted interval. Platform DNS calls remain non-interruptible despite socket cancellation. Large architecture/database migrations and log queue work remain separate follow-ups.

## Next phone validation

1. Update the previous SecureVPN Test installation with the supplied APK; keep the Google Play app separate.
2. Connect your own profile, change screens, lock/unlock the device and confirm current duration, rates and history on return.
3. Use the existing hotspot HTTP proxy setup with HTTPS sharing enabled in the client and compare explicit proxied/direct IP requests. This is a proxy, not transparent routing for every hotspot app.
4. Keep comparable screen-off sessions, note battery/time/network conditions, and verify Telegram and the ongoing notification after several hours. Do not remove wake locks or relax DPD based only on emulator results.

## Rebuild / continuation environment

Authoritative Android repository: `scratch/SecureVPN-Android-source`; the root `app/` is legacy. Pin JDK17 at `C:/Users/MohammadReza/.jdks/jdk-17.0.20.1+1`; default JBR25 is unsuitable for this build. Use `.gradle-codex` as GRADLE_USER_HOME, `-PpreviewBuild=true`, and `-PdebugUploadSigning=true` when instrumentation must upgrade the signed preview. The main APK uses the existing upload/Test signing configuration. The test TLS identity is automatically generated under ignored `app/build/generated/androidTestTls/`.

Final build command: `.\gradlew.bat --offline --no-daemon -PpreviewBuild=true testDebugUnitTest assembleRelease`. Test build command additionally uses `-PdebugUploadSigning=true assembleDebug assembleDebugAndroidTest`. This host also requires `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=Z:/codex-unavailable-socket-dir -Duser.home=C:/Users/MohammadReza`.

Runtime logs and packaging helpers are under root `scratch/native-audit/`; `finalize-1.1.6.py` is rerunnable after a final build. Some source files historically mix LF/CRLF; preserve unchanged line endings. Do not rerun old one-off `scratch/edit-battery-*.py` scripts, which apply edits non-idempotently. Avoid changing native binaries or delivery symbols independently. Do not publish Google Play as a continuation of this Test-only delivery without a new request.
