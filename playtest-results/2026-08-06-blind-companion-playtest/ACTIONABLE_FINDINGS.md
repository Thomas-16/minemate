# 2026-08-06 Blind Playtest: Root Causes and Actionable Fixes

## Context

This report analyzes the 2026-08-06 blind (black-box, UI-only) playtest against the
session logs preserved beside it. The playtester knew only `/companion spawn` and
`/companion plan`, and never read the source or `CLAUDE.md`. Evidence:

- [`PLAYTEST_REPORT.md`](./PLAYTEST_REPORT.md) - the player-perspective account
- [`session-latest.log`](./session-latest.log) - chat and lifecycle log
- [`session-debug.log`](./session-debug.log) - full planner wire traffic
  (`Planner request` / `Planner reply 200` lines) and PlayerEngine diagnostics

Session shape: three companions (Milo, Nova, Iris), 24 hosted-planner requests,
24 HTTP 200 responses, zero transport failures, prompt cache hit on every request
after the first. Every failure in this session happened above or below the wire
layer, never in it.

The headline: **the protocol and executor held; the losses came from four validator
rules that fire on replies a reasonable model sends, two deterministic-task deadlocks,
and command gating that can strand or orphan a working companion.** Almost everything
below is a bounded, testable change.

## Symptom-to-root-cause map

What the player saw versus what the logs show actually happened:

| Player-observed symptom | Actual root cause (from debug log) |
| --- | --- |
| "Say hello" -> "could not work out a plan" | Model returned a correct `ANSWERED` status plus a separate greeting text block; the terminal-shape rule rejects any assistant message next to the status call |
| Mining expedition "could not put together a complete plan" | Two-stage validator trap: 7-call bootstrap reply failed coverage, then the repair reply was rejected for not re-declaring a contract the system had silently discarded |
| Fallback order kept the diamond pickaxe it was told to skip | The model *did* skip it; coverage repair rejected that reply against the recorded contract and forced the pickaxe back in |
| Blank `Unknown resource:` message | Bind-time unknown-resource rejection drops the offending id from the player message (`wool`/`red_dye` in the bed plan; `fire_resistance_potion`/`strong_healing_potion` in brewing) |
| Spruce logs stuck at `0/5`, phases cycling | Fetch entered its delivery phase holding zero logs while PlayerEngine's DEFENDER follow dragged the companion back to the owner; delivery never re-enters acquisition, so it wedged |
| Diamond pickaxe lying on the ground, `delivering` -> `collecting`, nothing moving | Item was thrown at the owner, owner never picked it up; after the post-throw `GoalRunAwayFrom` backoff the task waits forever with no re-throw, nudge, or timeout |
| "No companion nearby" + "You already have a companion" = total control loss | `plan` is proximity-gated; `spawn`'s already-have-one check is also effectively proximity-based - Nova spawned while Milo was alive 200 blocks away, orphaning Milo mid-order (plan-12 never terminated) |
| Guard-until-sunrise became plain following that never stopped | No guard capability and no daybreak trigger; model silently downgraded to `follow`, which by design never finishes |

## Findings and fixes

### P0-1: Terminal replies must tolerate assistant speech

The session's very first order - "Milo, say hello and tell me what you notice around
us" - produced a perfect reply: `ANSWERED` with a snapshot-accurate message
("clear taiga, daytime, no threats around"), plus one stray text block
("Howdy Player67, good to see you nearby!"). The rule "a terminal reply is exactly
one status call and no separate assistant message" rejected it, and the player's
first impression was "I could not work out a plan for that."

