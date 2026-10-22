#!/usr/bin/env bash
# Construit NewPipe patché pour Booster Voiture.
#
# Le patch fait annoncer à NewPipe sa session audio (broadcast
# ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) au démarrage, à chaque reprise
# de lecture, puis toutes les 10 s pendant la lecture. Le booster peut ainsi
# s'y attacher comme il le fait avec Spotify.
#
# Variante "continuous" => package org.schabi.newpipe.continuous, qui coexiste
# avec la version Play Store (org.schabi.newpipe). Signée avec la clé debug.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NP="$ROOT/newpipe"
SRC="$NP/src"
PATCH="$NP/0001-boost-announce-audio-session.patch"
# Commit upstream vérifié ; le patch est écrit contre ce commit.
COMMIT="d7d6f25c031a43ec62dd37acffa5ce379d0f70cf"

fail() { echo "ERREUR: $*" >&2; exit 1; }

# --- JDK 21 (NewPipe : jvmToolchain(21)) -----------------------------------
java_is_21() { [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | grep -q 'version "21'; }

if ! java_is_21 "${JAVA_HOME:-/nonexistent}"; then
    JAVA_HOME=""
    for candidate in "${JAVA_HOME_21:-}" /home/fnavello/android-tooling/jdk-21*; do
        if java_is_21 "$candidate"; then JAVA_HOME="$candidate"; break; fi
    done
fi
[ -n "$JAVA_HOME" ] || fail "JDK 21 introuvable (définir JAVA_HOME ou JAVA_HOME_21)."
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

# --- SDK Android ------------------------------------------------------------
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[ -d "$ANDROID_HOME/platforms/android-37.0" ] \
    || fail "SDK platform 37.0 absent de $ANDROID_HOME (sdkmanager 'platforms;android-37.0')."
ls "$ANDROID_HOME"/build-tools/* >/dev/null 2>&1 \
    || fail "build-tools absents de $ANDROID_HOME (sdkmanager 'build-tools;37.0.0')."

# --- Sources + patch --------------------------------------------------------
mkdir -p "$NP/build"
if [ ! -d "$SRC/.git" ]; then
    git clone https://github.com/teamnewpipe/newpipe.git "$SRC"
fi
cd "$SRC"

git fetch --depth 1 origin "$COMMIT"
# Branche locale 'dev' obligatoire : NewPipe dérive le suffixe du package de
# `git rev-parse --abbrev-ref HEAD` ; en HEAD détaché on obtiendrait
# `.continuous.HEAD` au lieu de `.continuous`.
git checkout -f -B dev FETCH_HEAD

git apply --check "$PATCH"
git apply "$PATCH"

./gradlew assembleContinuous

APK="app/build/outputs/apk/continuous/app-continuous.apk"
[ -f "$APK" ] || fail "APK introuvable: $SRC/$APK"
cp "$APK" "$NP/build/app-continuous.apk"
echo
echo "OK: $NP/build/app-continuous.apk"
echo "Installer: adb install -r $NP/build/app-continuous.apk"
