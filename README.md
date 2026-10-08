# SecureVPN Android

Private Android source snapshot. Kotlin / Jetpack Compose; Android 8.0+.
Users import their own profiles; no prepared server configuration is bundled.

## Build

Install JDK17 and Android SDK35/build-tools. Set JAVA_HOME and your local SDK path in local.properties.
Run `python scripts/fetch-libbox.py`, then `./gradlew :app:testDebugUnitTest :app:assembleRelease`.
The pinned libbox AAR is fetched with SHA256 verification; it is larger than GitHub's normal file limit and is not committed.
Native OpenConnect/stoken binaries already used by this app are retained; distribution licensing and 16KB support must be addressed before a store release.

Release APK signing currently uses the existing Android debug key for compatibility with local phone tests. This is not Play signing.
Do not commit upload keystores, passwords, access tokens, user profile databases or exported subscriptions.

History begins with a sanitized 1.3.68 source baseline, followed by the 1.0 release changes. Earlier Android commit history was not present in the Windows repository.
