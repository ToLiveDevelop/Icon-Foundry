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

# Development baseline

For new application feature work, use the latest available `add-tabs` baseline
instead of `main`, unless the user explicitly chooses another base. Before
editing files, compare the feature branch with available `add-tabs` refs and
integrate any newer verified commits without discarding feature changes. The
local `add-tabs` ref may be ahead of `origin/add-tabs`; verify both and report
the exact refs used. Do not inspect or modify another checkout to establish the
baseline. If no suitable ref can be verified, stop before editing and report
the blocker.

Before building an APK for installation, verify its `versionCode` is not lower
than the installed app's version. If the feature branch would produce a
downgrade, integrate the current baseline before building; do not uninstall or
erase app data to work around a stale build.
