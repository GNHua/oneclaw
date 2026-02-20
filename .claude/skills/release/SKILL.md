---
name: release
description: Build a signed release APK and publish it as a GitHub release
disable-model-invocation: true
allowed-tools: Bash(git *), Bash(./gradlew *), Bash(gh *), Bash(du *), Bash(cp *)
---

# Release

Build a signed release APK and publish it to GitHub from the `release/apk` branch.

Usage: `/release <version>` (e.g., `/release 1.0.2`)

## Prerequisites

- `signing.properties` and `release.jks` in the parent directory (`../`)
- `gh` CLI installed and authenticated

## Branch strategy

- `release/apk` -- branch used for GitHub APK releases (this skill)
- `release` -- separate branch for Play Store releases (not managed here)

## Steps

1. **Update docs** -- before building, review and update based on changes since the last release:
   - `README.md` -- ensure feature list, install instructions, and screenshots reflect the current state
   - `docs/index.md` -- landing page (install instructions, provider list, screenshots, doc links)
   - `docs/reference.md` -- plugin & skill reference (add new plugins/tools, update counts, fix categories)
   - `docs/memory.md` -- memory system (character limits, search algorithm, flush behavior)
   - `docs/skill-loading.md` -- skill loading architecture (frontmatter fields, filtering, XML format)
   - Commit any doc changes on `main` before proceeding to the release branch.

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

3. **Copy signing files** from parent directory into project root (they are gitignored):
   ```bash
   cp ../signing.properties signing.properties
   cp ../release.jks release.jks
   ```

4. **Preflight checks** -- abort if any fail:
   - Current branch is `release/apk`
   - Working tree is clean (`git status --porcelain` is empty)
   - `signing.properties` exists in project root
   - `release.jks` exists in project root
   - `gh auth status` succeeds
   - Tag `v<VERSION>` does not already exist

5. **Build the release APK**:
   ```bash
   ./gradlew assembleRelease
   ```
   Verify `app/build/outputs/apk/release/app-release.apk` exists, then rename it:
   ```bash
   cp app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/oneclaw-<VERSION>.apk
   ```

6. **Generate release notes** from commits since the previous tag:
   ```bash
   PREV_TAG=$(git describe --tags --abbrev=0 2>/dev/null)
   git log "${PREV_TAG}..HEAD" --pretty=format:"- %s" | grep -v "^- chore:"
   ```

7. **Show the user** the version, commit list, and generated notes. Ask for confirmation before proceeding.

8. **Tag and push**:
   ```bash
   git tag v<VERSION>
   git push origin release/apk
   git push origin v<VERSION>
   ```

9. **Create the GitHub release** with the APK attached:
   ```bash
   gh release create v<VERSION> \
     app/build/outputs/apk/release/oneclaw-<VERSION>.apk \
     --title "v<VERSION>" \
     --notes "<NOTES>"
   ```

10. Print the release URL.

11. **Return to main**:
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
