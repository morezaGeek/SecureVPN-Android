# GitHub distribution

Repository: https://github.com/morezaGeek/SecureVPN-Android

Android has its own Git repository, independent of the Windows project. Releases contain the signed APK, checksums and the pinned native AAR required to build the source. Keep the signing identity stable and increase versionCode for subsequent updates.

History begins with a sanitized source snapshot. User profiles, subscription credentials, phone backups, tokens, signing keys and local configuration must remain outside Git. The existing release/upload signing key may differ from Google Play's app-signing key.

See README.md for downloads, migration and build instructions. Historical Play preparation documents describe past work and may not reflect the current distribution channel.
