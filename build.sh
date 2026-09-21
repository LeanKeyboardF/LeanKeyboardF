#!/usr/bin/env bash
#
# build.sh <versionName> [flavors]
#
# Example:
#   ./build.sh 6.2.0
#   ./build.sh 6.2.0 origin
#
# What it does:
#   1. On the very first run: generates a real release keystore
#      (keystore/release.keystore.jks) with random passwords,
#      stored in keystore/keystore.properties next to it.
#   2. On every run after that: reuses the same keystore, so all
#      builds are signed with the same key.
#   3. Builds a signed release for every flavor requested (playstore,
#      origin by default):
#        - playstore builds ONE .aab bundle (for uploading to Google
#          Play) covering every language - Play's own delivery already
#          avoids sending users languages they don't need.
#        - origin builds one APK per language actually present in the
#          project (LeanKeyboardF_v..._r.apk = English only,
#          LeanKeyboardF_EN+UA_v..._r.apk = English + Ukrainian, etc),
#          detected automatically from src/main/res/values-<code>
#          folders - see leankeykeyboard/build.gradle. This app has no
#          native code, so the old per-CPU-architecture APK splits it
#          used to build were all essentially the same size - no real
#          benefit, unlike splitting by language.
#   4. Copies every resulting APK/AAB into releases/<version>/.
#
# keystore/ is git-ignored on purpose - BACK IT UP YOURSELF.
# If you lose it you can never sign an update with the same
# signature again (Play Store, sideload upgrades, etc).
#
# NOTE: with two flavor dimensions (distribution x locale), AGP's
# per-flavor convenience aggregate tasks (e.g. "assembleOriginRelease")
# only exist for the "assemble" verb, NOT for "bundle" - there is no
# "bundlePlaystoreRelease". Since variantFilter (see leankeykeyboard/
# build.gradle) restricts "playstore" to the "all" locale flavor only,
# the playstore bundle task is hardcoded below to the one variant that
# can ever exist: bundlePlaystoreAllRelease. If a task isn't found, run
# `./gradlew tasks --all` to see the exact task names actually
# available and adjust the mapping below.

set -euo pipefail

VERSION_NAME="${1:-}"
FLAVORS="${2:-playstore,origin}"

if [[ -z "$VERSION_NAME" ]]; then
    echo "Usage: ./build.sh <versionName> [flavors]"
    echo "Example: ./build.sh 6.2.0"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYSTORE_DIR="$SCRIPT_DIR/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/release.keystore.jks"
KEYSTORE_PROPS="$KEYSTORE_DIR/keystore.properties"
VERSIONCODE_FILE="$KEYSTORE_DIR/versioncode.txt"

random_password() {
    # openssl produces a fixed, finite amount of output, so head never has
    # to cut off a still-writing producer - unlike piping the endless
    # /dev/urandom stream through `tr | head`, which trips SIGPIPE
    # ("broken pipe") and, combined with `set -o pipefail` above, aborts
    # the whole script even though the password itself was generated fine.
    openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 24
}

# -------------------------------------------------------------
# 1. First run: generate a real signing key
# -------------------------------------------------------------
if [[ ! -f "$KEYSTORE_FILE" ]]; then
    echo "[build] No release keystore found - generating one now (first run)..."
    mkdir -p "$KEYSTORE_DIR"

    # PKCS12 (the default keystore format on modern JDKs, used even for
    # files named *.jks) requires the key password to match the store
    # password - keytool silently ignores a different -keypass, so using
    # two different random passwords here produced a keystore whose real
    # key password didn't match what we wrote to keystore.properties.
    STORE_PASS="$(random_password)"
    KEY_PASS="$STORE_PASS"
    KEY_ALIAS="leankeyboardf"

    keytool -genkeypair -v \
        -keystore "$KEYSTORE_FILE" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
        -dname "CN=LeanKeyboardF, OU=Release, O=LeanKeyboardF, L=Unknown, S=Unknown, C=UA"

    cat > "$KEYSTORE_PROPS" <<EOF
storeFile=release.keystore.jks
storePassword=$STORE_PASS
keyAlias=$KEY_ALIAS
keyPassword=$KEY_PASS
EOF

    echo "1" > "$VERSIONCODE_FILE"

    echo "[build] Keystore created: $KEYSTORE_FILE"
    echo "[build] Passwords saved in: $KEYSTORE_PROPS"
    echo "[build] BACK UP the whole 'keystore' folder somewhere safe now."
    echo "[build] Losing it means you can never sign an update with the same key again."
else
    echo "[build] Reusing existing keystore: $KEYSTORE_FILE"
fi

# -------------------------------------------------------------
# 2. Auto-incrementing versionCode
# -------------------------------------------------------------
VERSION_CODE="$(cat "$VERSIONCODE_FILE" 2>/dev/null || echo 1)"
NEXT_VERSION_CODE=$((VERSION_CODE + 1))
echo "$NEXT_VERSION_CODE" > "$VERSIONCODE_FILE"

echo "[build] versionName=$VERSION_NAME  versionCode=$VERSION_CODE"

