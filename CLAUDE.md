# MineMate

## Current structure

- `entity` — the `LivingEntity` body and PlayerEngine controller. Never call `controller.setOwner`; MineMate owns planning/auth.
- `task` — goals, probes, supervision, and structured outcomes; this is the stable layer the stage-4 planner will drive.
- `task.sink` — chat, logging, TTS, planner, and test consumers of task lifecycle events.
- `command` — temporary hardcoded task input; the planner replaces it in stage 4.
- `integration` — isolated PlayerEngine configuration and compatibility quirks.
- `dev` — sequential headless integration test. On PowerShell, use `.\gradlew.bat "-Dminemate.autotest=true" runServer --console=plain` for the default smoke profile, or add `"-Dminemate.autotest.profile=full"` for all scenarios. Put quoted system properties before the task name. The task provisions `run/` and regenerates the world each time, preventing save-state contamination between clean sequential runs. It halts the server cleanly whether or not scenarios pass, so CI asserts on the `AUTOTEST PASS` log line rather than the Gradle exit code.
  Pass `"-Dminemate.autotest.scenario=<name>"` as well to run one or more comma-separated scenarios while debugging; explicit scenario selection overrides the profile.

Build with `gradlew build`; launch a client with `gradlew runClient`. The project is pinned to Minecraft 1.21.1, Java 21, and Mojang mappings.

## Development lessons

- A timed-out terminal/tool invocation does not necessarily stop the Gradle wrapper or the Minecraft JVM it launched. Before rerunning a timed-out headless test, inspect Java command lines and stop only the exact stale wrapper and `KnotServer` processes. Never kill every Java process; Gradle daemons, the IDE, and other tools may also be using Java. Stale-test symptoms include `Unable to delete run/logs/latest.log`, `Unable to delete run/logs/debug.log`, inconsistent scenario results, and port conflicts. A regenerated world cannot isolate two servers that are running concurrently.
- Prefer a focused scenario while changing one capability and the smoke profile for normal local verification. CI runs the complete `full` profile, including slow world-acquisition coverage. `runServer` can report `BUILD SUCCESSFUL` after an autotest failure because the test shuts the server down normally; the authoritative result is `[autotest] AUTOTEST PASS` or `AUTOTEST FAIL` in the log.
- PlayerEngine's craft macro used 300- and 1,200-tick collection cutoffs around a nested gather search that can run for 90 seconds. Compatibility patches must preserve the overall MineMate timeout, be guarded by the exact PlayerEngine dependency version, and use Mixin `require = 1` so upstream bytecode changes fail fast.
- `LivingEntityInventory.insertStack` mutates the supplied stack. Item pickup accounting must compare the stack count before and after insertion, report only that delta, discard a fully consumed entity without restoring its stack, and leave the mutated remainder for a partial pickup.
- `GiveItemToPlayerTask` considers delivery finished after throwing the items, not after the owner receives them. Fetch must treat acquisition and delivery as separate phases and remain active until the owner's inventory confirms the requested increase. Once delivery begins, do not re-enter acquisition merely because thrown items are no longer in the companion inventory.
- A loaded Fabric `FakePlayer` is not necessarily in the connected-player list. Owner resolution should first check connected players and then loaded `ServerPlayer` entities. In delivery tests, vanilla `FakePlayer.playerTouch` may remove an item entity while its old stack object remains readable; phantom-drop assertions should cover entities collected by `CompanionEntity`, while delivery success is verified from the fake owner's inventory.
- Reusing the same chest position across differently filtered storage scenarios can expose stale PlayerEngine container state. Preserve the proven full-suite order or give scenarios distinct fixture positions; the smoke profile intentionally runs only one mutating storage scenario.
- Keep player-visible status, progress, failure, truncation, and autotest-marker punctuation ASCII. The log files may be valid UTF-8 while a console or copy path still decodes em dashes and ellipses incorrectly; comments may retain Unicode.

## Where this is going

- **World context:** event-triggered, budgeted snapshots (~300 tokens) covering state, threats, current work, recent events, and player-relative position.
- **Memory:** episodic history, semantic facts/preferences, and spatial landmarks; evaluate PlayerEngine's `elliegps` waypoint system first.
- **Task depth:** encode real game knowledge—Y-levels, routes, safe bridges, spawn-proofing, and torch spacing—as deterministic tasks.
- **Learned skills:** persist and retrieve named goal compositions that worked; structured outcomes are their training signal.
- **Reflection:** turn failures into lessons and recall them before similar attempts.
- **Initiative and personality:** emerge from the 20 Hz loop and event triggers, not only a system prompt.
- **Player modeling:** infer what the player is doing and needs so the companion can help usefully without being prompted.
