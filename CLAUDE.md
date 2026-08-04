# MineMate

## Current structure

- `entity` — the `LivingEntity` body and PlayerEngine controller. Never call `controller.setOwner`; MineMate owns planning/auth.
- `task` — goals, probes, supervision, and structured outcomes; the stable layer the planner drives.
- `task.sink` — chat, logging, journal, TTS, planner, and test consumers of task lifecycle events.
- `tool` — the typed, schema'd capability surface over `Goals`: descriptors, parameters, JSON `ToolCall`s, pure validation, and world binding. The single source of truth for what the companion can be asked to do; both `command` and `planner` go through it, so a tool cannot behave one way when typed and another way when planned.
- `context` — world snapshots, the recent-event journal, and replan triggers. Depends on `task`/`entity`, never on `planner`.
- `planner` — plans, the multi-step plan executor, the async `Planner` interface, and the scripted stand-in. Stage 5 adds a hosted LLM behind the same interface.
- `command` — temporary hardcoded task input; the planner replaces it.
- `integration` — isolated PlayerEngine configuration and compatibility quirks.
- `dev` — sequential headless integration test. On PowerShell, use `.\gradlew.bat "-Dminemate.autotest=true" runServer --console=plain` for the default smoke profile, or add `"-Dminemate.autotest.profile=full"` for all scenarios. Put quoted system properties before the task name. The task provisions `run/` and regenerates the world each time, preventing save-state contamination between clean sequential runs. It halts the server cleanly whether or not scenarios pass, so CI asserts on the `AUTOTEST PASS` log line rather than the Gradle exit code.
  Pass `"-Dminemate.autotest.scenario=<name>"` as well to run one or more comma-separated scenarios while debugging; explicit scenario selection overrides the profile.

Build with `gradlew build`; launch a client with `gradlew runClient`. The project is pinned to Minecraft 1.21.1, Java 21, and Mojang mappings.

## Development lessons

- Keep verification proportional to the change. Small, low-risk changes such as command
  arguments or simple entity metadata should use build and manual verification rather than
  expanding the headless integration suite.
