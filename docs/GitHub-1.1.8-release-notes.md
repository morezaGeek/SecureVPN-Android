# SecureVPN 1.1.8

- Check for updates in Settings. See GitHub release notes, download the APK for your device, and install through Android's installer.
- SecureVPN checks for updates about once a day and can notify you when one is available. Downloads start only when you choose, show progress, and can be canceled.
- Redesigned profile cards with square corners, monospace text, and a more compact layout. Long-press a profile to edit, share, or delete it.
- Ping appears at the top right: blue below 100 ms, green through 160 ms, yellow through 270 ms, red above 270 ms. XHTTP mode details remain in settings.
- Keeps the CPU awake while connected in this build to help reduce disconnects with the screen off; this may increase battery use.

Build for GitHub: `./gradlew -PgithubUpdates=true -PconnectedCpuLock=true -PsplitApks=true :app:assembleRelease`.
The updater expects stable tags such as `v1.1.9`, a higher Android version code, and APKs named `SecureVPN-1.1.9-arm64-v8a.apk` (or the relevant ABI), with `SecureVPN-1.1.9.apk` as the universal fallback. Use the existing signing identity. The GitHub release description is shown as the changelog in the app.
