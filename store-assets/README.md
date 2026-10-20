# SecureVPN — Google Play assets

Prepared on 2026-10-08 for `com.rahanetmci.securevpn`, version 1.0.0 (code 1).

- `securevpn-play-icon.png`: opaque 512×512 PNG, vector-style shield/globe/check on a plain charcoal background. No baked Play corner mask or shadow.
- `securevpn-feature-graphic.png`: 1024×500 PNG using the same mark.
- Artwork is reproducible with `scripts/generate-store-assets.ps1`. It was designed with AI assistance; the two artwork assets are selected for the AI label in the Console's declaration dialog. The real screenshots are not selected for that label.
- Four actual empty-install screenshots from `screenshots/` were uploaded and attached. Play accepted their 1080×2280 dimensions in the listing review. No provider subscription or saved connection is bundled.
- `bundle-native-audit.json`: inspection of the final signed AAB's ELF load segment alignment. It does not prove 16KB runtime support.

The listing has reached Review, but its final declaration/save has NOT been submitted. A separate browser-control interruption stopped the session before the pending approvals were acted on.

The signed bundle is generated under `app/build/outputs/bundle/release/app-release.aab`. Keep signing files private and back them up separately; they are excluded from Git.