- A timed-out terminal/tool invocation does not necessarily stop the Gradle wrapper or the Minecraft JVM it launched. Before rerunning a timed-out headless test, inspect Java command lines and stop only the exact stale wrapper and `KnotServer` processes. Never kill every Java process; Gradle daemons, the IDE, and other tools may also be using Java. Stale-test symptoms include `Unable to delete run/logs/latest.log`, `Unable to delete run/logs/debug.log`, inconsistent scenario results, and port conflicts. A regenerated world cannot isolate two servers that are running concurrently.
- Prefer a focused scenario while changing one capability and the smoke profile for normal local verification. CI runs the complete `full` profile, including slow world-acquisition coverage. `runServer` can report `BUILD SUCCESSFUL` after an autotest failure because the test shuts the server down normally; the authoritative result is `[autotest] AUTOTEST PASS` or `AUTOTEST FAIL` in the log.
- PlayerEngine's craft macro used 300- and 1,200-tick collection cutoffs around a nested gather search that can run for 90 seconds. Compatibility patches must preserve the overall MineMate timeout, be guarded by the exact PlayerEngine dependency version, and use Mixin `require = 1` so upstream bytecode changes fail fast.
- `LivingEntityInventory.insertStack` mutates the supplied stack. Item pickup accounting must compare the stack count before and after insertion, report only that delta, discard a fully consumed entity without restoring its stack, and leave the mutated remainder for a partial pickup.
- `GiveItemToPlayerTask` considers delivery finished after throwing the items, not after the owner receives them. Fetch must treat acquisition and delivery as separate phases and remain active until the owner's inventory confirms the requested increase. Once delivery begins, do not re-enter acquisition merely because thrown items are no longer in the companion inventory.
- A loaded Fabric `FakePlayer` is not necessarily in the connected-player list. Owner resolution should first check connected players and then loaded `ServerPlayer` entities. In delivery tests, vanilla `FakePlayer.playerTouch` may remove an item entity while its old stack object remains readable; phantom-drop assertions should cover entities collected by `CompanionEntity`, while delivery success is verified from the fake owner's inventory.
- Replacing a chest or furnace fixture with air invokes vanilla container removal and drops its contents. Empty test containers before removing their blocks, or the companion can collect the drops in the next scenario and create misleading results such as `withdraw 6/4`; changing fixture coordinates or adding task-drain delays does not prevent those world item drops.
- Keep player-visible status, progress, failure, truncation, and autotest-marker punctuation ASCII. The log files may be valid UTF-8 while a console or copy path still decodes em dashes and ellipses incorrectly; comments may retain Unicode.
- `/companion stop` must cancel the plan before the supervisor. Cancelling only the supervisor lets `PlanExecutor` submit the next step on the following tick, so stop appears to do nothing.
- `PlanExecutor` polls `supervisor.lastOutcome()` for its own goal id rather than registering a `TaskListener`. `TaskListeners` has no unregister, so a per-companion listener leaks for every dismissed companion; and reacting inside `fireFinished` would start the next step from within the previous step's terminal callback, on a supervisor still mid-finish. Polling also makes a mid-plan chat order correct for free: the supervisor cancels the step as `superseded` and the ordinary outcome branch ends the plan.
- The plan executor uses `submit`, never `replace`, so a plan cannot silently steamroll an order the owner just gave; `BUSY` is the signal to abandon.
- Journal pickup and damage events must coalesce. A `mine 64 cobblestone` goal produces a pickup every few ticks, and without merging them the 64-entry ring holds nothing but pickups by the time a planner reads it.
- `ReflexSnapshot.startedAtTick` is measured against `CompanionReflexTelemetry`'s own per-entity-load counter, not game time. Use `CompanionEntity.reflexActiveTicks()` to turn it into a duration.
- `ReflexSnapshot.defenseSuppressed` is inverted relative to its name: true means mob defense is suppressed. Render it as a word (`mob_defense=leashed|active`), never a raw boolean, or the planner reads it backwards.
- World snapshots are captured on demand and memoized per game tick. Do not prime them from `CompanionEntity.tick()`; that spends raycasts and entity queries per companion per tick for a consumer that may never ask.
- Never use `Long.MIN_VALUE` as a "never happened" sentinel for a tick timestamp that gets subtracted. `observedTicks - Long.MIN_VALUE` overflows to a negative difference, so a cooldown guard reads as permanently unexpired; `ReplanTriggers` silently fired nothing at all. Use an explicit sentinel and branch on it before subtracting.
- Assert a floor, not only a ceiling, on anything whose job is to be rare. `context_journal` capped replan triggers at 4 per scenario, which a system firing zero triggers satisfies perfectly. The suite passed while the feature was dead.
- Numeric tool arguments must be checked for finiteness before range. Both `value < min` and `value > max` are false for NaN, so `"NaN"` — which `Double.parseDouble` accepts — validates against every bound and reaches pathfinding as a value no later check recognises.
- Planner results are asynchronous, so every completion carries a generation claimed on the server thread by `PlanExecutor.beginPlannerRequest()`. Without it a plan requested before `/companion stop` still arrives and starts seconds later, and two concurrent requests race. `stop` invalidates pending requests unconditionally, not inside the `isActive()` branch: a request can be in flight while the supervisor is busy with a manual order.

## Known issue

`mob_defense_reflex` fails whenever it runs after `owner_friendly_fire`, which is the order the
`full` profile uses. It passes in isolation. Reproducible on a clean checkout of `411804c`, so it
predates the stage-4 work; the diagnostic is `seen=false cleared=false ... suppressed=false
priority=0.0`, meaning the leash released correctly but `MobDefenseChain` still declined the body.
Because the harness halts on the first failure, this blocks every later scenario in `full` — use
`smoke`, or an explicit scenario list, until it is fixed. `craft_iron_sword_world` is separately
order-sensitive: it passes alone (~89s) but can exhaust its cap after a different preceding sequence.

## Where this is going

- **World context:** event-triggered, budgeted snapshots (~300 tokens) covering state, threats, current work, recent events, and player-relative position.
- **Memory:** episodic history, semantic facts/preferences, and spatial landmarks; evaluate PlayerEngine's `elliegps` waypoint system first.
- **Task depth:** encode real game knowledge—Y-levels, routes, safe bridges, spawn-proofing, and torch spacing—as deterministic tasks.
- **Learned skills:** persist and retrieve named goal compositions that worked; structured outcomes are their training signal.
- **Reflection:** turn failures into lessons and recall them before similar attempts.
- **Initiative and personality:** emerge from the 20 Hz loop and event triggers, not only a system prompt.
- **Player modeling:** infer what the player is doing and needs so the companion can help usefully without being prompted.
