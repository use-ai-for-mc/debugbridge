#!/usr/bin/env bash
# Point this clone's git hooks at .githooks/ (versioned with the repo).
# Idempotent: safe to re-run.
set -e

ROOT=$(git rev-parse --show-toplevel)
git -C "$ROOT" config core.hooksPath .githooks
chmod +x "$ROOT/.githooks/"*

echo "Git hooks installed (core.hooksPath=.githooks)."
echo "Bypass for a single commit with: git commit --no-verify"
