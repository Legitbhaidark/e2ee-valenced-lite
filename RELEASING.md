# Branches, builds and channels

| Branch | Publishes | Version looks like | JS bundle | Valenced channel |
|--------|-----------|--------------------|-----------|------------------|
| `main` | releases  | `0.1.28`           | minified, **no** dev code | `latest` |
| `dev`  | dev builds | `0.1.28-dev3`    | `-Pdev`, with Diagnostics, Self-test, Crypto benchmark and Developer options | `beta` |

Valenced creates the channels itself: `latest` is the newest version without a label, `beta` is the
newest version overall (it only exists while a labeled version is newer than `latest`). Extra
channels such as `lts` are pointers you add by hand under `channels` in `repo.config.json`, then run
the "publish-index" workflow.

The build number in the plugin settings is `YYMMVVVV-release` or `YYMMVVVV-dev`. It comes from
`js/about.ts` (`BUILD_NUMBER`) plus the build type, so there is nothing to edit per branch.

## Everyday work (on `dev`)

1. Change code. Bump `version` in `plugins/aegires-e2ee/manifest.json` to the next `-devN`
   (`0.1.28-dev2`) and the same string in `js/about.ts` (`PLUGIN_VERSION`). CI refuses a mismatch.
2. Push. CI publishes the dev build; users on the `beta` channel get it.

## Releasing (merge `dev` into `main`)

1. On `dev`, set both versions to the plain number (`0.1.28`, drop the label), push, check it builds.
2. Merge `dev` into `main` (fast-forward). CI publishes `0.1.28` as the new `latest`.
3. On `dev`, move on to `0.1.29-dev1` straight away. Valenced refuses a version lower than the
   newest published one, and `0.1.28-devN` is lower than `0.1.28`.

Never publish a labeled version from `main` or a plain one from `dev`; `check-channel.sh` fails the run.

## GitHub Releases (downloadable zips)

Every publish also creates a GitHub Release `aegires-e2ee@<version>` with the installable zip attached:

- `dev` builds (`0.1.30-dev19`) become **pre-releases**, notes = the commits since the previous dev build.
- `main` builds (`0.1.30`) become the **latest** release, notes = the commits since the previous stable release.
- To write the stable notes by hand, add `release-notes/0.1.30.md` before merging to `main`. It is used as
  written; the install lines are appended automatically.

## Channels

| Channel | Points at | Moves |
|---------|-----------|-------|
| `latest` | newest release (no label) | by itself, when `main` publishes |
| `beta` | newest build overall, normally the newest `-devN` | by itself, when `dev` publishes. It only exists while it is newer than `latest` |
| `lts` | a release you trust | by hand, see below |

Valenced lets a user follow one channel or pin a version. Only `lts` needs your attention:

1. In `repo.config.json` set `channels` > the plugin id > `lts` to a version that is already published
   (a pointer to an unpublished version makes index generation fail).
2. Push, then run the "Publish repository index" workflow (Actions tab). A config-only push publishes
   nothing by itself, because the release workflow only regenerates the index when there is a new version.
3. Keep `repo.config.json` identical on `main` and `dev`. Whichever branch publishes last decides the
   channels, so a stale copy on the other branch silently moves them back.

The same pointer is the rollback lever: to pull a bad release off `latest`, add `"latest": "<good version>"`
there and republish the index. No rebuild is needed.
