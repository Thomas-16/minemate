# MineMate

## Current structure

- `entity` — the `LivingEntity` body and PlayerEngine controller. Never call `controller.setOwner`; MineMate owns planning/auth.
- `task` — goals, probes, supervision, and structured outcomes; this is the stable layer the stage-4 planner will drive.
- `task.sink` — chat, logging, TTS, planner, and test consumers of task lifecycle events.
- `command` — temporary hardcoded task input; the planner replaces it in stage 4.
- `integration` — isolated PlayerEngine configuration and compatibility quirks.
- `dev` — sequential headless integration test; run with `gradlew runServer -Dminemate.autotest=true`.

Build with `gradlew build`; launch a client with `gradlew runClient`. The project is pinned to Minecraft 1.21.1, Java 21, and Mojang mappings.

## Where this is going

- **World context:** event-triggered, budgeted snapshots (~300 tokens) covering state, threats, current work, recent events, and player-relative position.
- **Memory:** episodic history, semantic facts/preferences, and spatial landmarks; evaluate PlayerEngine's `elliegps` waypoint system first.
- **Task depth:** encode real game knowledge—Y-levels, routes, safe bridges, spawn-proofing, and torch spacing—as deterministic tasks.
- **Learned skills:** persist and retrieve named goal compositions that worked; structured outcomes are their training signal.
- **Reflection:** turn failures into lessons and recall them before similar attempts.
- **Initiative and personality:** emerge from the 20 Hz loop and event triggers, not only a system prompt.
- **Player modeling:** infer what the player is doing and needs so the companion can help usefully without being prompted.
