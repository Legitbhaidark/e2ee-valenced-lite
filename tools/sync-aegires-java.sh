#!/bin/sh
# Re-vendors the Aegires Java crypto into the plugin, so updating = running this script.
#   sh tools/sync-aegires-java.sh                 (clones the latest Aegires)
#   sh tools/sync-aegires-java.sh ~/Aegires       (uses a clone you already have)
# Only two mechanical edits are made: the package line, and the android.util.Base64 import
# (replaced by the same-package Base64.java, so the classes also run on a plain JVM for tests).
set -e
DST="plugins/aegires-e2ee/src/main/java/aegires/e2ee"
[ -d "$DST" ] || { echo "run from the repo root"; exit 1; }
SRC="$1"
if [ -z "$SRC" ]; then
  SRC="$(mktemp -d)"
  git clone -q --depth 1 https://github.com/Legitbhaidark/Aegires.git "$SRC"
fi
U="$SRC/app/src/main/java/a/b/util"
for f in Crypto Argon2id Blake2b; do
  [ -f "$U/$f.java" ] || { echo "missing $U/$f.java"; exit 1; }
  {
    echo "// Copied from the Aegires Android app: https://github.com/Legitbhaidark/Aegires ($f.java)"
    echo "// Licensed under the GNU LGPL v3 (see LGPL-NOTICE.md). Changed: package line and the Base64 import only."
    sed -e 's/^package a\.b\.util;/package aegires.e2ee;/' -e '/^import android\.util\.Base64;/d' "$U/$f.java"
  } > "$DST/$f.java"
  echo "updated $f.java"
done
git -C "$SRC" log -1 --format="Aegires commit: %h %s" 2>/dev/null || true
