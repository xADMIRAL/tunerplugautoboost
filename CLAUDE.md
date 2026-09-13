# Boost Autotune (TunerStudio plugin) — project notes for Claude

## Delivery rule
After every code change: run the full build with tests (`mvn -q clean verify`), make sure it is
green, then hand the user the fresh `boost-autotune-plugin/target/BoostAutotune.jar` (send the
file, not just a path). Verify before sending: manifest has `ApplicationPlugin`, no
`com/efiAnalytics` classes bundled, bytecode major version 52 (Java 8).

## Build
- `mvn -q clean verify` — both modules, all tests, shaded jar.
- `mvn -q -o -pl boost-autotune-plugin -am -Dtest=... test` — a subset (the core module must stay
  in the reactor).
- Demo UI without a car: `java -cp boost-autotune-plugin/target/BoostAutotune.jar io.github.xadmiral.boostautotune.plugin.DemoLauncher`
- The TunerStudio plugin API jar is vendored in `repo/` (provided scope); never shade it.
- Source level is Java 8: no lambdas or streams in main code.

## Layout
- `boost-autotune-core` — pure logic + simulator (boost, sweep, vvt, als) with unit / e2e tests.
- `boost-autotune-plugin` — TunerStudio glue (`ecu/`), mode drivers (`mode/`), Swing UI (`ui/`).
- Primary ECU: Stealth PCM (MS3 `DM00.22f`); its parameter names live in `EcuPresets`.

## Conventions
- Work on the branch the session names; commit as `xADMIRAL <codingadmiral@gmail.com>`.
- Screenshots for the README are captured under Xvfb from the demo ECU (`docs/screenshots/`).
- README is Russian first (`README.md`) with an English summary (`README.en.md`); document every
  new mode in both.
