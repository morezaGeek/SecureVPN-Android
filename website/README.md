# SecureVPN public site

Static files for a Cloudflare Worker, to be deployed on `securevpn.rahanetmci.com`.

Routes:

- `/` — product information
- `/privacy/` — bilingual privacy policy
- `/support/` — support contact
- `/app-ads.txt` — AdMob authorized seller record

In Cloudflare Dashboard, open Workers & Pages > Create app > Upload and deploy. Upload this directory, set the Worker name to `securevpn-site`, and deploy. Verify the generated `workers.dev` URL first. Then open the Worker settings/domains and add `securevpn.rahanetmci.com` as a Custom Domain; Cloudflare manages the subdomain DNS record and certificate. Do not change nameservers or delete/modify existing VPN records. Verify HTTPS, `/privacy/`, `/support/`, and `/app-ads.txt` after activation.

As of 2026-10-08, the Cloudflare upload screen showed six files selected, but deployment and the custom hostname are not yet confirmed. Exclude this `README.md` from the public assets. The public support contact is `mo.reza2011@gmail.com`.

The privacy text describes the Android source as inspected on 2026-10-08. Re-check it whenever SDKs, network providers, backup behavior, or ad consent changes.
