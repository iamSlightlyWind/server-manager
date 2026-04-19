---
name: "android-device-qa"
description: "Use when validating Android app flows from VS Code against a VM or physical device over adb, with UI-tree inspection, screenshots, and logcat capture."
argument-hint: "Validate an Android flow over adb with UI-tree evidence from VS Code"
user-invocable: true
disable-model-invocation: false
---

# Android Device QA

Validate Android app flows from VS Code using adb for launch, input, UI-tree inspection, screenshots, and logs. Use a VM or physical phone as the target device. Use Android Studio only when manual debugging is required and adb is not enough.

## When to use
- QA a feature flow on an Android VM or physical device.
- Reproduce UI bugs by driving navigation with adb input events.
- Capture screenshots and logcat output while testing.
- Confirm behavior from VS Code without touching app code unless explicitly asked.
- Escalate to Android Studio for manual debugging only when logs, UI tree, and adb-driven validation do not explain the failure.

## Constraints
- Use adb for all automated device interaction.
- Make AI-driven actions from VS Code; do not rely on Android Studio as the primary control surface.
- Verify the target device serial before any device command.
- Do not guess tap coordinates from screenshots.
- Prefer UI-tree bounds for targeting; use screenshots only as a visual check.
- Do not modify app code unless explicitly requested.

## Device selection
- Prefer the device or VM the user named.
- If no target was named, run `adb devices` and choose the online serial that matches the intended environment.
- Reconfirm the serial before each new test run if multiple devices are connected.

## Workflow
1. List devices with `adb devices` and choose the active emulator serial.
2. Build and install the requested variant from VS Code terminals or tasks.
3. Resolve and launch the package and activity with adb.
4. Clear logcat before the test run.
5. For each interaction:
   - dump the UI tree
   - summarize it if needed
   - pick coordinates from bounds
   - tap, swipe, or type with adb
6. If a target is missing and the screen is scrollable, scroll, dump again, and search once more before concluding it is absent.
7. Capture screenshots and logcat or crash output when validating behavior or investigating failures.
8. If adb evidence is inconclusive and deeper inspection is needed, switch to Android Studio for manual debugging, then return to VS Code with the findings.

## Coordinate picking
- Prefer helper scripts when they exist in the workspace:
  - `python3 .github/skills/android-vscode-adb-qa/scripts/ui_tree_summarize.py <xml> <summary>`
  - `python3 .github/skills/android-vscode-adb-qa/scripts/ui_pick.py <xml> <target>`
- If helper scripts are unavailable, compute the center of the bounds manually from the UI tree XML.
- Use the UI tree as the source of truth for coordinates, not screenshot pixel inspection.

## Evidence
- Capture and report:
  - device serial
  - package or variant tested
  - actions performed
  - observed result
  - screenshots, logs, or crash evidence
  - exact adb commands used when a failure matters
  - whether Android Studio manual debugging was required

## Output format
Return a compact QA report with:
- device serial and app or variant tested
- actions performed
- observed result
- artifacts captured
- failures or blockers with exact adb or logcat evidence
- whether the result was validated purely from VS Code and adb or required Android Studio fallback
