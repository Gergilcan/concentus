# macOS: signing, notarization, and what the release needs

The mac build only differs from the others after compilation: Gatekeeper refuses any app that is
not signed with a Developer ID certificate AND notarized by Apple, so the release workflow does
both. Everything below is a one-time setup on the Apple Developer account (a paid membership) and
five GitHub secrets. With them in place, `git tag vX.Y.Z && git push origin vX.Y.Z` ships macOS
like any other platform.

None of this requires owning a Mac. The certificate is created with openssl, the build and
notarization run on GitHub's mac runners.

## 1. The signing certificate — secrets `MAC_CERT_P12` and `MAC_CERT_PASSWORD`

The type matters: **Developer ID Application** — the one for apps distributed OUTSIDE the App
Store. Not "Apple Development", not "Apple Distribution"; an app signed with those still gets
blocked by Gatekeeper.

```sh
# A key and a certificate signing request. The email is the Apple ID's; CN is informative only.
openssl genrsa -out developerid.key 2048
openssl req -new -key developerid.key -out developerid.csr \
  -subj "/emailAddress=gila791@gmail.com/CN=Concentus Developer ID/C=ES"
```

1. https://developer.apple.com/account → Certificates, Identifiers & Profiles → Certificates → +
2. Pick **Developer ID Application** (under Software), G2 Sub-CA is fine, upload `developerid.csr`.
3. Download the result (`developerID_application.cer`).
4. Apple's intermediate has to travel INSIDE the .p12 — GitHub's runners have no reason to know
   Apple's chain, and signing fails with "unable to build chain" without it:

```sh
# The "Developer ID - G2" intermediate, from https://www.apple.com/certificateauthority/
curl -O https://www.apple.com/certificateauthority/DeveloperIDG2CA.cer

openssl x509 -inform DER -in developerID_application.cer -out developerid.pem
openssl x509 -inform DER -in DeveloperIDG2CA.cer -out intermediate.pem
openssl pkcs12 -export -out developerid.p12 \
  -inkey developerid.key -in developerid.pem -certfile intermediate.pem
# It asks for an export password — that password is MAC_CERT_PASSWORD.
```

Secrets (repo → Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `MAC_CERT_P12` | `base64 -w0 developerid.p12` (one line, the whole file) |
| `MAC_CERT_PASSWORD` | the export password |

Keep `developerid.key` and the .p12 somewhere safe and OFF this repo. A leaked Developer ID
certificate signs malware in Concentus's name, and Apple's revocation then kills every existing
install at once.

## 2. The notarization key — secrets `APPLE_API_KEY_CONTENT`, `APPLE_API_KEY_ID`, `APPLE_API_ISSUER`

Notarization is Apple scanning the binary server-side; electron-builder submits with `notarytool`
using an App Store Connect API key (works headless, unlike an Apple ID password).

1. https://appstoreconnect.apple.com → Users and Access → **Integrations** → App Store Connect API
   → Team Keys → generate. Role: **Developer** is enough.
2. Download the `.p8` (offered exactly once) and note the **Key ID** and the page's **Issuer ID**.

| Secret | Value |
| --- | --- |
| `APPLE_API_KEY_CONTENT` | the .p8 file's text content, as is |
| `APPLE_API_KEY_ID` | the Key ID (e.g. `2X9R4HXF34`) |
| `APPLE_API_ISSUER` | the Issuer ID (a UUID) |

## 3. The Homebrew tap — secret `HOMEBREW_TAP_TOKEN` (optional channel)

1. Create a public repo `Gergilcan/homebrew-concentus`, empty.
2. Fine-grained PAT with **Contents: read and write** on that one repo → secret
   `HOMEBREW_TAP_TOKEN`.
3. Every stable release then stamps `packaging/homebrew/Casks/concentus.rb` with the version and
   dmg hashes and pushes it to the tap. Users: `brew tap gergilcan/concentus && brew install
   --cask concentus`.

Without the token the step logs a warning and skips, same as winget.

## What the workflow does with all this

Per mac runner (arm64 on `macos-latest`, x64 on `macos-15-intel`): build jar + jlink runtime +
pgvector for that arch, then electron-builder signs every binary in the bundle with the hardened
runtime and the entitlements in `apps/desktop/build/entitlements.mac.plist`, produces
`Concentus-<version>-<arch>.dmg` + `.zip`, submits to notarytool and staples the ticket. The
publish job merges the two arch update feeds into one `latest-mac.yml` (electron-updater on mac
updates from the zip — that is why it exists).

Degradation is deliberate and loud: without `MAC_CERT_P12` the build is unsigned, without the API
key it is signed but not notarized — both warn in the log and both are only good for inspecting
the artifacts of a manual `workflow_dispatch` run. A tagged release wants all five secrets.

## First-release checklist

1. The five secrets above are set.
2. `Gergilcan/homebrew-concentus` exists + `HOMEBREW_TAP_TOKEN` (or skip brew for now).
3. Tag and push. Check in the run: "notarization" appears in the mac build logs, and the release
   holds two dmgs, two zips and ONE `latest-mac.yml`.
4. On any Mac: download the dmg, open it — Gatekeeper should show the normal "verified by Apple"
   flow, no right-click-open dance. `spctl -a -vv /Applications/Concentus.app` says
   `source=Notarized Developer ID`.
