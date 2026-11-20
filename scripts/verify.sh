#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_NAME="structure-dfu-cache-verify:local"

USE_CONTAINER=0
EXTRA_ARGS=()
for arg in "$@"; do
    if [[ "$arg" == "--container" ]]; then
        USE_CONTAINER=1
    else
        EXTRA_ARGS+=("$arg")
    fi
done

discover_jdk() {
    local major="$1"
    local env_var="JAVA_${major}_HOME"
    if [[ -n "${!env_var:-}" && -x "${!env_var}/bin/java" ]]; then
        echo "${!env_var}"
        return 0
    fi
    local candidates=(
        "/usr/lib/jvm/temurin-${major}"
        "/opt/homebrew/opt/openjdk@${major}/libexec/openjdk.jdk/Contents/Home"
        "/Users/rin/Library/Java/JavaVirtualMachines/temurin-${major}.jdk/Contents/Home"
    )
    for c in "${candidates[@]}"; do
        if [[ -x "$c/bin/java" ]]; then
            echo "$c"
            return 0
        fi
    done
    return 1
}

if [[ "$USE_CONTAINER" -eq 0 ]]; then
    if ! discover_jdk 17 >/dev/null || ! discover_jdk 21 >/dev/null || ! discover_jdk 25 >/dev/null; then
        echo "[verify] Host is missing one or more required JDKs (17/21/25); falling back to container."
        USE_CONTAINER=1
    fi
fi

if [[ "$USE_CONTAINER" -eq 1 ]]; then
    ENGINE=""
    if command -v docker >/dev/null 2>&1; then
        ENGINE="docker"
    elif command -v podman >/dev/null 2>&1; then
        ENGINE="podman"
    else
        echo "[verify] ERROR: Neither docker nor podman is available for --container mode." >&2
        exit 1
    fi
    echo "[verify] Building container image $IMAGE_NAME via $ENGINE..."
    "$ENGINE" build -f "$REPO_ROOT/Dockerfile.verify" -t "$IMAGE_NAME" "$REPO_ROOT"
    echo "[verify] Running full verification inside container (xvfb-run)..."
    exec "$ENGINE" run --rm --init \
        -v "$REPO_ROOT:/workspace" \
        -v rinchan-gradle-cache-sdfu:/root/.gradle \
        -w /workspace \
        "$IMAGE_NAME" \
        bash scripts/verify.sh ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}
fi

export JAVA_17_HOME="$(discover_jdk 17)"
export JAVA_21_HOME="$(discover_jdk 21)"
export JAVA_25_HOME="$(discover_jdk 25)"
export JAVA_HOME="$JAVA_21_HOME"

echo "[verify] Running build-matrix.py (builds all targets + JUnit + Property + ArchitectureContract + 1.21.1 Packaged-JAR GameTestServer E2E)..."
python3 "$REPO_ROOT/scripts/build-matrix.py" ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}
echo "[verify] ALL VERIFICATION GATES PASSED."
