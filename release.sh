#!/usr/bin/env bash
#
# Builds an Extrumz release and checks it before anyone else has to.
#
# The point is not to wrap gradle — it is to catch the three things that go
# wrong on release day and are expensive to discover afterwards:
#
#   1. No keystore. Gradle answers this by producing an *unsigned* APK and
#      reporting success, so a build that looks fine is uninstallable. Every
#      check below exists because that failure is silent.
#   2. A version that was not bumped. Shipping 1.7 twice means users get no
#      update and the tag collides.
#   3. A shrunk build that broke something. R8 runs on release only, so the
#      bug appears in the one artifact nobody tested.
#
# Usage:
#   ./release.sh                 # build, verify, leave the APKs in place
#   ./release.sh --version 1.8   # set versionName and versionCode first
#   ./release.sh --check         # verify an existing build, build nothing
#
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO"

GRADLE="./gradlew"
OUT="app/build/outputs/apk/prod/release"
PROPS="keystore.properties"
SDK_TOOLS="${ANDROID_HOME:-$HOME/android-sdk}/build-tools"
APKSIGNER="$(ls -1 "$SDK_TOOLS"/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
die()   { red "✗ $*"; exit 1; }

VERSION=""
CHECK_ONLY=false
while [ $# -gt 0 ]; do
    case "$1" in
        --version) VERSION="${2:-}"; shift 2 ;;
        --check)   CHECK_ONLY=true; shift ;;
        -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done

# ── 1. The keystore ────────────────────────────────────────────────────────────
# Absent, gradle still succeeds. It just produces something nobody can install.
bold "1/5  keystore"
if [ ! -f "$PROPS" ]; then
    red "✗ $PROPS is missing — a release built now would be unsigned."
    echo
    echo "  Create the keystore once, then copy keystore.properties.example:"
    echo
    echo "      keytool -genkey -v -keystore extrumz-release.jks \\"
    echo "          -keyalg RSA -keysize 2048 -validity 10000 -alias extrumz"
    echo "      cp keystore.properties.example keystore.properties"
    echo "      \$EDITOR keystore.properties     # storePassword= and keyPassword="
    echo
    echo "  Back the .jks up in at least two places before shipping anything."
    echo "  Losing it means no future build can update an installed app: people"
    echo "  would have to uninstall, and lose their library, to move forward."
    exit 1
fi
STORE_FILE="$(grep -E '^storeFile=' "$PROPS" | cut -d= -f2- | tr -d '[:space:]')"
[ -n "${STORE_FILE:-}" ] || die "storeFile= is empty in $PROPS"
[ -f "$REPO/$STORE_FILE" ] || die "storeFile points at $STORE_FILE, which is not on disk"
for key in storePassword keyPassword keyAlias; do
    value="$(grep -E "^$key=" "$PROPS" | cut -d= -f2-)"
    [ -n "${value:-}" ] || die "$key= is empty in $PROPS"
done
green "✓ $STORE_FILE present and $PROPS filled in"

# ── 2. Version ─────────────────────────────────────────────────────────────────
bold "2/5  version"
CURRENT_CODE="$(grep -E '^\s*versionCode = ' app/build.gradle.kts | head -1 | grep -oE '[0-9]+')"
CURRENT_NAME="$(grep -E '^\s*versionName = ' app/build.gradle.kts | head -1 | grep -oE '"[^"]+"' | tr -d '"')"
echo "     current: versionName $CURRENT_NAME · versionCode $CURRENT_CODE"

if [ -n "$VERSION" ]; then
    if [ "$VERSION" = "$CURRENT_NAME" ]; then
        die "versionName is already $VERSION — bump it, or users get no update"
    fi
    NEXT_CODE=$((CURRENT_CODE + 1))
    # In place, and only the two lines: the file is heavily commented and a
    # rewrite would lose that.
    sed -i -E "s/^(\s*versionName = )\"[^\"]+\"/\1\"$VERSION\"/" app/build.gradle.kts
    sed -i -E "s/^(\s*versionCode = )[0-9]+/\1$NEXT_CODE/" app/build.gradle.kts
    green "✓ bumped to versionName $VERSION · versionCode $NEXT_CODE"
elif [ "$CHECK_ONLY" = false ]; then
    echo "     (pass --version X.Y to bump it; the tag and the APK must agree)"
fi

# ── 3. Build ───────────────────────────────────────────────────────────────────
bold "3/5  build"
if [ "$CHECK_ONLY" = false ]; then
    # JAVA_HOME first: gradle picks the JDK from it, and this machine's default
    # is not the 21 the build wants.
    JAVA_HOME=/usr/lib/jvm/java-21-temurin $GRADLE :app:assembleProdRelease --console=plain
fi
[ -d "$OUT" ] || die "no output at $OUT — did the build run?"
green "✓ built"

# ── 4. Verify ──────────────────────────────────────────────────────────────────
# An unsigned APK is the failure this whole script exists to catch: it installs
# nowhere, and nothing in gradle's output says so.
bold "4/5  signature"
shopt -s nullglob
APKS=("$OUT"/*.apk)
[ ${#APKS[@]} -gt 0 ] || die "no APKs in $OUT"
for apk in "${APKS[@]}"; do
    name="$(basename "$apk")"
    case "$name" in
        *unsigned*) die "$name is unsigned — the keystore was not found by gradle" ;;
    esac
    if [ -n "$APKSIGNER" ]; then
        if "$APKSIGNER" verify --print-certs "$apk" >/dev/null 2>&1; then
            green "✓ $name signed"
        else
            die "$name failed signature verification"
        fi
    else
        red "! apksigner not found under $SDK_TOOLS — signature unverified"
    fi
done

# ── 5. What to publish ─────────────────────────────────────────────────────────
bold "5/5  ready to publish"
echo
for apk in "${APKS[@]}"; do
    printf '     %-46s %s\n' "$(basename "$apk")" "$(du -h "$apk" | cut -f1)"
done
echo
echo "     Publish on GitHub:"
echo "         git tag v$CURRENT_NAME && git push origin v$CURRENT_NAME"
echo "         gh release create v$CURRENT_NAME ${APKS[*]}"
echo
echo "     Give users a channel that is not GitHub as well — it is often"
echo "     unreachable from the network this app is built for."
echo
echo "     GPLv3: the release needs its corresponding source, which this"
echo "     repository is. Link the tag in the release notes."
