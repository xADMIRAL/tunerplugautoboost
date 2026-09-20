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

The **Anti-lag** tab: a drift mode, three autotune goals, and advanced settings behind a checkbox.

* **Drift modes: light / medium / hard.** A mode fills the MS3 ECU settings (anti-lag, flat shift,
  over-run groups, each can be unticked and every value edited before writing) and the three
  goals. Light 120 kPa / hold 2500 rpm / 2 s, medium 135 / 3000 / 3, hard 150 / 3500 / 5; the
  ALS TPS and RPM limits, pause, MAT cut-off, `als_timing` (-12/-16/-22), `als_addfuel`,
  `als_sparkcut`, idle-valve air and the flat-shift hard limit scale with the mode. The "Off
  (street)" preset switches it all back. Old values are kept for **Restore original**; burn in
  TunerStudio when happy.
* **Goals.** *Boost off throttle* (kPa absolute) is tuned with ignition retard per RPM column of
  `als_timing`; *do not let RPM fall below* is tuned with the throttle opening during ALS on drive-by-wire
  (`als_iac_pos`, % TPS, when `drivebywire_opt_on` is on; `als_maxtps` is kept above the opening)
  or with idle-valve air otherwise (`als_iac_steps` or `als_iac_duty`, picked by `IdleCtl`), and
  `als_minrpm` is written 700 rpm below it; *hold for*
  (seconds) is written to `als_maxtime`. A run is 3-5 full lifts from WOT held closed for about
  the hold time plus one second; the report shows boost and the lowest RPM per event and the new
  retard and air; done after two good runs in a row. Guards: MAT warning 70 °C / abort 80 °C
  (`mat` channel), stall guard 1200 rpm, overboost 200 kPa, at most 30 s of active anti-lag per
  run; an abort restores the original settings and sets `als_in_pin = Off`. ALS activity comes
  from bit 128 of `status10`, or TPS <= 12 % without it.

* **Popcorn presets.** Two more entries in the drift-mode list write ECU settings only, no
  goals. *Popcorn - over-run window (street)* keeps the over-run cut but opens a window first
  (`fc_delay` 0.3 s, `fc_transition_time` 2.5 s) in which the timing ramps to `fc_timing` -20 deg
  and the injectors are dropped progressively; the pops last the window, then the quiet cut takes
  over; above `fc_rpm` 2500 and below `fc_kpa` 45 only, fuel back by `fc_rpm_lower` 1500, ALS
  switched off. *Popcorn - anti-lag always on (loud)* arms the ALS permanently without the air
  (`als_opt_idle = Off`): 30 % spark cut, +12 % fuel, -18 deg, 2 s per lift, 2500..6000 rpm, and
  `OvrRunC = Off` because the pops need fuel.

Anti-lag cooks the turbo, the manifold and the catalyst: short runs, cool-down laps, a working
intake air temperature sensor. Not for the street.

## Claude assistant

The **Assistant** tab is a chat with Claude inside TunerStudio. The model sees the connected
ECU through the plugin: it finds and reads any INI parameter or table (`list_parameters`,
`read_parameters`, tables come with their axes), watches live channels and their recent history
(`read_channels`, `channel_history`), and proposes edits (`propose_change` for scalars, options,
axes and table fills; `propose_table_cells` for single cells). Every proposal lands in the
*Proposed changes* table with a reason; you apply it (*Apply selected* / *Apply all pending*),
reject it, or put everything back (*Restore all applied*). It cannot burn: that stays a
TunerStudio button.

Settings: the API key (kept in your user preferences, never in the plugin's settings file), the
model (`claude-opus-5` by default; `claude-fable-5-1` or `claude-sonnet-5` are listed), a base
URL for a proxy or gateway, `effort` (empty = the API default), *Refusal fallbacks* (server-side
fallback when the model declines, beta) and *Auto-apply* (write proposals at once, off by
default). *Car profile and notes* goes into the system prompt of every conversation. The
assistant answers in the language you write in, moves in small steps (at most +2 deg timing,
+20 kPa boost target, 5 % VE per step) and must read a parameter before proposing a value.
Requests go to the Messages API over HTTPS with the JDK alone: no third-party libraries in the
jar, the JVM's proxy settings apply. Ctrl+Enter sends.

## Knock sensor calibration

**Knock: sensor calibration** (the **Knock** tab) sets the knock input itself: the per-cylinder
gains `knock_gain01..06` and the threshold curve `knock_thresholds` over `knock_rpms`. A run is
2-3 full-throttle pulls on a timing map that does not knock; everything the sensor hears is taken
as the engine's noise, collected per RPM bin (per cylinder from `knock_cyl01..06` when the ECU
links knock to cylinders) at load >= `knk_minload` inside `knk_lorpm..knk_hirpm`.

* **Survey (gains).** The loudest bin / cylinder is brought to a 40 % (30..55) noise level,
  each gain moving by at most x2 per run to the nearest `$KNOCK_GAIN` option; a cylinder more
  than 20 % quieter than the loudest one is turned up so one curve fits all. The thresholds scale
  with the gains meanwhile, so the protection stays what it was.
* **Verify (thresholds).** Threshold = noise p95 x 1.3 (at least the loudest plain sample + 3)
  per bin, unmeasured bins from their neighbours, smoothed so no bin dips under its neighbours.
  Then more pulls: a bin whose plain samples cross the threshold (one stray sample is tolerated)
  or where the ECU's knock retard fired is raised 10 %; a bin far above the noise is tightened
  once. Done after two clean runs in a row.

Isolated spikes (> 1.35 x median + 3) are reported as possible knock and kept out of the noise;
a bin with three or more keeps its threshold and the session asks for the timing map to be fixed.
The Analysis tab shows the curve and the per-cylinder gains before / after; *Restore original*
puts both back. It cannot tell a bad sensor or a knocking engine from a noisy one: listen too.
Frequency, window and integrator settings are not touched.

## TunerStudio dashboards

`dash/` holds three ready dashboards for the Stealth PCM (firmware signature taken from your own
dashboard file, currently `MS3 Format DM00.23f`): `track_boost.dash` (road tuning: RPM, MAP, AFR,
boost target and duty, timing, knock, temperatures, pressures, limiter and fault indicators),
`idle_injectors_throttle.dash` (idle target and valve, injector duty and pulse width, VE, EGO
correction, throttle / pedal / DBW target, accel enrichment and idle state indicators) and
`drift_antilag.dash` (RPM, MAP, MAT with the anti-lag limits, EGT, timing, ALS / launch / flat
shift and cut indicators). Load one with a right click on a dashboard → *Load Dashboard…*.
Regenerate for another signature with `python3 dash/generate_dashboards.py your.dash "MS3 Format …"`.

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
