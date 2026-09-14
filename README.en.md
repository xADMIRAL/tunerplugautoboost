# Boost Autotune for TunerStudio

A TunerStudio plugin that does for boost what VE Analyze does for fuel: pick the target boost
(one stage or several, e.g. `150, 170` kPa), make a few full-throttle pulls, and the plugin
characterizes the wastegate valve, fills the closed-loop bias (feed-forward) duty table, builds
the target table, settles the PID gains and tells you when each stage has converged.

Primary target: **MS3 / Stealth PCM (`MS3 Format DM00.22f`)** with all parameter and channel
names preset. Best-effort presets exist for stock MS3 1.5+, Speeduino and rusEFI.

## Workflow

1. **Open-loop characterization (2–3 runs).** The ECU is switched to open loop, the duty table is
   filled with a fixed duty (20 %, then +15 % per run). Steady samples build a duty → boost model
   per RPM column. The next rung is only planned if its predicted boost stays under
   `hard limit − prediction margin`; the ladder stops once the highest target was exceeded.
2. **Bias table** = lowest duty that reaches each (RPM, target) cell; the knee is used where the
   turbo is maxed out; empty columns borrow from neighbours.
3. **Closed loop per stage.** Target table (with a spool ramp from wastegate pressure), closed
   loop enabled (`Closed-loop`, `Advanced Mode`, control `On` on MS3), soft-started PID. After
   every run: overshoot / rise time / steady-state error / oscillation → relative PID steps, bias
   refinement from lag-compensated steady samples, "spool trim" for single-bump overshoot, and
   trimming of unreachable targets. A stage converges after two consecutive good runs.
   The simulator test converges 150 → 170 kPa in 9 runs with the classic ramp, 14 with fast spool.
4. **Fastest spool to target** (on by default, Boost tab). A target ramp makes the loop crack the
   wastegate open early, and the MS3 PID only acts inside the `boost_ctl_lowerlimit` window below
   the target (below it the duty is the bias table alone). So the plugin holds the valve shut
   (maximum duty) in every bias cell whose target is out of reach, writes a flat stage target
   right above the spool-start RPM instead of a ramp, records at which RPM and how many seconds
   after WOT the target arrived, and once the loop is settled spends up to 4 extra runs pushing one
   knob at a time: relax the spool trim → add feed-forward around the RPM where the target arrives
   → raise P → widen the closed-loop window. A push is kept while the target arrives at least
   50 rpm earlier without overshoot beyond the limit; a push that brings overshoot is reverted.
   A rise bump that survives a deep trim gets anticipation (D up, I down) and a narrower window.
   In the simulator 170 kPa arrives about 230 rpm earlier than with the ramp.

## VVT and ignition modes

* **VVT PID (normal driving):** a run is a minute or two of varied driving; the cam angle is
  compared with its target (steady accuracy, ringing, cross-correlation lag) and the gains are
  moved in relative steps until two good runs in a row.
* **VVT target sweep (pulls):** offsets (`0, -10, -5, +5, +10` deg) are applied to the WOT rows
  of the cam table one run at a time; engine acceleration (dRPM/dt per RPM bin, same gear, same
  road) picks the best cam timing per bin; the result is smoothed and written back.
* **Ignition advance sweep with knock guard (pulls):** offsets (`0, -2, +2, +4` deg, two passes)
  on the WOT rows of the spark table, decided by the MBT rule (least advance within 0.5 % of the
  best torque). Any ECU knock retard caps the cell below the advance that knocked; heavy knock
  (>= 3 deg retard) or a lean WOT mixture aborts and restores the original table; no cell is ever
  advanced more than +4 deg over its original value. Not a dyno; needs working knock control and a
  wideband.

## Anti-lag and drift mode

The **Anti-lag** tab has two parts: ready-made drift presets written to the ECU in one click, and
the settings of the anti-lag autotune (mode **Anti-lag: off-throttle boost autotune**).

* **Drift presets (MS3 / Stealth PCM names).** Three groups, each can be unticked and every value
  can be edited before writing: *Anti-lag* (`als_in_pin`, arm above `als_acttps` 60 % TPS, operate
  below `als_maxtps` 12/15 %, 2500-6500/7000 rpm, `als_maxtime` 3/5 s, `als_pausetime`, CLT/MAT
  limits, cyclic spark cut `als_opt_sc` on, idle valve air `als_opt_idle` with `als_iac_steps`
  100/140, axes `als_rpms`/`als_tpss`, `als_timing` -16/-22 deg, `als_addfuel` 15/25 %,
  `als_sparkcut` 30/50 %), *Flat shift* (`launch_opt_on = Launch/Flatshift`, spark cut, arm 3500,
  hard limit 6500/7000, `flats_deg` -5) and *Over-run* (`OvrRunC = Off`, the over-run fuel cut
  fights the anti-lag). The "Off (street)" preset switches it all back. Old values are kept for
  **Restore original**; burn in TunerStudio when happy.
* **Anti-lag autotune.** Goal: hold a chosen manifold pressure (default 130 kPa absolute) off
  throttle while the anti-lag is active with the least ignition retard. Knobs: the RPM columns of
  `als_timing` (rows with TPS <= 20), then idle valve air (`als_iac_steps` or `als_iac_duty`,
  picked by `IdleCtl`) once a column hits the retard limit. A run is 3-5 full lifts from WOT
  (arm above the ALS TPS, close the throttle for 2-3 s); the report shows measured boost, error
  and the new retard per column; done after two good runs in a row. Guards: MAT warning 70 °C /
  abort 80 °C (`mat` channel), stall guard 1200 rpm, overboost 200 kPa, at most 30 s of active
  anti-lag per run; an abort restores the original table and sets `als_in_pin = Off`. ALS activity
  comes from bit 128 of `status10`, or TPS <= 12 % without it. The simulator converges in
  7 runs.

Anti-lag cooks the turbo, the manifold and the catalyst: short runs, cool-down laps, a working
intake air temperature sensor. Not for the street.

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
