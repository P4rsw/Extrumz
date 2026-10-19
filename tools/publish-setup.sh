#!/usr/bin/env bash
#
# Getting Extrumz ready to publish: the four things that stand between this
# tree and a signed release, in the order they have to happen.
#
#     ./tools/publish-setup.sh
#
# It is safe to run more than once. Nothing here is destructive: it checks,
# it tells you what is missing, and it runs the keystore tool for you because
# that one is interactive and has to be.
#
# Two of the four need you specifically: the Telegram credentials, because
# they are registered to a phone number, and the backup, because the passwords
# should not be typed into a terminal that keeps a history.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
dim()   { printf '\033[2m%s\033[0m\n' "$*"; }
die()   { red "✗ $*"; exit 1; }

step() { printf '\n\033[1;36m── %s\033[0m\n' "$*"; }

# The credentials of Telegram Desktop, published by Telegram itself. They are in
# every fork and every tutorial, and an app that ships with them is an app
# that gets rate-limited and then banned -- for the users, not the author.
PUBLIC_API_ID="2040"
PUBLIC_API_HASH="b18441a1ff607e10a989891a5462e627"

# ───────────────────────────────────────────────────────────────── 1. api_id
step "1/4  Telegram api_id"

CURRENT_ID="$(grep -E '^TG_API_ID=' local.properties 2>/dev/null | cut -d= -f2 | tr -d ' \r')"
CURRENT_HASH="$(grep -E '^TG_API_HASH=' local.properties 2>/dev/null | cut -d= -f2 | tr -d ' \r')"

if [ "$CURRENT_ID" = "$PUBLIC_API_ID" ] || [ "$CURRENT_HASH" = "$PUBLIC_API_HASH" ]; then
    red "   ✗ local.properties still holds Telegram Desktop's public credentials."
    dim  "     Every fork ships these. Telegram bans apps that use them."
    echo
    echo "     Get your own — it takes about two minutes:"
    echo "       1. open  https://my.telegram.org  and sign in with your phone"
    echo "       2. API development tools  →  Create new application"
    echo "          (any name; platform: Android)"
    echo "       3. copy the api_id and api_hash it shows you"
    echo
    echo "     then put them in local.properties:"
    echo "       TG_API_ID=<yours>"
    echo "       TG_API_HASH=<yours>"
    echo
    echo "     re-run this script afterwards."
    echo
    API_OK=0
else
    green "   ✓ your own api_id is in place (id $CURRENT_ID)"
    API_OK=1
fi

# ─────────────────────────────────────────────────────────── 2. keystore
step "2/4  release keystore"

if [ -f extrumz-release.jks ] && [ -f keystore.properties ]; then
    green "   ✓ keystore and its properties are both here"
    KEYSTORE_OK=1
else
    KEYSTORE_OK=0
    if [ -f extrumz-release.jks ] || [ -f keystore.properties ]; then
        red "   ✗ only one of the two is here."
        dim  "     A signing config that names a keystore which is not on disk fails the"
        dim  "     release build outright. Keep whichever one you have and redo the other."
        echo
    fi
    echo "   Creating the keystore now — it will ask you for a password twice."
    echo
    if [ -x tools/make-keystore.sh ]; then
        ./tools/make-keystore.sh || die "keystore creation was cancelled"
        KEYSTORE_OK=1
    else
        die "tools/make-keystore.sh is missing or not executable"
    fi
fi

# ────────────────────────────────────────────── 3. keystore.properties
step "3/4  keystore.properties"

if [ -f keystore.properties ]; then
    MISSING=""
    for KEY in storeFile storePassword keyAlias keyPassword; do
        VALUE="$(grep -E "^$KEY=" keystore.properties 2>/dev/null | head -1 | cut -d= -f2- | sed 's/^ *//;s/ *$//')"
        if [ -z "$VALUE" ]; then
            MISSING="$MISSING $KEY"
        fi
    done
    if [ -n "$MISSING" ]; then
        red "   ✗ still empty or missing:$MISSING"
        dim  "     Edit keystore.properties and fill them in. The password you typed at"
        dim  "     keytool is the one that goes here — keytool cannot show it back."
        echo
        KEYSTORE_OK=0
    else
        STORE_FILE="$(grep -E '^storeFile=' keystore.properties | cut -d= -f2- | sed 's/^ *//;s/ *$//')"
        if [ -f "$STORE_FILE" ]; then
            green "   ✓ all four fields are filled in, and $STORE_FILE is on disk"
        else
            red "   ✗ storeFile points at '$STORE_FILE', which is not in the repository"
            KEYSTORE_OK=0
        fi
    fi
else
    red "   ✗ keystore.properties does not exist yet"
    KEYSTORE_OK=0
fi

# ───────────────────────────────────────────────────────────── 4. backup
step "4/4  backup"

BACKUP_DIR=""
for CANDIDATE in /run/media/parsa/6CC1-A83F /run/media/parsa/4EE0F882E0F87197; do
    if [ -d "$CANDIDATE" ] && [ -w "$CANDIDATE" ]; then
        BACKUP_DIR="$CANDIDATE/Extrumz-Key"
        break
    fi
done

if [ -f extrumz-release.jks ] && [ -n "$BACKUP_DIR" ]; then
    mkdir -p "$BACKUP_DIR"
    if cp -n extrumz-release.jks "$BACKUP_DIR/" 2>/dev/null; then
        green "   ✓ key copied to $BACKUP_DIR"
    else
        dim "   · key already backed up at $BACKUP_DIR"
    fi
    echo
    bold "   The two passwords are not copied anywhere, on purpose."
    dim  "     A password typed into a terminal lives in that terminal's history."
    dim  "     Put them in a password manager, or write them on paper and put the"
    dim  "     paper somewhere that is not this desk."
    echo
    dim "     The key file without the passwords is useless, and the passwords"
    dim "     without the key file are worse than useless — they are a liability."
    dim  "     Keep them in two different places."
else
    dim "   · no removable drive found, or no keystore yet — back this up yourself:"
    dim  "     cp extrumz-release.jks /path/to/your/backup/"
    dim  "     and put the two passwords in a password manager."
fi

# ──────────────────────────────────────────────────────────── summary
step "where this stands"

printf '   api_id           '; [ "$API_OK" = 1 ] && green "ready" || red "NOT YOURS YET"
printf '   keystore         '; [ "$KEYSTORE_OK" = 1 ] && green "ready" || red "not ready"
printf '   version          '; grep -E '^[[:space:]]*versionName' app/build.gradle.kts | head -1 | tr -d ' '
echo
echo "   Next, when both lines above are green:"
echo "       ./release.sh            # build signed, verify the signature"
echo "       ./release.sh --check    # verify without building"
echo
echo "   Then install the release APK and use it: R8 runs only in a release build,"
echo "   and it can remove something the debug build never would."
