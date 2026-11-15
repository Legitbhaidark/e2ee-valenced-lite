#!/usr/bin/env bash
# main releases plain versions, dev releases "-devN" versions, and the version shown in the plugin
# (about.ts) must match the manifest. Fails early with a readable message instead of publishing the
# wrong thing to the wrong channel.
set -euo pipefail

manifest=plugins/aegires-e2ee/manifest.json
about=plugins/aegires-e2ee/js/about.ts
version="$(jq -r .version "$manifest")"
branch="${GITHUB_REF_NAME:-$(git rev-parse --abbrev-ref HEAD)}"

case "$branch" in
  main)
    [[ "$version" != *-* ]] || { echo "::error::main publishes releases: ${manifest} version '${version}' must have no label (e.g. 0.1.28)"; exit 1; } ;;
  dev)
    [[ "$version" =~ -dev[0-9]+$ ]] || { echo "::error::dev publishes dev builds: ${manifest} version '${version}' must end in -devN with no dot (e.g. 0.1.28-dev1)"; exit 1; } ;;
esac

shown="$(sed -n 's/^export const PLUGIN_VERSION = "\(.*\)";.*/\1/p' "$about")"
[ "$shown" = "$version" ] || { echo "::error::${about} PLUGIN_VERSION '${shown}' does not match ${manifest} version '${version}'"; exit 1; }
echo "ok: ${branch} -> ${version}"
