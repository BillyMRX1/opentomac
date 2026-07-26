#!/usr/bin/env bash
# Fails loudly if the release-please-managed version strings drift apart.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

manifest_file="$repo_root/.release-please-manifest.json"
gradle_file="$repo_root/android/build.gradle.kts"
project_yml_file="$repo_root/macos/project.yml"

manifest_version="$(grep -o '"\."[[:space:]]*:[[:space:]]*"[^"]*"' "$manifest_file" | sed -E 's/.*:[[:space:]]*"([^"]*)"/\1/' || true)"
gradle_version="$(grep -E 'val semver = "[^"]*"' "$gradle_file" | sed -E 's/.*val semver = "([^"]*)".*/\1/' || true)"
project_yml_version="$(grep -E 'MARKETING_VERSION: "[^"]*"' "$project_yml_file" | sed -E 's/.*MARKETING_VERSION: "([^"]*)".*/\1/' || true)"

if [[ -z "$manifest_version" ]]; then
    echo "Could not parse version from $manifest_file" >&2
    exit 1
fi
if [[ -z "$gradle_version" ]]; then
    echo "Could not parse version from $gradle_file" >&2
    exit 1
fi
if [[ -z "$project_yml_version" ]]; then
    echo "Could not parse version from $project_yml_file" >&2
    exit 1
fi

if [[ "$manifest_version" != "$gradle_version" || "$manifest_version" != "$project_yml_version" ]]; then
    echo "Version strings are out of sync:" >&2
    echo "  $manifest_file: $manifest_version" >&2
    echo "  $gradle_file: $gradle_version" >&2
    echo "  $project_yml_file: $project_yml_version" >&2
    exit 1
fi

echo "Version sync OK: $manifest_version"
