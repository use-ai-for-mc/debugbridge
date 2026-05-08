#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_DIR="${SCRIPT_DIR}/mod"
MODRINTH_PROFILE_NAME="${MODRINTH_PROFILE_NAME:-REPLACE-WITH-PROFILE-NAME}"

JAR_NAME="debugbridge-26.2-dev-1.1.0.jar"
OLD_JAR_NAMES=("debugbridge-26.2-dev-1.0.0.jar" "debugbridge-26.2-snapshot-5-1.1.0.jar" "debugbridge-26.2-snapshot-5-1.0.0.jar" "debugbridge-26.2-snapshot-4-1.1.0.jar" "debugbridge-26.2-snapshot-4-1.0.0.jar" "debugbridge-26.2-snapshot-3-1.1.0.jar" "debugbridge-26.2-snapshot-3-1.0.0.jar")
SOURCE_JAR="${MOD_DIR}/fabric-26.2-dev/build/libs/${JAR_NAME}"

echo "Building DebugBridge mod (fabric-26.2-dev)..."
cd "${MOD_DIR}"
# Remove stale outputs from any older archivesName values.
for old in "${OLD_JAR_NAMES[@]}"; do
    rm -f "${MOD_DIR}/fabric-26.2-dev/build/libs/${old}"
done
JAVA_HOME=/opt/homebrew/opt/openjdk@26 ./gradlew :fabric-26.2-dev:build --no-daemon

if [ ! -f "${SOURCE_JAR}" ]; then
    echo "Error: Build artifact not found at ${SOURCE_JAR}"
    exit 1
fi

if [ "${MODRINTH_PROFILE_NAME}" = "REPLACE-WITH-PROFILE-NAME" ] || [ -z "${MODRINTH_PROFILE_NAME}" ]; then
    echo "Error: set MODRINTH_PROFILE_NAME before running this script"
    exit 1
fi

TARGET_DIR="/Users/cusgadmin/Library/Application Support/ModrinthApp/profiles/${MODRINTH_PROFILE_NAME}/mods/"
TARGET_JAR="${TARGET_DIR}/${JAR_NAME}"

echo "Creating target directory if it doesn't exist..."
mkdir -p "${TARGET_DIR}"

# Remove any stale jar names from the target mods directory so only the current jar remains.
for old in "${OLD_JAR_NAMES[@]}"; do
    if [ -f "${TARGET_DIR}/${old}" ]; then
        echo "Removing stale ${old} from mods folder..."
        rm -f "${TARGET_DIR}/${old}"
    fi
done

verify_jar() {
    local jar_file="$1"
    unzip -tq "${jar_file}" 2>/dev/null
}

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
