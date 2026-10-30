# Publishing on F-Droid

F-Droid builds Papercase from source and signs it with its own key. The store listing
(title, descriptions, icon, screenshots, changelogs) is read from
[`fastlane/metadata/android`](../fastlane/metadata/android) in this repo.

## First submission

1. Tag the release on `main`, for example `v1.1.1` (the release workflow does the rest on GitHub).
2. Fork https://gitlab.com/fdroid/fdroiddata and add
   [`com.mattintech.papercase.yml`](com.mattintech.papercase.yml) as
   `metadata/com.mattintech.papercase.yml`.
3. Check it locally if you have `fdroidserver` installed:
   ```bash
   fdroid readmeta && fdroid lint com.mattintech.papercase && fdroid build -v -l com.mattintech.papercase
   ```
4. Open a merge request to fdroiddata using the "App inclusion" template.

## Releasing

Releases are cut by tagging `main`. How each store gets its version:

| | versionName | versionCode |
|---|---|---|
| Google Play | the tag without the `v` (`v1.2.0` → `1.2.0`) | highest code on any Play track + 1, looked up by the release workflow with fastlane |
| F-Droid | `versionName` in `app/build.gradle.kts` at the tag | `versionCode` in `app/build.gradle.kts` at the tag |

F-Droid builds from source and can only see what's committed at the tag, so its version
comes from the build file, not from Play. The codes don't need to match: the two builds are
signed with different keys and can't update each other anyway.

Steps:

1. On `develop`, set `versionName` in `app/build.gradle.kts` to the new version and
   increase `versionCode` by 1. Only F-Droid and local builds use these values.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters at most).
   F-Droid shows it, and the release workflow uses it as the Google Play and GitHub release notes.
3. Merge `develop` into `main`, then tag the merge commit and push the tag:
   ```bash
   git checkout main && git pull
   git tag v1.2.0 && git push origin v1.2.0
   ```
4. The release workflow checks the tag is on `main`, builds the signed APK and app bundle,
   attaches the APK to a GitHub release, and uploads the bundle to the Play track in the
   `PLAY_TRACK` variable (default `internal`), with the status in `PLAY_RELEASE_STATUS`
   (default `draft`; `completed` releases it straight away).

F-Droid checks for new `vMAJOR.MINOR.PATCH` tags (`UpdateCheckMode: Tags`) and builds them
automatically; new versions usually appear within a few days. If step 1 is skipped, Play
still gets the release but F-Droid won't (the workflow shows a warning).

Tags with a suffix, like `v1.2.0-beta.1`, go to Play and are marked as a pre-release on
GitHub, but F-Droid ignores them. If the versionName in the build file doesn't match the tag,
the release notes come from `changelogs/default.txt` if it exists.

To check the Play lookup without releasing anything, run the Release workflow manually
(Actions > Release > Run workflow). It only prints the next versionCode.
