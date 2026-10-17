#!/usr/bin/env bash
#
# Creates the Extrumz release keystore, once, and fills in keystore.properties.
#
# Run this yourself, in your own terminal:
#
#     ./tools/make-keystore.sh
#
# It asks for a password twice — once for the keystore, once for the key — and
# nothing is echoed, stored in this script, or written anywhere but the two
# files it creates. Do not put the password in a command line or in a chat
# message: it would land in your shell history.
#
# ── Read this before you start ────────────────────────────────────────────────
#
# The keystore is the single most valuable file in the project, and it is the
# only one that cannot be regenerated. An Android app's identity *is* its
# signing key: a new key signs a different app, and neither the store nor a
# phone will accept it as an update to the old one. Lose it and every installed
# copy must be uninstalled before it can be replaced — taking the user's whole
# library with it.
#
# So: back up the .jks and the passwords in at least two places that are not
# this machine. Do that today, not before the next release.
#
# Losing the passwords is the same as losing the file. There is no recovery,
# no support address, and no amount of money that fixes it.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

KEYSTORE="extrumz-release.jks"
ALIAS="extrumz"
PROPS="keystore.properties"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
die()   { red "✗ $*"; exit 1; }

command -v keytool >/dev/null || die "keytool not found — install a JDK first"

if [ -f "$KEYSTORE" ] || [ -f "$PROPS" ]; then
    red "Something is already here:"
    [ -f "$KEYSTORE" ] && echo "    $KEYSTORE"
    [ -f "$PROPS" ]    && echo "    $PROPS"
    echo
    echo "Refusing to overwrite. If the existing key is the one you have been"
    echo "shipping with, keep it — a replacement cannot update those installs."
    echo "If you are certain it is disposable, move it aside and run again."
    exit 1
fi

bold "Creating $KEYSTORE"
echo
echo "  You will be asked for:"
echo "    • a keystore password (you may press Enter to reuse the key password)"
echo "    • your name and organisation — these end up inside the certificate"
echo "      and are shown to users on install, so use something you are happy"
echo "      for them to read; leaving them blank is allowed and common"
echo "    • a key password"
echo
echo "  Nothing is echoed. Write the passwords down before you continue."
echo
read -r -p "Press Enter to start, or Ctrl-C to stop. "

# -validity 10000: about 27 years. Google Play requires a key to outlive the
# app, and a key that expires stops signing updates while the app is still
# installed.
keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000

green "✓ keystore created"

# ── keystore.properties ───────────────────────────────────────────────────────
# The passwords are not read back out of keytool here: it cannot return them,
# and asking for them again to echo into a file is how they end up in the wrong
# places. The file is written with blanks and opened for the user to fill in
# with an editor that does not keep a history.
cp keystore.properties.example "$PROPS"

cat <<EOF

$(bold "Next: fill in $PROPS")
EOF
cat <<EOF
    storeFile=$KEYSTORE
    storePassword=      ← the keystore password you just typed
    keyAlias=$ALIAS
    keyPassword=        ← the key password you just typed

    \$EDITOR $PROPS

Both files are gitignored, and must stay that way. A committed password is a
password, and this one signs every future build.

$(bold "Then back both up — off this machine")
    • $KEYSTORE
    • the two passwords

Two copies, two places. A disk fails, a house does not.

$(bold "Finally")
    ./release.sh --check     # verifies they are wired up correctly
EOF
