---
name: android-layer2-test
description: Run Layer 2 adb visual verification flows for OneClawShadow on a connected Android device. Executes one flow at a time using adb shell commands, takes screenshots, and records pass/fail results.
---

# Android Layer 2 Visual Test Skill

## Purpose

Execute Layer 2 visual verification flows from `docs/testing/strategy.md` on a real Android device connected via adb. Each flow is run by a single subagent. Because there is only one phone, flows must be run one at a time — never spawn multiple subagents for different flows concurrently.

## When to use

Load this skill when asked to run, execute, or test any Layer 2 flow (e.g., "run Flow 1-1", "test Flow 3-2 on the device", "do Layer 2 testing").

## Constraints

- **One subagent at a time.** Only one flow may be in progress at a given moment. Wait for the current subagent to finish before launching the next.
- **One device.** Always use the device returned by `adb devices`. Do not assume a serial; read it fresh each time.
- **Screenshots go to `docs/testing/reports/screenshots/`.** Name them `Flow{RFC}-{N}_step{S}_{description}.png` (e.g., `Flow1-1_step4_streaming_mid.png`).
- **Results go into the RFC test report** at `docs/testing/reports/RFC-00{N}-*-report.md` (EN) and `-zh.md` (ZH). Update both files.

## Workflow

### Step 1 — Identify the flow

Read the flow definition from the relevant RFC document under `### Layer 2 Visual Verification Flows`. Note the preconditions, steps, and what each screenshot must verify.

### Step 2 — Confirm the device

```bash
adb devices
```

If no device is listed, stop and report that no device is connected. Do not proceed.

### Step 3 — Install the latest build

```bash
./gradlew assembleDebug
adb -s <SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 4 — Get element coordinates before tapping

Never hardcode coordinates. Before each tap, dump the UI hierarchy and parse the target element's `bounds`:

```bash
adb -s <SERIAL> shell uiautomator dump /sdcard/ui.xml
adb -s <SERIAL> pull /sdcard/ui.xml /tmp/ui.xml
# Then read /tmp/ui.xml and find the element by content-desc, class, or text
```

Tap the center of the element's bounding box:
- Bounds `[x1,y1][x2,y2]` → tap at `((x1+x2)/2, (y1+y2)/2)`

**Important:** The software keyboard shifts the on-screen position of elements below it. Re-dump the UI hierarchy after the keyboard appears to get updated bounds. Never use `KEYCODE_BACK` to dismiss the keyboard — it backgrounds the app.

### Step 5 — Type text

```bash
adb -s <SERIAL> shell input text "your\ message\ here"
```

Escape spaces with `\ `. For messages with special characters, use `adb shell input keyevent` for individual keys or break text into parts.

### Step 6 — Take screenshots

```bash
adb -s <SERIAL> shell screencap -p /sdcard/shot.png
adb -s <SERIAL> pull /sdcard/shot.png docs/testing/reports/screenshots/<filename>.png
```

Read the pulled PNG with the Read tool to visually inspect it before recording the result.

### Step 7 — Poll for state changes

To wait for streaming to complete (Stop button → Send button):

```bash
until adb -s <SERIAL> shell uiautomator dump /sdcard/p.xml 2>/dev/null && \
      adb -s <SERIAL> pull /sdcard/p.xml /tmp/p.xml 2>/dev/null && \
      grep -q 'content-desc="Send"' /tmp/p.xml; do sleep 2; done
```

### Step 8 — Record results

After the flow completes, update the test report (EN + ZH) at `docs/testing/reports/RFC-00{N}-*-report.md`:

1. Change the Layer 2 row in the summary table from `SKIP` to `PASS` or `FAIL`.
2. Add a subsection under `## Layer 2: adb Visual Verification` with:
   - Flow ID, result, device model, Android version, provider/model used
   - For each screenshot taken, embed it inline using a relative path:
     ```markdown
     <img src="screenshots/Flow1-1_step4_streaming_mid.png" width="280">
     ```
   - A description of what was verified in that screenshot
3. Add an entry to the Change History table.

Both the EN and ZH report files must be updated. Screenshots are referenced by the same relative path in both.

## Screenshot naming convention

```
Flow{RFC#}-{flow#}_step{step#}_{short_description}.png
```

Examples:
- `Flow1-1_step1_launch.png`
- `Flow1-1_step4_streaming_mid.png`
- `Flow3-2_step3_provider_connected.png`

## Pass/Fail criteria

A flow **PASSES** if every verification point listed in its definition is satisfied in the screenshots or observed state.

A flow **FAILS** if any verification point is not satisfied. Record what was expected, what was observed, and (if identifiable) which source file is likely responsible.

## Reporting a failure

In the Issues Found section of the report, include:
- Flow ID and step number
- Expected vs. actual behavior
- Screenshot filename showing the failure
- Likely source file/component (if determinable from screenshots alone)
