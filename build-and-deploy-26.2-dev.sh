#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_DIR="${SCRIPT_DIR}/mod"
MODRINTH_PROFILE_NAME="${MODRINTH_PROFILE_NAME:-REPLACE-WITH-PROFILE-NAME}"

PROJECT_VERSION="${PROJECT_VERSION:-$(sed -n 's/.*version = "\([^"]*\)".*/\1/p' "${MOD_DIR}/build.gradle.kts" | head -1)}"
if [ -z "${PROJECT_VERSION}" ]; then
  echo "Error: Could not determine project version from ${MOD_DIR}/build.gradle.kts"
  echo "Set PROJECT_VERSION explicitly before running this script."
  exit 1
fi

JAR_NAME="debugbridge-26.2-${PROJECT_VERSION}.jar"
SOURCE_JAR="${MOD_DIR}/fabric-26.2-dev/build/libs/${JAR_NAME}"

remove_26_2_jars() {
    local dir="$1"
    for old in \
        "${dir}"/debugbridge-26.2-[0-9]*.jar \
        "${dir}"/debugbridge-26.2-dev-*.jar \
        "${dir}"/debugbridge-26.2-snapshot-*.jar; do
        if [ -f "${old}" ]; then
            echo "Removing stale $(basename "${old}") from ${dir}..."
            rm -f "${old}"
        fi
    done
}

echo "Building DebugBridge mod (fabric-26.2-dev)..."
cd "${MOD_DIR}"
# Remove stale outputs from any older archivesName values.
remove_26_2_jars "${MOD_DIR}/fabric-26.2-dev/build/libs"
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :fabric-26.2-dev:build --no-daemon

if [ ! -f "${SOURCE_JAR}" ]; then
    echo "Error: Build artifact not found at ${SOURCE_JAR}"
    exit 1
fi

if [ "${MODRINTH_PROFILE_NAME}" = "REPLACE-WITH-PROFILE-NAME" ] || [ -z "${MODRINTH_PROFILE_NAME}" ]; then
    echo "Error: set MODRINTH_PROFILE_NAME before running this script"
    exit 1
fi

verify_jar() {
    local jar_file="$1"
    unzip -tq "${jar_file}" 2>/dev/null
}

verify_26_2_metadata() {
    local jar_file="$1"
    local metadata
    metadata="$(unzip -p "${jar_file}" fabric.mod.json 2>/dev/null || true)"
    if [ -z "${metadata}" ]; then
        echo "Error: ${jar_file} is missing fabric.mod.json"
        return 1
    fi
    printf '%s\n' "${metadata}" | grep -q '"minecraft"[[:space:]]*:[[:space:]]*"26.2"' \
      || { echo "Error: ${jar_file} does not declare exact Minecraft 26.2"; return 1; }
    printf '%s\n' "${metadata}" | grep -q '"com\.debugbridge\.fabric262\.DebugBridgeMod"' \
      || { echo "Error: ${jar_file} does not use com.debugbridge.fabric262.DebugBridgeMod entrypoint"; return 1; }
}

if ! verify_jar "${SOURCE_JAR}" || ! verify_26_2_metadata "${SOURCE_JAR}"; then
    echo "Error: Built jar failed integrity / metadata checks: ${SOURCE_JAR}"
    exit 1
fi

TARGET_DIR="/Users/cusgadmin/Library/Application Support/ModrinthApp/profiles/${MODRINTH_PROFILE_NAME}/mods/"
TARGET_JAR="${TARGET_DIR}/${JAR_NAME}"

echo "Creating target directory if it doesn't exist..."
mkdir -p "${TARGET_DIR}"

# Remove any stale jar names from the target mods directory so only the current jar remains.
remove_26_2_jars "${TARGET_DIR}"

MAX_RETRIES=3
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    echo "Copying jar to ${TARGET_DIR}..."
    cp -f "${SOURCE_JAR}" "${TARGET_JAR}"

    echo "Verifying copied jar integrity..."
    if verify_jar "${TARGET_JAR}"; then
        echo "Jar verification successful!"
        break
    else
        RETRY_COUNT=$((RETRY_COUNT + 1))
        if [ $RETRY_COUNT -lt $MAX_RETRIES ]; then
            echo "Warning: Jar verification failed (attempt $RETRY_COUNT/$MAX_RETRIES). Retrying..."
            sleep 1
        else
            echo "Error: Failed to copy a valid jar after $MAX_RETRIES attempts"
            echo "Source: ${SOURCE_JAR}"
            echo "Target: ${TARGET_JAR}"
            exit 1
        fi
    fi
done

echo "Build and deployment complete!"
echo "Jar copied to: ${TARGET_JAR}"