This is the same class of lesson as the live-retest one already in `CLAUDE.md`
("any protocol that adds a required call must expect this model to send that call
alone"): a hard shape requirement the model violates in a semantically harmless way
should be absorbed, not fatal.

**Fix:** accept a terminal reply that carries assistant text. Either concatenate the
text into the spoken message or discard it in favor of the status `message` field.
If a hard reject is kept for some shapes, make this one repairable rather than
`PLANNER_FAILED`.

**Fixture:** offline protocol probe: `report_order_status(ANSWERED)` plus a text
block parses to a valid terminal reply.

### P0-2: A rejected action reply must not discard its contract silently

The five-resource mining expedition died in two rejections, and the second was
unwinnable for the model:

1. First reply: contract (all 5 deliverables, correct ids) + a sensible 7-call tool
   bootstrap (`mine oak_log`, craft table, wooden then stone tools). Rejected:
   "the plan does not fetch 3 diamond" - the checkpoint exemption requires all
   8 world calls used, and it had 7.
2. Repair reply: 8 world calls, ending at `mine iron_ore` - and no contract, because
   the system prompt says "do not repeat the contract on a replan" and the repair text
   says to declare it "only if it is not recorded yet", which the model cannot know.
   The contract from the rejected reply had been discarded. Rejected: "the action
   reply did not declare the order contract". Order closed.

Contract-only replies record their contract before repair ("which is now recorded"
appears in the fallback order's repair prompt); contracts arriving with rejected world
calls are dropped. The model is held responsible for state it cannot observe.

**Fix (pick at least the first):**
1. Record a structurally valid `declare_order_contract` even when the reply's plan is
   rejected, exactly as the contract-only path already does. Then every repair prompt
   can truthfully say "the contract is recorded".
2. When the contract is *not* recorded, say so explicitly in the repair message
   ("no contract is recorded for this order; declare it in your corrected reply").
3. Make the repair message name the escape hatch: "one fetch_items call listing all
   deliverables satisfies coverage even when the full work will not fit in this plan."

**Fixture:** `llm_contract_discarded_repair`: first reply = contract + under-covering
calls, repair reply without contract - must be accepted (fix 1) or the repair prompt
must contain the explicit not-recorded notice (fix 2).

### P0-3: Repair must be allowed to shrink the contract - it currently overrides the player

The adaptive-fallback order ("The diamond pickaxe is taking too long. Skip anything
unavailable...") is the worst finding in the session, because the system forced the
model to disobey the player:

1. Reply 1: contract only (5 items, still including `diamond_pickaxe`). Recorded;
   repair requested for the missing world calls.
2. Reply 2: speech "Skipping the diamond pickaxe for now, I will push hard on the rest
   of the Nether kit" + `fetch_items` of the other four items - **exactly what the
   player asked for**. Rejected: "the plan does not fetch 1 diamond_pickaxe for the
   owner."
3. Reply 3, capitulating: "Pushing on the full Nether kit, diamond pickaxe included,
   as fast as I can" + all five items. Accepted.

The player then watched the companion grind on the very item they said to skip, and
read it as the AI ignoring them. It was the validator.

**Fix:** let a repair or replan reply amend the recorded contract downward. Concretely:
accept `declare_order_contract` in a repair reply as a re-declaration (the "do not
repeat" rule already carves out exactly the wrong case), or add an explicit mechanism
(e.g. contract entries the reply marks dropped, with a required spoken reason so the
owner hears "skipping X because you told me to"). Coverage then validates against the
amended contract.

**Fixture:** `llm_contract_amend`: recorded 5-item contract, repair reply re-declares
4 items and covers those 4 - accepted, ledger re-seeded, dropped item reported in
speech.

### P0-4: Unknown-resource rejections lose the name and waste a planner round trip

Two orders hit `Unknown resource: ` with nothing after the colon (brewing:
`fire_resistance_potion` / `strong_healing_potion`; red bed first plan: `wool` and/or
`red_dye` in a hand-rolled shear-and-dye chain). The player saw a blank error; the
offending id exists in the `ToolCall` at rejection time but never reaches the message.

Both orders did recover through the `PLAN_FINISHED` replan (brewing to an honest
`UNSUPPORTED`, red bed to a clean `fetch 1 red_bed`), so the lifecycle is fine - but
each recovery costs a full planner request, and the blank message is pure confusion.

**Fixes:**
1. Include the offending id(s) in both the log line and the player-facing message.
   Assert non-blank detail in the autotest.
2. Route bind-time `unknown_resource` through the existing bounded-repair path with
   the id named ("`wool` is not an item id; sheared wool is `white_wool`"), instead of
   burning a replan. The repair prompt already exists; this is one more violation type.
3. Cheap prompt patch meanwhile: the potion case is a category gap (potion items need
   NBT and brewing is unsupported), so say so in the system prompt or tool
   descriptions so the model goes straight to `UNSUPPORTED`.

### P0-5: Fetch can latch into delivery with zero items and never recover

"Please collect five spruce logs and come back to me" - in a taiga, standing among
spruce trees - sat at `0/5` for 122 seconds until the player gave up. Debug timeline:

1. The previous order had left the companion 83 m from the owner. The fetch started,
   phase `mining`, and pathed to a nearby target.
2. PlayerEngine's DEFENDER-mode `FollowPlayerTask` fired its break-off
   (`distToTarget=81.5 maxChase=16.0`) and steered the companion back toward the
   owner. Chat: "I stopped chasing it to stay near you."
3. The fetch flipped to `delivering` while holding zero spruce logs, then to
   `collecting`, where it sat stationary until superseded. It never mined again -
   consistent with the (correct for its original purpose) rule that delivery does not
   re-enter acquisition.

Three stacked defects:

1. **Delivery entered with nothing to deliver.** Gate the delivery phase on actually
   holding at least one requested unit (or the remaining count); with zero held it
   must stay in/return to acquisition.
2. **The DEFENDER follow fought the user task.** MineMate suppresses mob defense only
   beyond 24 m owner distance and restores under 20 m; the restore mid-fetch let the
   defender leash steer the entity against the acquisition goal. While a user task is
   active, the defender follow must not steer (or the leash must widen to the task's
   working radius). The same break-off event fired during the breakfast and Nether-kit
   orders.
3. **No watchdog fired.** 122 s at 0/5 with phase flapping produced no stall, no
   `BLOCKED`, no player-visible explanation. The per-entry pipeline stall budget
   should have escalated long before the player gave up.

**Fixture:** headless scenario: start a fetch with the companion >80 m from the owner
inside spawn's ticking region, assert it either acquires or ends `BLOCKED` with a
reason - and specifically never reports a delivery phase while holding zero units.

### P0-6: Post-throw delivery needs a retry-and-escalate loop

The reduced Nether-kit order crafted its diamond pickaxe, walked ~130 m back, and
threw it at the owner. The owner never picked it up (it lay visibly at their feet -
plausibly creative-flight hover out of pickup range). After the deliberate
`GoalRunAwayFrom` backoff step, the task did nothing for the remaining ~90 s while
chat showed `collecting`, and progress stayed `0/5` because the ledger (correctly)
saw no owner-inventory delta.

The evidence rule worked; the recovery path doesn't exist.

**Fixes:**
1. If the owner-inventory delta hasn't landed after a bounded wait, re-collect the
   thrown stack and re-throw from closer (bounded retries).
2. Then tell the owner: "your diamond_pickaxe is on the ground next to you" - the
   companion knows exactly where it threw it.
3. Then end the entry honestly (`BLOCKED` delivery-not-accepted) instead of waiting
   forever.
4. Label the phase truthfully while waiting (`waiting for pickup`, not `collecting`).

**Fixture:** fake-owner scenario where `playerTouch` is deliberately *not* simulated:
assert re-throw happens, then the nudge line, then the bounded `BLOCKED`.

### P0-7: The owner must never lose command of a live companion

The playtest's most disruptive failure needed no debug log, but the log added a worse
detail than the report knew:

- `/companion plan` is proximity-gated ("No companion nearby"), so any task that walks
  the companion out of range makes it unredirectable and uncancellable - during the
  red-bed order the player could neither retask nor stop Iris, and the session ended
  that way.
- `/companion spawn 'Nova'` **succeeded at 12:10:35 while Milo was alive** ~200 blocks
  away mid-breakfast-order; Milo's plan-12 has no terminal line anywhere in the log.
  An orphaned companion kept a live standing order. Later, `spawn 'Juniper'` was
  correctly refused while Iris was nearby - the already-have-one check is effectively
  proximity-based and therefore inconsistent.

**Fixes:**
1. Drop the proximity gate for `plan`, `stop`, and any cancel path: ownership, not
   distance, is the authority. If some range limit must exist for immersion, exempt
   at minimum `stop`.
2. Add `/companion recall` (maps to the existing `come_to_owner`) and a
   `/companion status` that reports position, distance, active order, and phase -
   the remote-control primitives the player kept reaching for.
3. Make spawn's exclusivity check identity-based (owner has a living companion
   anywhere in any loaded dimension), and when one exists, say where it is and offer
   recall/dismiss instead of silently orphaning it.
4. Decide orphan semantics explicitly: if a second spawn is ever allowed to replace a
   lost companion, the old one's plan and standing order must be cancelled, not
   leaked.

**Fixture:** command-level scenario: walk companion 100+ blocks out (inside the
ticking region), assert `plan`/`stop` still work; assert second spawn is refused with
a location message while the first lives.

### P1-1: Conditional and duration orders need either support or honest refusal

"Guard me until sunrise... stop when daylight arrives" produced: empty contract +
`follow(Player67, distance=5)`. Following plus the mob-defense reflex is a defensible
approximation of "guard" - and mob defense did visibly engage the zombie - but the
stop condition evaporated: `follow` never finishes, there is a `NIGHTFALL` replan
trigger but no `DAYBREAK`, and after the player forced time to day the companion just
kept following. The player had no way to know the condition was dropped.

**Fixes, in increasing depth:**
1. Prompt rule now: when an order contains a condition or duration the toolset cannot
   express, the reply's speech must say what will and will not happen ("I'll stay
   close and fight anything nearby, but you'll need to tell me when to stop") - or
   return `CLARIFICATION`/`UNSUPPORTED`. Silent downgrade is the failure mode.
2. Add a `DAYBREAK` trigger symmetric with `NIGHTFALL`, so a standing order like this
   replans at dawn and the planner can then close it (`COMPLETED` with "sun's up").
3. Longer term: a finite `guard(radius, until_daylight|duration)` task - the first
   real member of the conditional-task family, and the shape "Task depth" in
   `CLAUDE.md` already points toward.

### P1-2: Progress messaging - dedupe, sub-progress, and travel visibility

Three compounding legibility problems, all visible in one screen of chat:

- **Flood:** nine consecutive identical `0/8 items - iron_pickaxe: smelting` lines,
  10-12 s apart. Emit on change; while unchanged, heartbeat at a much longer interval
  with elapsed time ("still smelting, 90s").
- **No sub-progress:** `iron_pickaxe: smelting` for 105 s was *real* work (ingots
  accumulating) but rendered identically to a stall. The pipeline knows its
  ingredient counts; show them ("smelting iron 2/3"). This is the same fix as the
  existing P1 item about probes counting ingredient progress - the probe data should
  feed the progress line, not only the supervisor.
- **Travel reads as stall:** `mushroom_stew: mining` while pathing 210 blocks looked
  frozen. Baritone's path length and remaining distance exist; append them when the
  current leg is movement ("traveling, 140 blocks to go"). This also covers the
  playtester's ETA request without inventing time estimates.

### P1-3: Combat survivability during item orders

Nova died to three summoned skeletons while carrying the ranged-kit order. The debug
log shows the mob-defense chain flapping - `User Tasks -> Mob Defense -> User Tasks`
five times in 20 seconds, with `FOLLOW-TASK-STOP` each cycle - so the companion
neither committed to fighting nor fled; it stood in skeleton fire re-deciding. Three
skeletons at once is a harsh test, but the failure shape is actionable:

1. Add hysteresis/commitment to the defense chain so an engaged fight runs to a
   conclusion instead of re-arbitrating every few ticks.
2. Add a low-health reflex (retreat toward owner / disengage) - there is currently
   nothing between "fight" and "die", and the companion drowns the order with it.
3. On death, report where ("Nova died at x,z; your items are there") - the death
   message named the order but not the drop location.

### P2: Capability gaps the model already handles honestly

These were correctly refused in-session (good protocol behavior) and are roadmap
items, listed for completeness with one observation each:

- **Building:** refusal was honest, but the player rightly noticed companions *do*
  place crafting tables and furnaces internally. A minimal `build`/`place_blocks`
  objective would close the perceived inconsistency; until then the refusal line
  could acknowledge it ("I can place my own workstations but not build structures").
- **Scouting:** "no way to scout for villages or ruined portals" is accurate; a
  bounded `explore`/`locate_structure` task is the missing capability.
- **Brewing:** needs potion-item support (NBT ids) plus a brewing pipeline; until
  then P0-4's prompt patch keeps it a clean `UNSUPPORTED`.
- **Biome-aware resource choice:** "walk to the nearest tree" became
  `mine(oak_log, 1)` *in a taiga*, dragging the companion 83 m to find an oak when
  spruce stood everywhere - and that displacement set up the P0-5 wedge. One system-
  prompt line ("prefer resources native to the current biome; WORLD names it") is
  nearly free; a `NEARBY`/resource-hint snapshot section is the fuller fix already
  contemplated under "World context".
- **Contract/tool bound mismatch:** `declare_order_contract` caps `count` at 64 while
  `fetch`/`fetch_items` accept 2304; an order for 128 cobblestone cannot declare an
  honest contract. Align the bounds.

## What worked and must not regress

- 24/24 hosted requests succeeded; prompt caching hit on every request after the
  first; latency 4-11 s.
- The contract-only single-call habit was absorbed by repair twice, invisibly to the
  player (crafting-table order: repaired, planned, delivered in 7 s total).
- Contract coverage produced the intended plan shape everywhere it accepted a reply:
  every multi-item order became exactly one `fetch_items`; the red-bed replan
  collapsed a hand-rolled 6-step recipe chain into one `fetch`.
- The ledger and delivery receipts worked: `delivered 1 iron_pickaxe/sword/axe to
  owner` events landed as the items arrived, `LEDGER:` lines reached the model on
  replans, and no unevidenced `COMPLETED` was accepted all session.
- Supersede/cancel was immediate and clean in all six replacements; the death path
  closed the order with a clear message naming the lost work.
- Iron equipment (3/8 delivered before interruption) and the breakfast (3/4) show the
  acquire/craft/smelt/deliver pipeline is genuinely strong when nothing above wedges
  it - the playtester called it "genuinely impressive".

## Priority order

**P0 - protocol fairness and control (small, bounded, high player impact):**
1. Accept assistant speech on terminal replies (P0-1).
2. Record contracts from rejected replies; truthful repair prompts (P0-2).
3. Contract amendment on repair - never force the plan against the player (P0-3).
4. Name the resource in unknown-resource failures; make them repairable (P0-4).
5. Owner command authority at any distance; identity-based spawn exclusivity; no
   orphaned standing orders (P0-7).

**P0/P1 - the two deadlocks (task-layer, need scenarios first):**
6. Delivery phase requires held items; defender-follow must not fight user tasks;
   stall watchdog actually fires (P0-5).
7. Post-throw retry, owner nudge, bounded BLOCKED (P0-6).

**P1 - legibility and survivability:**
8. Progress dedupe + sub-progress + travel distance (P1-2).
9. Conditional orders: honest speech now, `DAYBREAK` trigger next, `guard` later (P1-1).
10. Defense-chain hysteresis and low-health retreat (P1-3).

**P2 - capability roadmap:** build, scout, brewing, biome-aware hints, bound
alignment.

## Acceptance criteria for the next blind playtest

- A greeting or observation question gets an in-character answer, never "I could not
  work out a plan."
- No order ever closes because the model could not guess hidden validator state; every
  repair prompt states the recorded contract (or its absence) explicitly.
- "Skip X and do the rest" produces a plan without X, and the companion says why.
- Every failure message that names a resource actually names it.
- A fetch never reports a delivery phase while holding zero requested items, and no
  order sits >60 s with zero progress and no explanation or state change.
- A thrown-but-unclaimed delivery ends in a re-throw, then a spoken nudge, then an
  honest BLOCKED - never a silent standstill.
- The player can always stop, redirect, or recall their companion, from any distance,
  and can never accidentally orphan one that is alive.
- A conditional order either executes its condition or the companion says up front
  that it cannot.
