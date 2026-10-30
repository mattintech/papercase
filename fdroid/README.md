# Publishing on F-Droid

F-Droid builds Papercase from source and signs it with its own key. The store listing
(title, descriptions, icon, screenshots, changelogs) is read from
[`fastlane/metadata/android`](../fastlane/metadata/android) in this repo.

## First submission

1. Tag the release on `main`, for example `v1.0.0` (the release workflow does the rest on GitHub).
2. Fork https://gitlab.com/fdroid/fdroiddata and add
   [`com.mattintech.papercase.yml`](com.mattintech.papercase.yml) as
   `metadata/com.mattintech.papercase.yml`.
3. Check it locally if you have `fdroidserver` installed:
   ```bash
   fdroid readmeta && fdroid lint com.mattintech.papercase && fdroid build -v -l com.mattintech.papercase
   ```
4. Open a merge request to fdroiddata using the "App inclusion" template.

## Later releases

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Merge to `main` and push a tag `v<versionName>`.

F-Droid checks for new tags (`UpdateCheckMode: Tags`) and builds them automatically;
new versions usually appear within a few days.
