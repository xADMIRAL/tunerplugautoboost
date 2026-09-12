# Boost Autotune for TunerStudio

A TunerStudio plugin that does for boost what VE Analyze does for fuel: pick the target boost
(one stage or several, e.g. `150, 170` kPa), make a few full-throttle pulls, and the plugin
characterizes the wastegate valve, fills the closed-loop bias (feed-forward) duty table, builds
the target table, settles the PID gains and tells you when each stage has converged.

Primary target: **MS3 / Stealth PCM (`MS3 Format DM00.22f`)** with all parameter and channel
names preset. Best-effort presets exist for stock MS3 1.5+, Speeduino and rusEFI.

## Workflow

1. **Open-loop characterization (2–3 runs).** The ECU is switched to open loop, the duty table is
   filled with a fixed duty (20 %, then +20 % per run). Steady samples build a duty → boost model
   per RPM column. The next rung is only planned if its predicted boost stays under
   `hard limit − prediction margin`; the ladder stops once the highest target was exceeded.
2. **Bias table** = lowest duty that reaches each (RPM, target) cell; the knee is used where the
   turbo is maxed out; empty columns borrow from neighbours.
3. **Closed loop per stage.** Target table (with a spool ramp from wastegate pressure), closed
   loop enabled (`Closed-loop`, `Advanced Mode`, control `On` on MS3), soft-started PID. After
   every run: overshoot / rise time / steady-state error / oscillation → relative PID steps, bias
   refinement from lag-compensated steady samples, "spool trim" for single-bump overshoot, and
   trimming of unreachable targets. A stage converges after two consecutive good runs.
   The simulator test converges 150 → 170 kPa in 9 runs.

## Install

Copy `BoostAutotune.jar` to `~/.efianalytics/TunerStudio/plugins/` (Windows:
`C:\Users\<you>\.efianalytics\TunerStudio\plugins\`) or to the `plugins` folder of the TunerStudio
installation, restart TunerStudio, open the project: **Tools → Boost Autotune**.

Build: `mvn -q verify` → `boost-autotune-plugin/target/BoostAutotune.jar` (JDK 8+ bytecode).
Try it without a car:
`java -cp boost-autotune-plugin/target/BoostAutotune.jar io.github.xadmiral.boostautotune.plugin.DemoLauncher`

## Safety

The plugin writes boost tables and gains to ECU RAM between runs. Above the hard limit it aborts
and forces open loop at minimum duty. Keep the ECU overboost cut enabled and above the plugin's
hard limit, keep your foot ready, and use **Restore original** to undo everything.

See `README.md` (Russian) for the full tab-by-tab description, ECU preparation notes and known
limitations (table orientation for square tables, axis bins are not rewritten, Speeduino/rusEFI
presets untested on hardware).

MIT license.
