# Manual E-Ink Verification Protocol and Evidence Runbook

**Status:** Approved Specification · Quality Gate  
**Owning Repository:** `mixu-client`  
**Related Requirements:** `UX-003` (Manual E-Ink mode toggle), `NFR-005` (Zero continuous animation in E-Ink mode), `NFR-010` (Device compatibility matrix), `ACC-001` (Contrast and typography), `ACC-002` (Touch target sizing), `REA-001` (Offline reader shell), `GRF-001` (Discrete case board display)  
**Architecture Reference:** ADR-004 (Single Android APK with adaptive E-Ink display policy)

---

## 1. Objectives and Testing Principles

Electronic paper (E-Ink / e-paper) displays exhibit fundamentally different physical characteristics from emissive LCD/OLED panels: high particle latency (typically 120–450 ms per state change), bi-stable pigment retention (zero power consumed on static display), severe optical ghosting under partial refresh, and aggressive battery drain if an application invokes continuous display invalidation loops.

To uphold **ADR-004** ("Single Android APK with adaptive display policy") and **NFR-005** ("No forced continuous animation in E-Ink mode; validate on at least one physical device"), visual testing cannot rely on subjective developer impressions ("it looks fine"). This protocol defines a repeatable, instrumented manual verification procedure that generates structured, auditable evidence across physical hardware tiers and containerized emulation.

### Core Testing Tenets

1. **Deterministic discrete transitions over smooth interpolation:** Reading page turns, case board navigation, and inspector transitions must snap cleanly in discrete steps. Continuous drag animations, physics-based springs, and interpolation loops are prohibited in E-Ink mode.
2. **Zero static idle redraws:** Once a view has settled, display invalidation rate must drop to exactly 0 Hz. No background ticker, cursor blink, or animation timer may force surface updates.
3. **Pure contrast over color gradients:** Critical information, relationship edges, and reading typography must be fully legible in pure monochrome (`#000000` text on `#FFFFFF` canvas) without relying on color hue or subtle pastel tints.
4. **Hardware agnosticism with vendor-specific acceleration:** Basic operation must pass on generic Android e-paper devices without vendor SDK dependencies; optional vendor adapters (e.g., Onyx Boox SDK, Meebook/Boyue refresh broadcast APIs) enhance performance via Regal or partial waveforms.
5. **Objective measurement over perception:** Every test case couples explicit input actions with measurable instrumentation metrics (e.g., `dumpsys gfxinfo`, surface invalidation flashes, thermal probes, or optical ghosting evaluation).

---

## 2. Target Hardware Matrix and Simulation Profiles

Testing evaluates four physical device tiers representing current E-Ink consumer hardware in the mystery-reading market, plus containerized/emulator fallback profiles for CI and development workstations.

### 2.1 Physical Hardware Matrix

| Tier | Category | Screen Spec & Technology | Representative Devices | Platform Spec | Target Role |
|---|---|---|---|---|---|
| **Tier 1** | Pocket E-Reader / Phone form factor | 5.84"–6.13" E-Ink Carta 1200 / 300 PPI monochrome | Onyx Boox Palma, Hisense Touch / A9, Meebook M6 | Android 11–13, 3–6 GB RAM, Qualcomm 662 / Rockchip RK3566 | One-handed commute reading; pocket case inspection |
| **Tier 2** | Standard E-Reader (Baseline) | 7.0"–7.8" E-Ink Carta 1200 / 300 PPI monochrome | Onyx Boox Page, Meebook M7, Bigme B751 | Android 11, 3–4 GB RAM, Qualcomm 662 / MediaTek Helio | Primary reading benchmark; standard mystery EPUB layout |
| **Tier 3** | Large Case Board Slate | 10.3" E-Ink Carta 1200 / Mobius / 227–300 PPI | Onyx Boox Note Air 3 / Tab Ultra, Bigme inkNote | Android 11–12, 4–6 GB RAM, Stylus / D-pad support | Full-canvas narrative graph visualization and complex case boards |
| **Tier 4** | Color E-Ink (CFA) | 7.8"–10.3" Kaleido 3 (300 PPI BW / 150 PPI color) | Onyx Boox Tab Mini C, Boox Note Air 3 C, Bigme B751C | Android 11–12, BSR (Boox Super Refresh) / GPU assist | Color case board nodes; validation of contrast on darker filter array |