# -------------------------------------------------------------
# 3. Build every requested flavor
# -------------------------------------------------------------
# With two flavor dimensions (distribution x locale), AGP 8.5 does NOT
# generate convenience aggregate tasks like "assembleOriginRelease" or
# "bundlePlaystoreRelease" - only exact per-combo tasks exist
# (assembleOriginEnRelease, assembleOriginEnUkRelease, ...). "playstore"
# always resolves to exactly one combo (locale "all", enforced by
# variantFilter in leankeykeyboard/build.gradle), so it's safe to
# hardcode. "origin" fans out over every non-"all" locale flavor - and
# that set grows every time a translation is added - so instead of
# hardcoding or parsing "gradlew tasks" text output (whose format isn't
# stable/flat), ask the build itself via the ciListVariants hook in
# leankeykeyboard/build.gradle, which prints exact variant names from
# the real Variant API.
echo "[build] Querying Gradle for release variants..."
CI_VARIANTS="$("$SCRIPT_DIR/gradlew" :leankeykeyboard:help -PciListVariants=true \
    -PappVersionName="$VERSION_NAME" -PappVersionCode="$VERSION_CODE" \
    --console=plain -q 2>/dev/null | grep '^CI_VARIANT::' || true)"

if [[ -z "$CI_VARIANTS" ]]; then
    echo "[build] ERROR: got no variant list from Gradle (ciListVariants hook)." >&2
    echo "[build] Run './gradlew :leankeykeyboard:help -PciListVariants=true' by hand to debug." >&2
    exit 1
fi

TASKS=()
IFS=',' read -ra FLAVOR_LIST <<< "$FLAVORS"
for f in "${FLAVOR_LIST[@]}"; do
    if [[ "$f" == "playstore" ]]; then
        TASKS+=("bundlePlaystoreAllRelease")
        continue
    fi

    # Each line: CI_VARIANT::<variantName>::<flavor1,flavor2,...>
    # Keep variants whose flavor list contains exactly "$f".
    mapfile -t variant_names < <(awk -F'::' -v want="$f" '
        $1 == "CI_VARIANT" {
            n = split($3, flavs, ",")
            for (i = 1; i <= n; i++) {
                if (flavs[i] == want) { print $2; break }
            }
        }
    ' <<< "$CI_VARIANTS")

    if [[ ${#variant_names[@]} -eq 0 ]]; then
        echo "[build] ERROR: no release variant matches flavor '$f'." >&2
        echo "[build] Variants Gradle reported: $CI_VARIANTS" >&2
        exit 1
    fi

    for name in "${variant_names[@]}"; do
        # Task name = "assemble" + variant name with its first letter
        # capitalized (variant.name is already e.g. "originEnRelease").
        TASKS+=("assemble$(tr '[:lower:]' '[:upper:]' <<< "${name:0:1}")${name:1}")
    done
done

echo "[build] Running: gradlew ${TASKS[*]} -PappVersionName=$VERSION_NAME -PappVersionCode=$VERSION_CODE"

# Run in small batches rather than one giant "gradlew t1 t2 t3 ... tN"
# call. With two flavor dimensions this list grows by one assemble task
# (and one R8 invocation) per locale added, all inside a single Gradle
# daemon JVM if run as one invocation - that's what caused the R8
# OutOfMemoryError in CI (see gradle.properties for the heap bump). Batching
# re-invokes gradlew every $BATCH_SIZE tasks, so the daemon/heap gets
# reused across a bounded number of R8 runs instead of growing without
# limit as more languages get added. Lower BATCH_SIZE (env var) if it
# still OOMs; raise it for faster local builds once you've confirmed
# headroom.
BATCH_SIZE="${BATCH_SIZE:-6}"
for ((i = 0; i < ${#TASKS[@]}; i += BATCH_SIZE)); do
    batch=("${TASKS[@]:i:BATCH_SIZE}")
    echo "[build] Batch $((i / BATCH_SIZE + 1)): ${batch[*]}"
    "$SCRIPT_DIR/gradlew" "${batch[@]}" -PappVersionName="$VERSION_NAME" -PappVersionCode="$VERSION_CODE"
done

# -------------------------------------------------------------
# 4. Collect the APKs/AABs
# -------------------------------------------------------------
OUT_DIR="$SCRIPT_DIR/releases/$VERSION_NAME"
mkdir -p "$OUT_DIR"

for f in "${FLAVOR_LIST[@]}"; do
    if [[ "$f" == "playstore" ]]; then
        # Bundle output lives under outputs/bundle/<variant>/, not
        # outputs/apk/ - variant folder name includes the locale flavor
        # too (e.g. playstoreAll), so glob broadly.
        for bundle_dir in "$SCRIPT_DIR"/leankeykeyboard/build/outputs/bundle/playstore*/; do
            [[ -d "$bundle_dir" ]] && cp "$bundle_dir"/*.aab "$OUT_DIR/" 2>/dev/null || true
        done
    else
        # With two flavor dimensions, the folder name is the
        # distribution+locale combo (e.g. originEn, originEnUk...), not
        # just "origin" - glob every locale variant for this
        # distribution.
        for apk_dir in "$SCRIPT_DIR"/leankeykeyboard/build/outputs/apk/"$f"*/release/; do
            [[ -d "$apk_dir" ]] && cp "$apk_dir"/*.apk "$OUT_DIR/" 2>/dev/null || true
        done
    fi
done

echo
echo "[build] Done. Files copied to: $OUT_DIR"
ls -1 "$OUT_DIR"
