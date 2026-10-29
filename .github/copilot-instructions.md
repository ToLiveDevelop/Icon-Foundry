# Version delivery

For each completed application version requested by the user:
- Increment `versionCode` and `versionName` in `app/build.gradle`.
- Update directly related documentation and validate the changes before publishing.
- Commit the version to this repository and push it to GitHub.
- Tag the version as `v<versionName>` and publish the locally built APK as
  `Icon-Foundry.apk` in the matching GitHub Release, keeping the landing-page
  download link working.
- Keep the existing signing identity for installable updates. Never commit
  keystores, signing credentials, local SDK paths, user themes or icon-pack assets.
- Do not apply a theme to the user's phone automatically.

The static landing page is in `docs/`; GitHub Actions deploys it to Pages on
changes pushed to `main`.