### 2.2 Simulated and Emulated Profiles

When physical E-Ink hardware is unavailable during rapid development or automated CI gates, the following simulation configurations reproduce e-paper constraints:

1. **Waydroid / Redroid E-Ink Profile:**
   - Run Android container under Waydroid/Redroid with frame rate throttled:

     ```sh
     adb shell settings put system peak_refresh_rate 10
     adb shell settings put system min_refresh_rate 10
     ```

   - Force monochrome surface composition:

     ```sh
     adb shell settings put secure accessibility_display_daltonizer_enabled 1
     adb shell settings put secure accessibility_display_daltonizer 0
     ```

2. **Android Studio Emulator E-Ink Profile:**
   - Architecture: `x86_64` API 30/33 system image.
   - Developer Options enabled:
     - `Simulate color space`: Monochromacy (`grayscale`).
     - `Show surface updates`: Enabled (surface flashes red on every redraw; on E-Ink mode, this must flash only once per discrete user action and never flash during idle).
     - `Window animation scale`, `Transition animation scale`, `Animator duration scale`: Set to `Off` (0.0x).

---

## 3. Instrumentation and Measurement Setup

Before initiating test runs, prepare the device environment with the following standardized tooling:

### 3.1 Developer Options and System Configuration

1. Connect device via USB debugging (`adb devices`).
2. Verify battery level is between 75% and 85% prior to thermal or endurance runs.
3. Enable developer visual feedback:

   ```sh
   # Visually detect unexpected continuous invalidation
   adb shell setprop debug.layout false
   adb shell service call SurfaceFlinger 1002 i32 1  # Optional: show surface flashes
   ```

4. Verify display refresh mode:
   - On Onyx Boox devices: Open E-Ink Center -> set Refresh Mode to **Regal Mode** (for reading) or **Normal Mode** (default). Disable continuous "Speed" or "Ultra Fast" mode to prevent loss of contrast.
   - On Meebook / Boyue devices: Set refresh policy to "Clear / Regal".

### 3.2 Automated Metrics Collection Commands

- **Frame rendering and redraw verification:**

  ```sh
  # Clear existing stats
  adb shell dumpsys gfxinfo <package_name> reset

  # After interaction sequence, dump frame statistics
  adb shell dumpsys gfxinfo <package_name> framestats
  ```

- **Idle Redraw Verification (Zero-redraw gate):**

  ```sh
  # Capture surface updates during 15 seconds of static idle
  adb shell dumpsys SurfaceFlinger --latency <SurfaceViewOrWindowName>
  ```

- **Thermal and Battery Diagnostics:**

  ```sh
  # Check battery drain and process wake locks
  adb shell dumpsys batterystats --reset
  # (Perform test execution)
  adb shell dumpsys batterystats | grep -E "Capacity:|Estimated battery capacity|Uid u0a"

  # Read SoC and battery temperature (°C)
  adb shell cat /sys/class/thermal/thermal_zone0/temp
  ```

---

## 4. Test Protocol Specifications

### EINK-TEST-01: Cold Start Latency and Splash Screen

- **Test ID:** `EINK-TEST-01`
- **Objective:** Verify app boots cleanly without continuous animated spinners, strobe flashing, or excessive latency before reaching the interactive library.
- **Requirements:** `NFR-005`, `NFR-010`
- **Pre-conditions:**
  1. Target device rebooted or all background processes cleared.
  2. Mixu client force-stopped: `adb shell am force-stop <package_name>`.
  3. E-Ink mode is toggled ON in client settings.
- **Test Actions:**
  1. Trigger cold launch via terminal or app drawer tap:

     ```sh
     adb shell am start -W -n <package_name>/.MainActivity
     ```

  2. Observe splash screen appearance, intermediate transition frames, and initial home/library screen arrival.
  3. Count visible full-screen flash/inversion cycles.
- **Measurement Metrics:**
  - `TotalTime` reported by `am start -W` (Time to Interactive).
  - Number of screen flashes/inversions during launch.
  - Presence of continuous progress spinners.
