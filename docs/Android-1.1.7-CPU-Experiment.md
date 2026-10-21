# SecureVPN CPU wake-lock experiment — 2026-10-21

Branch: `codex/android-cpu-wakelock-experiment-1.1.7`, based on stable commit `8f79a81e1324e2dc98c4136ae7819fcb9f9fb95d`. No production Play upload.

Build with `-PpreviewBuild=true -PcpuWakeLockExperiment=true` using the existing JDK17 environment. Produces signed Test package `com.rahanetmci.securevpn.preview`, version `1.1.7-test-cpu`, code12. The experiment flag is rejected unless previewBuild is enabled. Default flag-off behavior remains baseline1.1.6/code11.

Only CPU lock policy changes: connecting acquires a lock with a60-second timeout; `markConnectionStarted` releases it once the tunnel succeeds. Established sessions hold no app CPU lock. The shared setup/ready path applies to all protocols; this acceptance suite specifically exercises VLESS/TLS. Wi-Fi lock, DPD, notification watchdog, callback cadence, DNS/routing and LAN policy are unchanged for comparison.

Validation:91 JVM tests passed and11 selected Android acceptance tests passed on the isolated16KB emulator in62.714seconds. In each of five encrypted VLESS/TLS restarts the real PowerManager dump confirmed no connected-session CPU lock remained. Existing LAN/FakeIP relay, notification restoration and visibility-aware stats checks passed. Final APK signature matches the prior Test identity, zipalign -P16 passed, native LOAD/RELRO16KB passed, native library bytes match1.1.6 and matching symbols/mapping accompany the APK.

Physical phone: updated the user's Test1.1.6/code11 using `adb install -r` without uninstall/data clear. Confirmed version12/1.1.7-test-cpu and retained profiles/settings in the UI. Connected the already-selected profile via its observed Connect control; UI showed Connected and Disconnect, service was foreground with notification1, and `dumpsys power` had no active SecureVPN CPU lock. Long screen-off behavior and battery savings remain for the user's test. Do not read emulator loopback acceptance as overnight phone verification.

Delivery: root `SecureVPN-CPU-Test-2026-10-21-1.1.7/` contains APK, exact R8 mapping, matching native symbols, test summary, native audit and hashes. Private physical-phone raw captures remain only under root `scratch/battery-audit-2026-10-21/` and must not be committed/uploaded.

Next: user has started a60-minute unplugged screen-off test and will report. At return, capture `capture-comparison.py cpu-experiment-end`, compare against the latest `*-cpu-experiment-start` directory if present, and ask whether VPN, notification and Telegram remained functional. The earlier1.1.6 baseline was mixed use (39minutes screen on,19minutes off), so it is not a matched one-hour idle control. Capture statistics without reset/fake unplug or changing battery settings. Do not claim a percentage saving from unmatched runs.

Recovery must preserve data: do not uninstall or attempt to downgrade the signed release directly from code12 to11. If the user reports instability, build baseline CPU lock behavior with a higher versionCode and the same Test signing key, then upgrade in place. Stable1.1.6 source and artifacts are already preserved. No timer automation was requested.
