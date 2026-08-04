# PlayerEngine capability audit

Audited dependency: **PlayerEngine 1.21.1-1.4.0**. MineMate pins this exact mod version because
the classifications below describe observed APIs and behavior, not a compatibility promise for
future PlayerEngine releases.

## Classification rules

- **REFLEX** — automatic fast-loop behavior; never exposed as a planner tool.
- **CERTIFIED_TOOL** — has MineMate-owned completion checks and representative headless scenarios.
- **INTERNAL_PRIMITIVE** — useful inside a certified/composite task but too low-level or weakly
  verified for a planner.
- **EXPERIMENTAL** — hidden until it has bounded outcomes and scenario coverage on
  `CompanionEntity`.

Static compatibility is not certification. A family can contain both internal and experimental
entry points; only the explicitly named tools in the Evidence column are planner candidates.

## Capability matrix

| Family | Classification | Prerequisites and possible hidden composition | Side effects / risks | Completion evidence | Current scenario coverage |
| --- | --- | --- | --- | --- | --- |
| Eating and hunger | REFLEX | Food must be held or reachable through PlayerEngine's food chain. | May preempt user work to eat. | Active chain, start tick, hunger/eating detail, and transition telemetry. | Eating preemption and post-eating clear. |
| Mob/player defense | REFLEX | Weapons, shields, and safe terrain improve behavior. | May attack, retreat, shield, dodge, place protection, or extinguish fire. MineMate leashes mob defense to the owner. | `MobDefenseChain` state, detailed response, owner distance, and suppression flag. | Real defense preemption/clear plus owner-leash suppression. |
| Fall, lava, water, fire, and unstuck survival | REFLEX | Context-specific items such as a water bucket may be required. | Can interrupt movement, place water, break blocks, or change route. | Active chain and relevant MLG/world-survival/unstuck detail. | Telemetry integration only; hazard fixtures remain pending. |
| Follow | EXPERIMENTAL | Online target player in the same server. | Continuous pathfinding and route modification until cancelled. | Independent target availability/distance probe; `CONTINUOUS` profile. | Dedicated-server player fixture pending. |
| Coordinate travel | CERTIFIED_TOOL | Loaded/reachable terrain. | Automatone may break/place route-support blocks. | Independent arrival-radius probe; `DIRECT` profile. | Success, cancellation, and defense-leash scenarios; timeout behavior is covered by the composite timeout regression. |
| Combat | EXPERIMENTAL | Live target entity. | Pursues and attacks the selected target. | Independent target liveness/health probe; `DIRECT` profile. | Representative deterministic kill fixture pending. |
| Catalogued resource acquisition (`mine`) | CERTIFIED_TOOL | Depends on requested resource and world. | **Composite:** may gather wood, craft tools, mine, smelt, fight, trade, and travel. The future planner-facing name is `acquire_item`; `/companion mine` remains a compatibility alias. | Independent inventory target, bounded timeout/stall budgets, `COMPOSITE` profile, and live task tree. | Dirt acquisition success, unknown resource, timeout/cancellation infrastructure, and composite telemetry. |
| Fetch and delivery | EXPERIMENTAL | Same acquisition prerequisites plus an online owner. | Composite acquisition followed by inventory transfer to the owner. | Independent owner inventory delta, resource failure probe, and `COMPOSITE` profile. | Unknown-resource rejection; dedicated-server owner fixture pending. |
| Crafting macros | CERTIFIED_TOOL | Exact craftable output item. PlayerEngine may acquire ingredients and craft/use a table. | Composite and potentially long-running; recipe choices may consume variant materials. | Exact output inventory total, structured no-recipe/material outcome, phase, and task tree. | Partial-total success, no recipe, composite telemetry, and a bounded acquisition timeout. |
| Furnace smelting | CERTIFIED_TOOL | Exact input with a standard `SMELTING` recipe, reachable furnace, input, and fuel. | Direct furnace operation can leave partial output when fuel/setup changes. No extra MineMate acquisition chain is added. | Resolved output inventory total and typed `SmeltDeferredTask` outcome. | Partial-total success, wall reachability, no recipe, no fuel/setup, and direct telemetry. |
| Exact container deposit/withdraw | CERTIFIED_TOOL | Loaded supported container at one exact position and exact item ID; `ContainerResolver` joins supported double chests. | Moves only the amount missing from the destination total and may leave a partial transfer. | Independent complete-container/companion totals and precise `StorageAccessCode`. | Partial-total deposit, reachable withdraw, partial stock, full, missing, unsupported, and unloaded exact coordinate. |
| Inventory slots, equipment, and pickup processing | INTERNAL_PRIMITIVE | Matching items and free/usable slots. | Changes held item, armor, cursor, or inventory layout. | Used by PlayerEngine reflexes/tasks; not independently exposed. | Covered only through higher-level tasks. |
| General movement/search primitives | INTERNAL_PRIMITIVE | Loaded chunks and reachable goals. | Can explore, break/place blocks, enter boats or portals, or wander. | Composed beneath travel/resource tools; low-level entry points remain hidden. | Indirect travel/acquisition coverage. |
| Container scanning, looting, smithing, and deferred jobs | EXPERIMENTAL | Containers/workstations, recipes, and persistent deferred-job state. | Broad inventory/world mutations and resumable work. | PlayerEngine outcomes inspected statically; only exact Stage 3 transfers are certified. | No general scanning/looting/smithing certification. |
| Farming setup, planting, repair, and harvest | EXPERIMENTAL | Farm geometry, tools, water, planting items, and EllieGPS waypoints. | Large terrain/inventory changes and persistent landmarks. | Rich PlayerEngine outcomes exist, but no MineMate-owned verifier contract. | None. |
| Construction, portals, and structures | EXPERIMENTAL | Materials, placement permissions, safe terrain, and sometimes hosted PlayerEngine services. | Potentially large/destructive world edits; generated structures may invoke PlayerEngine's own LLM paths. | Hidden until bounded postconditions and fixtures exist. | None. |
| Exploration, temples, strongholds, and dimensions | EXPERIMENTAL | Chunk loading, dimension access, supplies, and long travel budgets. | Can leave owner/render distance, loot structures, use portals, and modify the world. | No MineMate-owned end-to-end verifier; chunk-ticket policy is unresolved. | None. |
| Full-game and Ender Dragon sequences | EXPERIMENTAL | Extensive supplies, dimensions, boss state, and long-lived execution. | Very long, high-impact task chains with broad world mutation. | PlayerEngine self-tests are not MineMate certification. | None; never planner-facing in Stage 3. |

## Planner contract consequence

Stage 4 must build its typed registry from the certified rows, not from PlayerEngine's class list.
In particular, it must describe `acquire_item` as composite and show its timeout, live subtask, and
possible prerequisite work before a planner chooses it. Direct production/storage tools should be
preferred when their prerequisites are already present.