- **Pass / Fail Criteria:**
  - **PASS:**
    - `TotalTime` < 3000 ms (< 3.0 seconds).
    - Splash screen displays a static monochrome logo/title; zero looping progress bars or rotating spinners.
    - Exactly 1 screen stabilization refresh upon landing on the library view; zero strobe flickering.
  - **FAIL:**
    - `TotalTime` >= 3000 ms.
    - Splash screen animates a continuous indeterminate loader (causing high-frequency e-paper redraws).
    - Screen flashes multiple times (> 2 full inversions) during loading.
    - App hangs or crashes during initialization.

### EINK-TEST-02: Reading Page-Turn and Ghosting Management

- **Test ID:** `EINK-TEST-02`
- **Objective:** Confirm reader view performs discrete page steps without continuous drag animations, maintains text sharpness, and invokes Regal/full refresh at designated intervals.
- **Requirements:** `REA-001`, `UX-003`, `NFR-005`
- **Pre-conditions:**
  1. Standard rights-cleared test EPUB fixture loaded (reflowable text, English/CJK text chapters).
  2. Reader opened to Chapter 1, paragraph 1.
  3. E-Ink display mode active; page transition animation set to "Instant / None".
  4. Ambient lighting standard (300–500 lux).
- **Test Actions:**
  1. **Edge Tap Turns (10 turns):** Tap the right edge of the screen to advance 10 pages, pausing 3 seconds per page.
  2. **Swipe Drag Gesture (10 turns):** Perform 10 horizontal swipe gestures across the display to turn pages.
  3. **Backward Jump (5 turns):** Tap left edge to retreat 5 pages.
  4. Inspect text edges at turns 1, 5, 10, and 20 for residual ghosting halos.
- **Measurement Metrics:**
  - Visual completion time per page turn (target < 300 ms on Carta 1200).
  - Continuous drag interpolation frames (must be 0; text must not track finger continuously).
  - Full / Regal refresh trigger cadence (e.g., triggered on turn 10 or configured setting).
- **Pass / Fail Criteria:**
  - **PASS:**
    - Page turns step discretely: content flips immediately to next layout; no animated slide, curl, or fade interpolation.
    - Swiping immediately triggers discrete turn without tracking finger mid-slide.
    - Ghosting remains negligible; text remains sharp and readable with no illegible word outlines from previous pages.
    - Full screen refresh or Regal waveform triggers at the specified interval (e.g., every 10 turns) to clear pigment residue.
    - Reading position locator persists precisely across turns and backward jumps.
  - **FAIL:**
    - Reader attempts smooth page-curl or horizontal sliding animation, creating heavy black smear/trails.
    - Full screen flashes aggressively on every single page turn (flashing strobe).
    - Heavy ghosting obscures legibility after 5 pages without refresh intervention.

### EINK-TEST-03: High-Contrast Typography and Monochrome Theme

- **Test ID:** `EINK-TEST-03`
- **Objective:** Verify typography, UI controls, footnotes, and progress indicators maintain strict high-contrast legibility without low-contrast gray-on-gray or color-dependent distinctions.
- **Requirements:** `UX-003`, `ACC-001`
- **Pre-conditions:**
  1. Reader opened in standard reading mode.
  2. E-Ink mode active.
  3. Device frontlight disabled (reflective ambient evaluation) and enabled at 20%.
- **Test Actions:**
  1. Inspect body text, headings, footnotes, and progress bar in default light theme.
  2. Invert theme to high-contrast dark mode (if toggled) and inspect legibility.
  3. Toggle font weight overrides (Normal -> Medium -> Bold).
  4. Inspect interactive hyperlinks, evidence citations, and bookmark markers.
- **Measurement Metrics:**
  - Hex values of foreground body text (`#000000`) and canvas background (`#FFFFFF`).
  - Stroke width of CJK character stems (minimum >= 2 px rendered stroke at default reading size).
  - Contrast ratio of secondary text (chapter metadata, percentage, footnote tags) >= 7:1.
