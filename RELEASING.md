# Branches, builds and channels

| Branch | Publishes | Version looks like | JS bundle | Valenced channel |
|--------|-----------|--------------------|-----------|------------------|
| `main` | releases  | `0.1.28`           | minified, **no** dev code | `latest` |
| `dev`  | dev builds | `0.1.28-dev.3`    | `-Pdev`, with Diagnostics, Self-test, Crypto benchmark and Developer options | `beta` |

Valenced creates the channels itself: `latest` is the newest version without a label, `beta` is the
newest version overall (it only exists while a labeled version is newer than `latest`). Extra
channels such as `lts` are pointers you add by hand under `channels` in `repo.config.json`, then run
the "publish-index" workflow.

The build number in the plugin settings is `YYMMVVVV-release` or `YYMMVVVV-dev`. It comes from
`js/about.ts` (`BUILD_NUMBER`) plus the build type, so there is nothing to edit per branch.

## Everyday work (on `dev`)

1. Change code. Bump `version` in `plugins/aegires-e2ee/manifest.json` to the next `-dev.N`
   (`0.1.28-dev.2`) and the same string in `js/about.ts` (`PLUGIN_VERSION`). CI refuses a mismatch.
2. Push. CI publishes the dev build; users on the `beta` channel get it.

## Releasing (merge `dev` into `main`)

1. On `dev`, set both versions to the plain number (`0.1.28`, drop the label), push, check it builds.
2. Merge `dev` into `main` (fast-forward). CI publishes `0.1.28` as the new `latest`.
3. On `dev`, move on to `0.1.29-dev.1` straight away. Valenced refuses a version lower than the
   newest published one, and `0.1.28-dev.N` is lower than `0.1.28`.

Never publish a labeled version from `main` or a plain one from `dev`; `check-channel.sh` fails the run.
