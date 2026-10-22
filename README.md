# SecureVPN for Android

Android VPN client for user-supplied V2Ray-compatible, SSTP and OpenConnect profiles. No VPN servers, credentials or subscriptions are bundled. Android 8.0 (API 26) or later.

## Download

Get the signed **SecureVPN 1.1.7 (12)** APK from [GitHub Releases](https://github.com/morezaGeek/SecureVPN-Android/releases/latest). The universal APK includes ARM and x86 architectures. Choose `arm64-v8a` for most current phones, `armeabi-v7a` for older 32-bit ARM phones, or `x86` / `x86_64` for matching Intel devices and emulators. If unsure, use the universal APK. Architecture-specific checksums are in `SHA256SUMS-architectures.txt`; universal APK checksums are in `SHA256SUMS.txt`.

This APK uses the existing project release/upload certificate. Google Play may use a different app-signing certificate. If Android reports incompatible signatures, export your profiles before uninstalling the Play version, then install this APK and import them. Future GitHub updates retain the same signing certificate and increase versionCode. Signing keys and passwords are never included in this repository.

## Features

- Import your own profiles and subscriptions, including QR codes.
- Profile sorting and cancellable parallel latency checks.
- Custom DNS, split tunneling, usage history and LAN HTTP/SOCKS5 proxy sharing.
- Connection notifications and Android always-on / block-without-VPN integration.
- Light and dark themes. Production builds include AdMob advertising.

Version 1.1.7 improves connection timers, traffic statistics, LAN proxy reliability and CPU power management. The setup CPU wake lock releases after successful connection; no quantified battery saving is claimed.

## Build

Install JDK 17 and Android SDK 36. Set the SDK location in an ignored `local.properties` file.

```sh
python scripts/fetch-libbox.py
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The fetch script downloads the pinned native AAR from this project's release and verifies SHA-256. Native source provenance and rebuild instructions are in `app/libs/libbox-provenance.json`, `NATIVE_16KB_BUILD.md` and `scripts/build-libbox-16kb.ps1`. Native components retain their upstream licensing requirements; refer to the linked source repositories.

Release signing uses an untracked `key.properties` with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it, release output is unsigned. Use your own signing identity for independent builds and never commit keystores or passwords.

```sh
./gradlew :app:assembleRelease
# Optional: one standalone signed APK per architecture
./gradlew -PsplitApks=true :app:assembleRelease
```

## Verification

The 1.1.7 release passed 91 JVM tests and 11 selected Android acceptance tests on a 16 KB emulator, plus minified APK startup, signature and alignment checks. See `docs/verification/android-play-1.1.7.json`. Physical connection stability was also confirmed by the maintainer; performance depends on network, profile and device.

[Privacy policy](https://securevpn.rahanetmci.com/privacy/) | [Support](https://securevpn.rahanetmci.com/support/)