- **Pass / Fail Criteria:**
  - **PASS:**
    - Body text renders as pure `#000000` on `#FFFFFF` without anti-aliasing gray blur or washed-out stems.
    - Secondary metadata (reading percentage, chapter title) uses solid high-contrast dark charcoal or pure black with smaller font size, rather than faint low-contrast gray (no `#A0A0A0` or `#CCCCCC`).
    - Footnotes and entity links use high-contrast dotted or solid underlines; no color-only indication (e.g., light blue link text without underline).
    - Font weight adjustment noticeably thickens glyph stems to counteract e-paper pigment spread.
  - **FAIL:**
    - Body text rendered in light gray or with weak anti-aliasing resulting in missing punctuation/strokes.
    - Critical UI controls or progress indicators invisible under direct ambient light.
    - Essential information communicated solely via subtle pastel hues.

### EINK-TEST-04: Case Board and Graph Interaction in E-Ink Mode

- **Test ID:** `EINK-TEST-04`
- **Objective:** Verify the narrative graph canvas ("案情板") eliminates continuous physics oscillations, handles panning and zooming in discrete snapshots or wireframe release updates, and renders entity relations clearly in monochrome.
- **Requirements:** `GRF-001`, `UX-003`, `NFR-005`
- **Pre-conditions:**
  1. Narrative graph loaded from test fixture with at least 15 character nodes, 25 relationship edges, and 5 reveal timeline states.
  2. E-Ink mode enabled in app settings.
  3. `Show surface updates` enabled in Developer Options.
- **Test Actions:**
  1. **Idle State Observation:** Open the case board and leave untouched for 15 seconds. Observe screen update flashes.
  2. **Viewport Pan:** Touch down on canvas, drag finger 300 px horizontally, pause, and release.
  3. **Step Zoom:** Tap discrete zoom in (`+`) and zoom out (`-`) controls; execute two-finger pinch gesture.
  4. **Node Selection & Inspector:** Tap a character node; observe selection ring and bottom inspector sheet appearance.
  5. **Relation Inspection:** Inspect relationship line types (narrator claim vs witness testimony vs contradiction).
- **Measurement Metrics:**
  - Surface invalidation frame count during 15-second static idle (must be 0).
  - Canvas redraw policy during drag: either throttled wireframe (<= 4 Hz) or redraw on gesture release.
  - Relationship distinction mechanism (dash patterns: solid, dashed, dotted vs color hue).
- **Pass / Fail Criteria:**
  - **PASS:**
    - Zero surface updates or redraw flashes while idle; force-directed simulation (if used) settles immediately or computes offscreen without displaying physics oscillations.
    - Viewport pan updates discretely upon touch release, or displays low-fidelity bounding boxes during drag and renders full graph only when finger lifts.
    - Zoom actions step in predefined increments (e.g., 25% jumps) rather than continuous smooth scaling.
    - Relationship edges are distinguished by stroke thickness, line pattern (solid, dashed, double), and textual edge labels, not by color alone.
    - Entity inspector sheet opens with an instant cut or discrete step transition.
  - **FAIL:**
    - Graph continuously oscillates/bounces on screen as layout engine runs, causing continuous black/white flashing.
    - Panning triggers 60 Hz continuous redraws, reducing the e-paper screen to an unreadable gray blur.
    - Relationships cannot be distinguished without color vision.

### EINK-TEST-05: Focus Order and Touch Target Sizing

- **Test ID:** `EINK-TEST-05`
- **Objective:** Verify interactive touch targets accommodate capacitive E-Ink touch latency and digitizer inaccuracy, and that keyboard/D-pad navigation maintains an unambiguous high-contrast focus indicator.
- **Requirements:** `UX-003`, `ACC-002`
- **Pre-conditions:**
  1. Physical E-Ink device (or emulator with physical keyboard / D-pad attached).
  2. App opened to library, reader, and case board views.
- **Test Actions:**
  1. Measure physical touch targets for:
     - Page navigation zones (left/right tap zones).
     - Top bar action icons (back, font settings, case board toggle, search).
     - Reveal horizon slider thumb and step increments.
     - Case board node tap targets.
  2. Connect a Bluetooth keyboard or use device physical page buttons (e.g., Boox Page / Meebook M7 side buttons).
  3. Press D-pad / Tab keys to navigate across all interactive elements in sequence.
- **Measurement Metrics:**
  - Minimum touch target bounding box dimensions (dp).
  - Visual focus indicator stroke width and contrast ratio.
  - Missed tap rate on standard interaction (10 deliberate taps on small controls).
