# Test Report: RFC-XXX — [RFC Name]

## Report Information

| Field | Value |
|-------|-------|
| RFC | RFC-XXX |
| Related FEAT | FEAT-XXX |
| Commit | `xxxxxxx` |
| Date | YYYY-MM-DD |
| Tester | AI (OpenCode) |
| Status | PASS / PARTIAL / FAIL |

## Summary

Brief description of what was implemented and tested in this RFC.

| Layer | Step | Result | Notes |
|-------|------|--------|-------|
| 1A | JVM Unit Tests | PASS / FAIL / SKIP | X tests |
| 1B | Instrumented DAO Tests | PASS / FAIL / SKIP | X tests |
| 1B | Instrumented UI Tests | PASS / FAIL / SKIP | X tests |
| 1C | Roborazzi Screenshot Tests | PASS / FAIL / SKIP | X screenshots |
| 2 | adb Visual Flows | PASS / FAIL / SKIP | Flow X, Y, Z |

## Layer 1A: JVM Unit Tests

**Command:** `./gradlew test`

**Result:** PASS

**Test count:** X tests, 0 failures

Notable test classes added or modified in this RFC:
- `SomeUseCaseTest` — X tests
- `SomeViewModelTest` — X tests

## Layer 1B: Instrumented Tests

**Command:** `ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest`

**Result:** PASS / SKIP

**Reason for skip (if applicable):** _e.g., emulator not available_

**Test count:** X tests, 0 failures

## Layer 1C: Roborazzi Screenshot Tests

**Commands:**
```bash
./gradlew recordRoborazziDebug
./gradlew verifyRoborazziDebug
```

**Result:** PASS / SKIP

**Reason for skip (if applicable):** _e.g., no UI changes in this RFC_

### Screenshots

#### [ScreenName] — [variant]

![ScreenName](screenshots/RFC-XXX_ScreenName.png)

Visual check: [describe what you see and confirm it matches expectations]

## Layer 2: adb Visual Verification

**Result:** PASS / SKIP

**Reason for skip (if applicable):** _e.g., API keys not set; Chat not yet implemented_

### Flow 1: [Flow Name]

Steps executed:
1. [action] — [result]
2. [action] — [result]

Screenshot:
![flow1-step3](../../screenshots/layer2/flow1-step3.png)

Verdict: PASS / FAIL — [explanation]

## Issues Found

_List any bugs, unexpected behavior, or deviations from the RFC discovered during testing._

| # | Description | Severity | Status |
|---|-------------|----------|--------|
| 1 | | | |

_If none: "No issues found."_

## Change History

| Date | Change |
|------|--------|
| YYYY-MM-DD | Initial report |
