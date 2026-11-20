#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
git -C "$REPO_ROOT" config core.hooksPath .githooks
chmod +x "$REPO_ROOT"/.githooks/* "$REPO_ROOT"/scripts/*.sh
echo "Configured core.hooksPath=$(git -C "$REPO_ROOT" config --get core.hooksPath)"