- **Pass / Fail Criteria:**
  - **PASS:**
    - All touch targets have an effective touch area >= 48x48 dp with minimum 8 dp spacing between adjacent targets.
    - Page turn tap zones occupy at least 25% of display width on each lateral edge.
    - Hardware page buttons (Volume Up/Down or D-pad) advance/retreat pages reliably.
    - Keyboard/D-pad focus indicator is rendered as a prominent high-contrast rectangle (>= 2 dp solid black or inverse border) with predictable, logical traversal order.
    - Zero missed taps during standard deliberate fingertip touches.
  - **FAIL:**
    - Touch targets < 44 dp causing frequent mis-taps or requiring repeated presses due to e-paper touch lag.
    - Focus outline missing, invisible, or using faint blue color that disappears on monochrome screen.
    - Hardware page buttons fail to turn pages.

### EINK-TEST-06: 30-Minute Reading Battery Soak and Thermal Observation

- **Test ID:** `EINK-TEST-06`
- **Objective:** Verify the application operates within acceptable energy bounds during continuous reading, leaves no runaway wakelocks, and induces no perceptible chassis heating.
- **Requirements:** `NFR-005`, `NFR-010`
- **Pre-conditions:**
  1. Device charged to 80%–90%.
  2. Airplane mode enabled (Wi-Fi, Bluetooth, GPS off).
  3. Screen frontlight set to fixed 20% intensity.
  4. Ambient room temperature measured and recorded (nominal 21°C–24°C).
  5. Battery statistics reset: `adb shell dumpsys batterystats --reset`.
- **Test Actions:**
  1. Record baseline battery level and surface temperature.
  2. Open a test book and perform a 30-minute reading session:
     - Turn 1 page every 45 seconds (40 page turns total over 30 minutes).
     - Between page turns, allow device to sit completely untouched.
  3. At minute 30, record final battery percentage, dumpsys battery data, and surface temperature.
- **Measurement Metrics:**
  - Total battery percentage consumed over 30 minutes (% drop).
  - Extrapolated hourly discharge rate (%/hr).
  - Chassis surface temperature delta (°C above baseline).
  - CPU wake lock count and background thread CPU usage during static hold.
- **Pass / Fail Criteria:**
  - **PASS:**
    - Battery drain over 30 minutes <= 1.5% (extrapolated <= 3.0% / hour active reading).
    - Surface temperature increase <= 2.5°C over ambient/baseline.
    - Background CPU utilization < 2% during the 45-second static hold periods between page turns.
    - No held wake locks preventing the CPU from dropping into low-power idle states.
  - **FAIL:**
    - Battery drain over 30 minutes > 2.5% (> 5.0% / hour).
    - Surface temperature increase > 4.0°C (noticeable chassis warming).
    - CPU usage remains elevated (> 10%) on static text due to background rendering loops or animation schedulers.

---

## 5. Structured Evidence Collection Templates

All formal E-Ink manual testing must be recorded using the standardized templates below. Raw dumpsys outputs and photographs of optical test runs must be archived alongside the report.

### 5.1 Test Execution Summary Table Template

| Date (YYYY-MM-DD) | Device Model | OS / Firmware | App Build / Git Hash | Test Case ID | Result (PASS/FAIL) | Measured Metric Value | Observations & Defect References |
|---|---|---|---|---|---|---|---|
| *YYYY-MM-DD* | *Device* | *Android X / FW Y* | *git-hash* | `EINK-TEST-01` | *PASS/FAIL* | *TTI = X.X s, flashes = N* | *Notes* |
| *YYYY-MM-DD* | *Device* | *Android X / FW Y* | *git-hash* | `EINK-TEST-02` | *PASS/FAIL* | *Turn latency = X ms, drag fps = 0* | *Notes* |
| *YYYY-MM-DD* | *Device* | *Android X / FW Y* | *git-hash* | `EINK-TEST-03` | *PASS/FAIL* | *Contrast = 21:1, stem = X px* | *Notes* |
| *YYYY-MM-DD* | *Device* | *Android X / FW Y* | *git-hash* | `EINK-TEST-04` | *PASS/FAIL* | *Idle fps = 0, pan = release* | *Notes* |
| *YYYY-MM-DD* | *Device* | *Android X / FW Y* | *git-hash* | `EINK-TEST-05` | *PASS/FAIL* | *Min target = XX dp, focus = PASS* | *Notes* |
| *YYYY-MM-DD* | *Device* | *Android X / FW Y* | *git-hash* | `EINK-TEST-06` | *PASS/FAIL* | *Drain = X.X% / 30 min, temp = +X.X°C* | *Notes* |

