#!/usr/bin/env bash
# Builds a Tally release on this Mac and publishes it to GitHub Releases, where
# the in-app updater (update/AppUpdater.kt) finds it.
#
#   scripts/release.sh 1.2 "What changed, one line per change"
#
# Built here, not in CI, on purpose: Android installs an update only if it is
# signed with the same key as the app already on the phone, and that is this
# Mac's debug key. A CI build would be signed differently and refused — and the
# only way past that is uninstalling, which deletes every payment.
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION_NAME="${1:?usage: scripts/release.sh <versionName> [release notes]}"
NOTES="${2:-}"
REPO="kalki-kgp/Tally"
TAG="v$VERSION_NAME"
KEYSTORE="$HOME/.android/debug.keystore"

[[ -f "$KEYSTORE" ]] || { echo "No $KEYSTORE — this is the key the phone's copy is signed with." >&2; exit 1; }
if git rev-parse "$TAG" >/dev/null 2>&1; then echo "$TAG already exists." >&2; exit 1; fi

# ── Version ──────────────────────────────────────────────────────────────────
CODE=$(( $(sed -n 's/^versionCode=//p' version.properties) + 1 ))
sed -i '' -e "s/^versionCode=.*/versionCode=$CODE/" -e "s/^versionName=.*/versionName=$VERSION_NAME/" version.properties
echo "Building $VERSION_NAME ($CODE)…"

# ── Build ────────────────────────────────────────────────────────────────────
./gradlew -q :app:testReleaseUnitTest :app:assembleRelease
APK=app/build/outputs/apk/release/app-release.apk

# Refuse to publish anything the phone would refuse to install.
SDK=$(sed -n 's/^sdk.dir=//p' local.properties)
APKSIGNER=$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
if [[ -n "$APKSIGNER" ]]; then
  APK_CERT=$("$APKSIGNER" verify --print-certs "$APK" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
  KEY_CERT=$(keytool -list -v -keystore "$KEYSTORE" -storepass android -alias androiddebugkey 2>/dev/null \
    | sed -n 's/.*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f')
  [[ "$APK_CERT" == "$KEY_CERT" ]] || { echo "APK is not signed with $KEYSTORE; the phone would refuse it." >&2; exit 1; }
fi

# ── Assets ───────────────────────────────────────────────────────────────────
rm -rf dist && mkdir dist
cp "$APK" dist/tally-release.apk
SHA=$(shasum -a 256 dist/tally-release.apk | cut -d' ' -f1)
python3 - "$VERSION_NAME" "$CODE" "$SHA" "$NOTES" "https://github.com/$REPO/releases/download/$TAG/tally-release.apk" <<'PY'
import json, sys
name, code, sha, notes, url = sys.argv[1:]
manifest = {"platform": "android", "versionName": name, "versionCode": int(code),
            "url": url, "sha256": sha, "releaseNotes": notes}
open("dist/tally-android-update.json", "w").write(json.dumps(manifest, indent=2) + "\n")
PY

# The zipped copy is for handing over directly; a 27 MB upload times out.
rm -f tally-release-apk.zip && cp dist/tally-release.apk tally-release.apk && zip -9 -q tally-release-apk.zip tally-release.apk

# ── Publish ──────────────────────────────────────────────────────────────────
git add -A
git commit -q -m "Release $TAG"
git tag "$TAG"
git push -q origin HEAD --tags
gh release create "$TAG" dist/tally-release.apk dist/tally-android-update.json \
  --repo "$REPO" --title "Tally $VERSION_NAME" --notes "${NOTES:-Tally $VERSION_NAME}"

echo "Published $TAG. Phones pick it up on next open, or Settings → Check for updates."
