#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOD_DIR="${SCRIPT_DIR}/mod"

usage() {
  cat <<EOF
Usage: $0 [--preflight]

Build and deploy the exact Minecraft 26.1 DebugBridge jar to a Prism Launcher instance.

Options:
  --preflight   Check local deploy configuration without building or touching Prism.
  -h, --help    Show this help.

Environment:
  PRISM_INSTANCE_NAME   Prism instance name to deploy to. Default: 26.1
  PRISM_INSTANCES_DIR   Prism instances directory.
  JAVA_HOME_26_1        JDK 25 home used for Gradle.
  PROJECT_VERSION       Override parsed Gradle project version.
  JAR_NAME              Override expected jar name.
  SMOKE_PORT            Port printed in post-deploy smoke commands. Default: 9876
  SMOKE_NODE            Node 22+ binary printed in post-deploy smoke commands.
EOF
}

PREFLIGHT=false
case "${1:-}" in
  --preflight)
    PREFLIGHT=true
    shift
    ;;
  -h|--help)
    usage
    exit 0
    ;;
esac
if [ "$#" -gt 0 ]; then
  echo "Error: unknown argument(s): $*"
  usage
  exit 2
fi
PRISM_INSTANCE_NAME="${PRISM_INSTANCE_NAME:-26.1}"
PRISM_INSTANCES_DIR="${PRISM_INSTANCES_DIR:-/Users/cusgadmin/Library/Application Support/PrismLauncher/instances}"
JAVA_HOME_26_1="${JAVA_HOME_26_1:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
SMOKE_PORT="${SMOKE_PORT:-9876}"
PROJECT_VERSION="${PROJECT_VERSION:-$(sed -n 's/.*version = "\([^"]*\)".*/\1/p' "${MOD_DIR}/build.gradle.kts" | head -1)}"

if [ -z "${PROJECT_VERSION}" ]; then
  echo "Error: Could not determine project version from ${MOD_DIR}/build.gradle.kts"
  echo "Set PROJECT_VERSION explicitly before running this script."
  exit 1
fi

JAR_NAME="${JAR_NAME:-debugbridge-26.1-${PROJECT_VERSION}.jar}"
SOURCE_JAR="${MOD_DIR}/fabric-26.1/build/libs/${JAR_NAME}"

remove_stale_26_1_jars() {
  local dir="$1"
  local keep_name="${2:-}"
  local jar
  for jar in "${dir}"/debugbridge-26.1-*.jar; do
    [ -e "${jar}" ] || continue
    if [ -n "${keep_name}" ] && [ "$(basename "${jar}")" = "${keep_name}" ]; then
      continue
    fi
    echo "Removing stale $(basename "${jar}") from ${dir}..."
    rm -f "${jar}"
  done
}

verify_jar() {
  local jar_file="$1"
  unzip -tq "${jar_file}" 2>/dev/null
}

verify_26_1_metadata() {
  local jar_file="$1"
  local metadata
  if ! metadata="$(unzip -p "${jar_file}" fabric.mod.json 2>/dev/null)"; then
    echo "Error: ${jar_file} is missing fabric.mod.json"
    return 1
  fi
  printf '%s\n' "${metadata}" | grep -q '"minecraft"[[:space:]]*:[[:space:]]*"26\.1"' \
    || { echo "Error: ${jar_file} does not declare exact Minecraft 26.1"; return 1; }
  printf '%s\n' "${metadata}" | grep -q '"com\.debugbridge\.fabric261\.DebugBridgeMod"' \
    || { echo "Error: ${jar_file} does not point at com.debugbridge.fabric261.DebugBridgeMod"; return 1; }
}

detect_smoke_node() {
  local candidate
  local major
  if [ -n "${SMOKE_NODE:-}" ]; then
    printf '%s\n' "${SMOKE_NODE}"
    return
  fi

  if command -v node >/dev/null 2>&1; then
    major="$(node -p "Number(process.versions.node.split('.')[0])" 2>/dev/null || printf '0')"
    if [ "${major}" -ge 22 ] 2>/dev/null; then
      command -v node
      return
    fi
  fi

  for candidate in "${HOME}/.nvm/versions/node"/v*/bin/node; do
    [ -x "${candidate}" ] || continue
    major="$("${candidate}" -p "Number(process.versions.node.split('.')[0])" 2>/dev/null || printf '0')"
    if [ "${major}" -ge 22 ] 2>/dev/null; then
      printf '%s\n' "${candidate}"
      return
    fi
  done

  printf 'node\n'
}

SMOKE_NODE_CMD="$(detect_smoke_node)"
SMOKE_NODE_PRINT="$(printf '%q' "${SMOKE_NODE_CMD}")"

INSTANCE_DIR="${PRISM_INSTANCES_DIR}/${PRISM_INSTANCE_NAME}"

if [ -z "${PRISM_INSTANCE_NAME}" ]; then
  echo "Error: Prism instance name is empty."
  exit 1
fi

if [ ! -d "${INSTANCE_DIR}" ]; then
  echo "Error: Prism instance not found: ${INSTANCE_DIR}"
  exit 1
fi

if [ -d "${INSTANCE_DIR}/.minecraft" ]; then
  TARGET_DIR="${INSTANCE_DIR}/.minecraft/mods"
elif [ -d "${INSTANCE_DIR}/minecraft" ]; then
  TARGET_DIR="${INSTANCE_DIR}/minecraft/mods"
else
  # Prism Launcher normally uses .minecraft. Prefer that layout for a fresh
  # instance where the mods directory has not been created yet.
  TARGET_DIR="${INSTANCE_DIR}/.minecraft/mods"
fi
TARGET_JAR="${TARGET_DIR}/${JAR_NAME}"

if [ ! -x "${JAVA_HOME_26_1}/bin/java" ]; then
  echo "Error: Java 25 runtime not found at ${JAVA_HOME_26_1}"
  echo "Set JAVA_HOME_26_1 to the JDK 25 home before running this script."
  exit 1
fi

if ! command -v unzip >/dev/null 2>&1; then
  echo "Error: unzip is required to verify the 26.1 jar before deployment."
  exit 1
fi

if [ "${PREFLIGHT}" = true ]; then
  echo "Preflight OK for DebugBridge exact 26.1 Prism deployment."
  echo "Project version: ${PROJECT_VERSION}"
  echo "Prism instance: ${INSTANCE_DIR}"
  echo "Target mods dir: ${TARGET_DIR}"
  echo "Expected jar: ${JAR_NAME}"
  echo "Java: ${JAVA_HOME_26_1}/bin/java"
  echo "Smoke port: ${SMOKE_PORT}"
  echo "Smoke node: ${SMOKE_NODE_CMD}"
  echo "No build or deploy was performed."
  exit 0
fi

echo "Building DebugBridge mod (fabric-26.1)..."
cd "${MOD_DIR}"
remove_stale_26_1_jars "${MOD_DIR}/fabric-26.1/build/libs"
# The exact 26.1 line declares a Java 25 runtime, matching the 26.2-dev module.
# Point JAVA_HOME at the actual Homebrew JDK home, not just the formula prefix.
JAVA_HOME="${JAVA_HOME_26_1}" ./gradlew :fabric-26.1:build --no-daemon

if [ ! -f "${SOURCE_JAR}" ]; then
  echo "Error: Build artifact not found at ${SOURCE_JAR}"
  exit 1
fi

echo "Verifying built jar integrity and 26.1 metadata..."
verify_jar "${SOURCE_JAR}"
verify_26_1_metadata "${SOURCE_JAR}"

echo "Creating target directory if it doesn't exist..."
mkdir -p "${TARGET_DIR}"
remove_stale_26_1_jars "${TARGET_DIR}" "${JAR_NAME}"
if [ -f "${TARGET_JAR}" ]; then
  echo "Existing ${JAR_NAME} found in target; it will be replaced after staged verification."
fi

TMP_JAR="${TARGET_JAR}.new"
trap 'rm -f "${TMP_JAR}"' EXIT

MAX_RETRIES=3
RETRY_COUNT=0

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
  echo "Staging jar at ${TMP_JAR}..."
  cp -f "${SOURCE_JAR}" "${TMP_JAR}"

  echo "Verifying staged jar integrity and 26.1 metadata..."
  if verify_jar "${TMP_JAR}" && verify_26_1_metadata "${TMP_JAR}"; then
    echo "Swapping staged jar into place (atomic rename)..."
    mv -f "${TMP_JAR}" "${TARGET_JAR}"
    echo "Jar swap successful!"
    break
  else
    RETRY_COUNT=$((RETRY_COUNT + 1))
    if [ $RETRY_COUNT -lt $MAX_RETRIES ]; then
      echo "Warning: Jar verification failed (attempt ${RETRY_COUNT}/$MAX_RETRIES). Retrying..."
      sleep 1
    else
      echo "Error: Failed to stage a valid jar after $MAX_RETRIES attempts"
      echo "Source: ${SOURCE_JAR}"
      echo "Target: ${TARGET_JAR}"
      exit 1
    fi
  fi
done

echo "Build and deployment complete!"
echo "Jar deployed to: ${TARGET_JAR}"
echo
echo "After launching or restarting the Prism instance, run guarded smoke checks from the repo root:"
echo "${SMOKE_NODE_PRINT} tools/smoke-test.mjs --port ${SMOKE_PORT} --version 26.1 --game-dir-contains '${INSTANCE_DIR}' --include-textures"
echo "${SMOKE_NODE_PRINT} tools/record-video-smoke.mjs --port ${SMOKE_PORT} --version 26.1 --game-dir-contains '${INSTANCE_DIR}'"