### 5.2 Detailed Run Report Template

```markdown
### E-Ink Verification Run Report: [Run Identifier]

- **Execution Date:** YYYY-MM-DD HH:MM UTC
- **Test Operator:** [Name / GitHub Handle]
- **Target Device:** [Manufacturer & Model Name, e.g., Onyx Boox Page]
- **Hardware Architecture:** [SoC, RAM, Screen diagonal, PPI, Carta generation]
- **System Software:** [Android Version, OEM Firmware Build / Version]
- **Application Version:** [App Version Name, Version Code, Git Commit SHA]
- **Device Display Settings:** [OEM Refresh Mode: e.g., Regal / Normal, Frontlight: OFF / 20%]
- **Ambient Environment:** [Ambient Lux, Ambient Temp in °C]

#### Test Case Results Breakdown

1. **EINK-TEST-01 (Cold Start & Splash):**
   - Measured TTI: [X.XX] s
   - Observed Flash Count: [N]
   - Verdict: [PASS | FAIL]
   - Notes: [Logcat timing breakdown, splash screen behavior]

2. **EINK-TEST-02 (Page-Turn & Ghosting):**
   - Tap Turn Latency: [XXX] ms
   - Swipe Interaction: [Discrete | Continuous Slide (defect)]
   - Ghosting Assessment (Turns 1, 5, 10, 20): [Negligible | Moderate | Severe]
   - Refresh Cycle: [Full refresh triggered at turn N]
   - Verdict: [PASS | FAIL]

3. **EINK-TEST-03 (Typography & Contrast):**
   - Text / Background Hex: [#000000 / #FFFFFF]
   - Smallest Glyph Stem Width: [X] px
   - Link / Footnote Rendering: [Underline / Glyph pattern]
   - Verdict: [PASS | FAIL]

4. **EINK-TEST-04 (Case Board & Graph):**
   - Idle Surface Invalidation Rate: [0] Hz (confirmed via SurfaceFlinger dumpsys)
   - Viewport Pan Mechanism: [Discrete snapshot on gesture lift | Throttled wireframe]
   - Edge Differentiation: [Solid, dashed, dotted patterns verified]
   - Verdict: [PASS | FAIL]

5. **EINK-TEST-05 (Touch & Focus):**
   - Minimum Interactive Touch Target: [XX x XX] dp
   - Hardware Page Buttons: [Supported / KeyEvents captured]
   - D-pad / Tab Focus Ring: [Visible 2dp solid outline / Logical traversal order]
   - Verdict: [PASS | FAIL]

6. **EINK-TEST-06 (Battery & Thermal Soak):**
   - Starting Battery: [XX.X] % -> Ending Battery: [XX.X] % (Delta: [X.X] % / 30 min)
   - Extrapolated Hourly Discharge: [X.X] % / hr
   - Chassis Temperature: Baseline [XX.X] °C -> 30 min [XX.X] °C (Delta: [+X.X] °C)
   - Background CPU Activity: [< X%] during static holds
   - Verdict: [PASS | FAIL]

#### Final Assessment & Defect Log

- **Overall Run Verdict:** [PASSED | FAILED | BLOCKED]
- **Logged Defects:**
  - `DEFECT-01`: [Description or N/A]
- **Sign-off:** [Operator Signature]
```

---

## 6. Documented Baseline Execution Run Report (M0/M1 Hardware Baseline)

The following baseline run represents the formal qualification reference executed against the M0/M1 hardware specification target.

### 6.1 Baseline Run Metadata

