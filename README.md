# MineMate

MineMate is a Fabric companion mod backed by PlayerEngine. Stage 3 pins and audits
PlayerEngine `1.21.1-1.4.0`, exposes observable execution/reflex state, and adds
deterministic crafting, standard-furnace smelting, and exact-container transfers.

## Companion commands

Counts are desired destination totals and default to one. Item arguments below are exact
Minecraft registry items such as `minecraft:oak_planks`.

```text
/companion spawn <name>
/companion craft <item> [count]
/companion smelt <input> [count]
/companion deposit <x y z> <item> [count]
/companion withdraw <x y z> <item> [count]
```

Spawning requires a name between 1 and 32 characters. Quote names containing spaces,
for example `/companion spawn "Ada Stone"`. The name is displayed above the companion.

`/companion status` reports the execution scope, elapsed/timeout tick budget, live
PlayerEngine phase/task tree, preempting chain, and current reflex. Existing `mine` and
`fetch` aliases remain available; `mine` is composite item acquisition and its future
planner-facing name is `acquire_item`.

`/companion debugUI` opens a read-only live inspector for your nearby companion. Its
Overview, Inventory, and Execution tabs show exact server-owned slots and equipment,
vitals and movement, current goal/task/engine/reflex telemetry, and the last outcome.
The screen refreshes twice per second and requires MineMate on both the server and client.

See [playerengine-capability-audit.md](playerengine-capability-audit.md) for the audited
capability matrix and certification boundaries.

## Verification

```text
./gradlew build
./gradlew -Dminemate.autotest=true runServer --console=plain
./gradlew -Dminemate.autotest=true -Dminemate.autotest.profile=full runServer --console=plain
```

The first server command runs the local smoke profile. The second runs all scenarios,
including the slower iron-sword world-acquisition case; CI explicitly uses this full
profile. Use `-Dminemate.autotest.scenario=<name>` to run one or more comma-separated
headless scenarios regardless of profile.

## License

This project is available under the CC0 license.
