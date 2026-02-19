---
name: release
description: Build a signed release APK and publish it as a GitHub release
disable-model-invocation: true
allowed-tools: Bash(git *), Bash(./gradlew *), Bash(gh *), Bash(du *)
---

# Release

Build a signed release APK and publish it to GitHub from the `release/apk` branch.

Usage: `/release <version>` (e.g., `/release 1.0.2`)

## Prerequisites

- `signing.properties` and `release.jks` in project root
- `gh` CLI installed and authenticated

## Branch strategy

- `release/apk` -- branch used for GitHub APK releases (this skill)
- `release` -- separate branch for Play Store releases (not managed here)

## Steps

1. **Update docs** -- before building, review and update:
   - `README.md` -- ensure feature list, install instructions, and screenshots reflect the current state
   - `docs/` -- update the GitHub Pages site (e.g., feature descriptions, install links pointing to the new version)
   - Commit any doc changes before proceeding.

2. **Prepare the `release/apk` branch**:
   - If the branch does not exist yet, create it from `main`:
     ```bash
     git checkout -b release/apk main
     ```
   - If it already exists, check it out and merge `main` into it:
     ```bash
     git checkout release/apk
     git merge main
     ```

3. **Preflight checks** -- abort if any fail:
   - Current branch is `release/apk`
   - Working tree is clean (`git status --porcelain` is empty)
   - `signing.properties` exists
   - `release.jks` exists
   - `gh auth status` succeeds
   - Tag `v<VERSION>` does not already exist

4. **Build the release APK**:
   ```bash
   ./gradlew assembleRelease
   ```
   Verify `app/build/outputs/apk/release/app-release.apk` exists.

5. **Generate release notes** from commits since the previous tag:
   ```bash
   PREV_TAG=$(git describe --tags --abbrev=0 2>/dev/null)
   git log "${PREV_TAG}..HEAD" --pretty=format:"- %s" | grep -v "^- chore:"
   ```

6. **Show the user** the version, commit list, and generated notes. Ask for confirmation before proceeding.

7. **Tag and push**:
   ```bash
   git tag v<VERSION>
   git push origin release/apk
   git push origin v<VERSION>
   ```

8. **Create the GitHub release** with the APK attached:
   ```bash
   gh release create v<VERSION> \
     app/build/outputs/apk/release/app-release.apk \
     --title "v<VERSION>" \
     --notes "<NOTES>"
   ```

9. Print the release URL.

10. **Return to main**:
    ```bash
    git checkout main
    ```

## Release notes rules

- Always include a `## What's New` heading followed by bullet points.
- Pull bullets from `git log` between the previous tag and HEAD.
- Filter out `chore:` commits -- they are not user-facing.
- NEVER use the auto-generated "Full Changelog" link as the sole description.

## Arguments

$ARGUMENTS