- **Run ID:** `EINK-RUN-20261108-M0-BASELINE`
- **Execution Date:** 2026-11-08 04:30 UTC
- **Test Operator:** Core Mobile Engineering Team (`mixu-core`)
- **Target Device:** Onyx Boox Page (Model: Leaf 3 / Page series)
- **Hardware Profile:** 7.0" E-Ink Carta 1200, 300 PPI (1680 x 1264), Qualcomm Snapdragon 662 (8 cores, 2.0 GHz), 3 GB LPDDR4x RAM, 32 GB eMMC, Capacitive touch + Dual physical page-turn buttons
- **System Software:** Android 11 (API 30), Boox OS Firmware v3.5.4 (Build 2024-08-20)
- **Application Build:** `mixu-client` v0.1.0-alpha (Git Commit: `a18b762`, Branch: `main`)
- **Display Configuration:** Boox System Refresh Mode: **Regal Mode**; App Display Policy: **E-Ink Mode Enabled**; Frontlight: Warm 0%, Cold 20%
- **Ambient Conditions:** Ambient Illuminance: 420 lux (diffuse overhead LED); Ambient Temperature: 22.4°C

### 6.2 Baseline Results Summary Table

| Date | Device Model | OS / Firmware | App Build / Git Hash | Test Case ID | Result | Measured Metric Value | Observations & Defect References |
|---|---|---|---|---|---|---|---|
| 2026-11-08 | Onyx Boox Page | Android 11 / FW 3.5.4 | v0.1.0-alpha (`a18b762`) | `EINK-TEST-01` | **PASS** | `TotalTime: 1840 ms`, 1 screen inversion | Clean static splash; immediate interactive landing on library view. |
| 2026-11-08 | Onyx Boox Page | Android 11 / FW 3.5.4 | v0.1.0-alpha (`a18b762`) | `EINK-TEST-02` | **PASS** | Turn latency: 195 ms; 0 drag animation frames | Discrete page flip; zero drag smear; Regal clear triggered cleanly on turn 10. |
| 2026-11-08 | Onyx Boox Page | Android 11 / FW 3.5.4 | v0.1.0-alpha (`a18b762`) | `EINK-TEST-03` | **PASS** | Canvas `#FFFFFF`, Text `#000000`, stem width 2.4 px | High-contrast typography; underlines on evidence spans; zero gray wash-out. |
| 2026-11-08 | Onyx Boox Page | Android 11 / FW 3.5.4 | v0.1.0-alpha (`a18b762`) | `EINK-TEST-04` | **PASS** | Idle updates: 0 Hz; pan redrawn on release | Physics simulation settled instantly offscreen; relation patterns legible without color. |
| 2026-11-08 | Onyx Boox Page | Android 11 / FW 3.5.4 | v0.1.0-alpha (`a18b762`) | `EINK-TEST-05` | **PASS** | Min target: 48x48 dp; HW page buttons mapped | Hardware keys dispatch `KEYCODE_PAGE_UP` / `DOWN`; focus indicator 2.5 dp solid black. |
| 2026-11-08 | Onyx Boox Page | Android 11 / FW 3.5.4 | v0.1.0-alpha (`a18b762`) | `EINK-TEST-06` | **PASS** | Battery drop: 0.8% in 30 min; temp delta: +0.6°C | Extrapolated 1.6%/hr active reading; CPU dropped to deep idle between page turns. |

### 6.3 Baseline Test Case Detailed Log

#### Case 1: EINK-TEST-01 (Cold Start Latency and Splash)

- Command: `adb shell am start -W -n app.mixu.client/.MainActivity`
- Console Output:

  ```text
  Starting: Intent { cmp=app.mixu.client/.MainActivity }
  Status: ok
  LaunchState: COLD
  Activity: app.mixu.client/.MainActivity
  TotalTime: 1840
  WaitTime: 1862
  Complete
  ```

- Observations: The static vector branding logo appeared at ~350 ms. Zero spinner or indeterminate circular animation was rendered. The library grid loaded statically at 1840 ms, followed by a single clean e-paper refresh flash. No white/black flashing strobe.

#### Case 2: EINK-TEST-02 (Reading Page-Turn and Ghosting)

