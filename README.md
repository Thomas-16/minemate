# MineMate

MineMate is a Fabric companion mod backed by PlayerEngine. Stage 3 pins and audits
PlayerEngine `1.21.1-1.4.0`, exposes observable execution/reflex state, and adds
deterministic crafting, standard-furnace smelting, and exact-container transfers.

## Companion commands

Counts are desired destination totals and default to one. Item arguments below are exact
Minecraft registry items such as `minecraft:oak_planks`.

```text
/companion craft <item> [count]
/companion smelt <input> [count]
/companion deposit <x y z> <item> [count]
/companion withdraw <x y z> <item> [count]
```

`/companion status` reports the execution scope, elapsed/timeout tick budget, live
PlayerEngine phase/task tree, preempting chain, and current reflex. Existing `mine` and
`fetch` aliases remain available; `mine` is composite item acquisition and its future
planner-facing name is `acquire_item`.

See [playerengine-capability-audit.md](playerengine-capability-audit.md) for the audited
capability matrix and certification boundaries.

## Verification

```text
./gradlew build
./gradlew runServer -Dminemate.autotest=true
```

Use `-Dminemate.autotest.scenario=<name>` to run one headless scenario. CI performs both
the build and complete server suite.

## License

This project is available under the CC0 license.
