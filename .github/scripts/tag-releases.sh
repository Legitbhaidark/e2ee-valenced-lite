#!/usr/bin/env bash
# Writes a tag and a GitHub Release per planned version, with the installable zip attached.
#
#   dev branch  (versions like 1.2.3-dev4)  -> pre-release
#   main branch (plain versions like 1.2.3) -> stable release, marked "latest"
#
# Release notes: if release-notes/<version>.md exists in the repo it is used as written; otherwise the
# notes list the commits since the previous release of the same channel.

set -euo pipefail
# shellcheck source=.github/scripts/lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

base="$(pages_base_url)"
assets="$(mktemp -d)"

# Latest earlier tag of this plugin that is part of this branch's history, same channel first.
previous_tag() {
    local id="$1" tag="$2" stable="$3" tags
    tags="$(git tag --merged HEAD --list "${id}@*" | grep -vxF "$tag" | sort -V || true)"
    if [ "$stable" = "1" ]; then
        printf '%s\n' "$tags" | grep -v -- '-dev' | tail -n 1 || true
    else
        printf '%s\n' "$tags" | grep -- '-dev' | tail -n 1 || true
    fi
}

# Commit subjects as bullets: drops the "0.1.30-dev5:" prefix and empty or "stable" bump commits.
commit_list() {
    git log --no-merges --pretty='- %s' "$@" |
        sed -E 's/^- [0-9][0-9.]*(-dev[0-9]+)?:? *-? */- /' |
        grep -Ev '^- *$|^- *stable$' | head -n 60 || true
}

write_notes() {
    local id="$1" version="$2" tag="$3" stable="$4" out="$5"
    local custom="release-notes/${version}.md" prev

    {
        if [ -f "$custom" ]; then
            cat "$custom"
        else
            if [ "$stable" = "1" ]; then
                echo "## ${id} ${version}"
            else
                echo "## ${id} ${version} (pre-release)"
                echo
                echo "Development build from the \`dev\` branch. Expect rough edges."
            fi
            echo
            echo "### Changes"
            prev="$(previous_tag "$id" "$tag" "$stable")"
            if [ -n "$prev" ]; then
                commit_list "${prev}..HEAD"
                echo
                echo "Changes since \`${prev}\`."
            else
                commit_list -n 30
            fi
        fi
        echo
        echo "### Install"
        echo "- In Valenced, add this plugin source: \`${base}/index.json\`"
        echo "- Or download \`${id}@${version}.zip\` from the assets below."
    } > "$out"
}

jq -r '.[] | [.tag, .id, .version, .zip, .file] | @tsv' "$PLAN" |
    while IFS=$'\t' read -r tag id version zip file; do
        if ! git rev-parse -q --verify "refs/tags/${tag}" > /dev/null; then
            git tag "$tag"
            git push origin "$tag"
        fi

        stable=1
        case "$version" in *-*) stable=0 ;; esac

        cp "$zip" "${assets}/${file}"
        notes="${assets}/${tag//\//_}.notes.md"
        write_notes "$id" "$version" "$tag" "$stable" "$notes"

        if [ "$stable" = "1" ]; then
            flags=(--latest)
        else
            flags=(--prerelease --latest=false)
        fi

        if gh release view "$tag" > /dev/null 2>&1; then
            # Re-run, or a release made by an older workflow: bring it up to date.
            gh release edit "$tag" --title "$tag" --notes-file "$notes" "${flags[@]}"
            gh release upload "$tag" "${assets}/${file}" --clobber
            echo "= ${tag} (updated)"
        else
            gh release create "$tag" "${assets}/${file}" \
                --title "$tag" \
                --notes-file "$notes" \
                "${flags[@]}"
            echo "+ ${tag}"
        fi
    done