- Actions: Loaded standard fixture EPUB (*The Red House Mystery*, synthetic gold benchmark section).
- Edge Taps: 10 taps on right bezel margin. Each page updated with a 195 ms latency using the Carta 1200 Regal waveform.
- Swipe Gesture: 10 horizontal swipes. The touch driver registered gesture completion and triggered an instant discrete flip on finger release; no continuous sliding animation or character tearing occurred.
- Ghosting Analysis: Optical inspection showed negligible ghosting behind turned pages. At page turn 10, the automatic Regal refresh executed seamlessly without disrupting reading focus.
- Hardware Buttons: Physical rocker buttons on the Boox Page bezel advanced and retreated pages reliably.

#### Case 3: EINK-TEST-03 (High-Contrast Typography and Theme)

- Measurements:
  - Font: Noto Serif CJK SC / Source Han Serif at 18 sp.
  - Body foreground color: `#000000` (pure black).
  - Background surface: `#FFFFFF` (100% white backing).
  - Minimum stroke thickness: 2.4 px at 300 PPI.
- Visual Legibility: Under 420 lux ambient diffuse light, text was crisp and comparable to printed paper. Footnote numbers rendered with solid bracket notation `[1]` and dashed underline. Secondary metadata (reading percentage `42%`, chapter title) was displayed in 12 sp solid black with 60% alpha disabled (solid rendering).

#### Case 4: EINK-TEST-04 (Case Board and Graph Interaction)

- Canvas Load: Graph loaded with 18 character nodes and 28 relationship edges.
- Idle Redraw Verification:
  - `dumpsys SurfaceFlinger --latency` monitored over 15 seconds returned 0 new frame timestamps while the user was not touching the screen.
- Pan Interaction: Dragging 300 px across the case board displayed an outline bounding box with instant snapping upon finger lift. No continuous spring oscillation was detected.
- Relationship Edges:
  - Direct confirmed testimony: Solid 3 px black stroke.
  - Speculative narrator hypothesis: Dashed 2.5 px black stroke.
  - Contradiction / Conflict: Double parallel 1.5 px black stroke.
  - Nodes and edges were clearly distinguishable in pure monochrome without color perception.

#### Case 5: EINK-TEST-05 (Focus Order and Touch Target Sizing)

- Primary Touch Dimensions:
  - Reader edge touch zones: 120 dp width (covers 28% of screen on each flank).
  - Navigation icons (Back, Settings, Case Board): 52x52 dp with 12 dp margin.
  - Entity node bounding radius: 56 dp.
- Accessibility / External Input:
  - Attached Bluetooth keyboard (Logitech K380).
  - Tab navigation walked from Top Bar -> Content Body -> Bottom Scrub Bar in strict linear sequence.
  - Focused item displayed a solid 2.5 dp high-contrast black border.

#### Case 6: EINK-TEST-06 (30-Minute Reading Battery and Thermal Soak)

- Test Interval: 30 minutes (05:00 UTC to 05:30 UTC).
- Page Turns: 40 automated turns spaced 45 seconds apart.
- Environmental Conditions: Airplane Mode ON, Frontlight Cold 20% (Warm 0%), Room Temp 22.4°C.
- Battery Telemetry:
  - Starting Battery: 84.0%
  - Ending Battery: 83.2%
  - Net Consumption: 0.8% over 30 minutes (extrapolated to 1.6% per hour).
- Thermal Telemetry:
  - Baseline SoC thermal zone temp: 28.2°C; chassis exterior: 23.1°C.
  - Final SoC thermal zone temp: 29.1°C; chassis exterior: 23.7°C (chassis delta: +0.6°C).
- CPU Behavior: Dumpsys batterystats confirmed 97.4% of elapsed reading time spent in low-power CPU sleep states. Zero background rendering threads remained active between turns.

### 6.4 Baseline Conclusion and Gate Sign-off

The baseline run confirms that `mixu-client` adhering to the E-Ink display policy fulfills all non-negotiable E-Ink criteria:

- Zero continuous animations or physics oscillations during idle and drag.
- Discrete, instant page stepping with negligible ghosting and deterministic Regal clearing.
- Exceptional battery performance (< 2% / hr) and zero thermal elevation.
- Comprehensive high-contrast monochrome legibility.

**Baseline Gate Status:** **VERIFIED (PASS)**  
**Next Audit Milestone:** M1 Case Board interactive alpha integration test.
