# SecureVPN public site

Static files for the Cloudflare Pages project `securevpn-site`, published at
`https://securevpn.rahanetmci.com`.

Routes:

- `/` — product information
- `/privacy/` — bilingual privacy policy
- `/support/` — support contact
- `/app-ads.txt` — AdMob authorized seller record

To publish an update, use Cloudflare Pages > `securevpn-site` > Create deployment > Direct Upload. Upload the site files (excluding this README), then verify the Pages URL and the custom domain. The custom-domain CNAME is `securevpn` → `securevpn-site.pages.dev`; Cloudflare manages its certificate. Do not change nameservers or modify existing VPN records.

As of 2026-10-08, the Pages deployment and all four routes were checked in a browser over HTTPS. Cloudflare's dashboard can continue to show the custom domain as Verifying while DNS/certificate status settles. The public support contact is `mo.reza2011@gmail.com`.

The privacy text describes the Android source as inspected on 2026-10-08. Re-check it whenever SDKs, network providers, backup behavior, or ad consent changes.
