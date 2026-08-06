# LLM Playtest Learnings and Next Steps

## Context

This report covers the manual `runClient` playtest performed on 2026-08-04 after
commit `a7d34ef` (`Harden bounded LLM planning lifecycle`). That change introduced
bounded plans of up to eight actions, explicit terminal dispositions, whole-reply
validation, planner-owned trigger deduplication, and hardened standing-order
termination.

The evidence for this report is preserved in the repository as:

- [`latest.log`](playtest-logs/2026-08-04-bounded-planning/latest.log), SHA-256
  `515C382E29600650E2A04EC8984E14B5D968C33B84D1820C14BE02FB520A124D`
- [`debug.log`](playtest-logs/2026-08-04-bounded-planning/debug.log), SHA-256
  `1FBA0EED9FC3A840DC3C5CA0647C7FC0D58E140331254E31DD662555731E528F`

These are exact copies of the transient `run/logs` files captured before another
session could overwrite them. The session ran for approximately nine minutes,
from 20:28 to 20:37.

## Executive Summary

The lifecycle hardening worked, but complete bounded planning did not.

Execution, cancellation, replanning, failure cleanup, and terminal reporting were
reliable. The hosted model nevertheless continued to plan as though it were
limited to one action per turn: all nine action replies contained exactly one
world-tool call, even when later work was already known. It also produced two
speech-only replies, and repeatedly promised delivery in its speech without
including delivery in its tool calls.

The main bottleneck is therefore no longer the plan executor. It is the semantic
boundary between player intent, the LLM, and the tool surface. More reasoning
alone is unlikely to solve it. The strongest next direction is to let the LLM
interpret intentions and ambiguity while deterministic systems handle recipes,
item bundles, target reacquisition, delivery, and completion evidence.

## Session Evidence

| Metric | Result |
| --- | ---: |
| Hosted-planner requests | 17 |
| Successful HTTP responses | 17 |
| Provider or transport failures | 0 |
| Planner latency | 1-5 seconds |
| Input tokens | 27,481 |
| Cached input tokens | 21,600 |
| Output tokens | 2,189 |
| Reasoning tokens | 1,552 |
| Valid action replies | 9 |
| Explicit terminal replies | 6 |
| Malformed speech-only replies | 2 |
| Multi-action replies | 0 |

Prompt caching worked well. After the first request, each subsequent request
reported 1,350 cached input tokens. No response mixed status and world calls,
duplicated status calls, or exceeded the eight-action limit.

## Order-by-Order Findings

### Make an iron sword

The planner first called `craft(iron_sword, 1)`. After the roughly 73-second
acquisition and crafting task finished, it replanned to
`fetch(iron_sword, 1)`. A third response reported `COMPLETED` after delivery.

The order succeeded, and the deterministic acquisition, crafting, and delivery
machinery performed well. The plan was nevertheless incomplete. The initial
speech said that the companion would forge the sword and bring it over, while the
actual response contained only the craft call. Delivery was known work and should
not have been deferred to a later replan.

For an owner-directed item request, `fetch` should normally be the high-level
operation because it already acquires or crafts the item and delivers it. At a
minimum, the first response should have included both craft and fetch.

### Thank-you message

The planner returned `COMPLETED` with "You're welcome." This worked, but it
revealed that `COMPLETED` is serving two different meanings: verified completion
of a world order and a terminal conversational answer.

### Go to -4, 127, 38

The model called `goto(x=-4, z=38, radius=1)` and claimed it was going to the full
three-dimensional coordinate. The companion reached the requested X/Z at about
Y=116. Only then did a replan return `UNSUPPORTED`, explaining that the tool could
not target a height.

The terminal diagnosis was honest, but the companion partially executed an order
it could not satisfy and made a misleading initial promise. Either navigation
must support Y, or the order must be rejected or clarified before horizontal
movement begins.

### Inventory and hotbar questions

For "what's in your inventory?", the snapshot contained the answer and the model
spoke the correct contents: three dark oak planks, stone and wooden pickaxes,
thirteen cobblestone, and three sticks. It omitted both a world call and a terminal
status. The protocol correctly rejected the malformed reply, executed nothing,
recorded `PLANNER_FAILED`, and closed the order. The player therefore received a
generic planning failure instead of the correct answer.

When the question was rephrased as "what's in your hotbar?", the model returned
the same information through an explicit `COMPLETED` status and succeeded.

This confirms that speech-only completion is no longer silently accepted, but it
also shows a gap in the protocol and prompt treatment of informational answers.

### Get some food

The planner arbitrarily chose `fetch(bread, 8)`. The companion started traveling
toward a source roughly 153 blocks away and had acquired no bread after about 24
seconds. The player then superseded the order.

Cancellation was immediate and clean. The cancelled plan did not revive or
interfere with the replacement order. The questionable part was strategy: the
model had insufficient evidence for choosing bread, a quantity of eight, and a
long trip over possible nearby alternatives.

### Hunt an animal and cook it

The model immediately returned `BLOCKED` because no nearby animal was visible.
This was correct for the exposed world state and capabilities. Passive animals
are not sufficiently represented in the snapshot, and `kill` requires a specific
visible entity UUID. There is no bounded search, hunt-by-type, or higher-level
cooked-food acquisition objective.

The model correctly avoided inventing a target or calling a nonexistent tool.
This failure is primarily a capability and world-context gap.

### Come to the owner

The owner kept moving. Across successive requests the planner called:

1. `goto(-3, -21)`
2. `goto(-1, -1)`
3. `goto(0, 12)`
4. `COMPLETED` once the companion was close

This exercised the initial request and all three allowed replans. The standing
order remained active while each plan ran, no duplicate task-outcome trigger
consumed the budget, no stale event closed the next plan, and the final terminal
response closed the order cleanly without `PLANNER_BUDGET_EXHAUSTED`.

The lifecycle behavior was excellent. The inefficiency is architectural: chasing
a moving owner required four API calls. A finite task that continuously binds to
the owner's current position should do this in one world action.

### Give a full set of stone tools

The companion started with a stone pickaxe, thirteen cobblestone, and three
sticks. The planner called `craft(stick, 7)`, then `craft(stick, 8)`. The second
recipe overshot to eleven sticks. Its third response only said that it would
finish the tool set and start handing it over, so the protocol rejected the
speech-only response and closed the order as `PLANNER_FAILED`.

No stone tool set was produced or delivered. The model:

- planned intermediate ingredients even though final-item crafting already
  handles dependencies;
- failed to expand "full stone tool set" into final deliverables;
- misunderstood the desired-total meaning of `count`;
- promised delivery without any `fetch` calls; and
- used all three requests without creating a single requested final item.

The three stone-tool responses consumed 237, 220, and 360 reasoning tokens. The
highest-reasoning reply was the malformed one. This is evidence that additional
reasoning effort does not compensate for ambiguous abstractions and missing
semantic validation.

## What Worked

- All seventeen provider requests succeeded, with predictable latency and strong
  prompt-cache reuse.
- Explicit `COMPLETED`, `UNSUPPORTED`, and `BLOCKED` dispositions worked.
- Malformed terminal shapes executed no partial world plan.
- Speech-only replies no longer silently completed an order.
- Planner failures closed standing orders instead of leaving inert orders behind.
- Planner-owned task outcomes did not generate duplicate replans.
- Each finished plan generated exactly one follow-up request.
- Superseding an active order cancelled it without stale-trigger interference.
- The final allowed replan remained active until it settled.
- The reserved status function remained absent from `/companion tools`.
- Given a sensible goal, deterministic crafting, fetching, navigation, and
  delivery were reliable.

## What Did Not Work

- The model produced no multi-action responses despite explicit prompt guidance.
- Known work was deferred to later replans in every multi-stage successful order.
- Assistant speech and tool calls disagreed about promised work.
- Informational answers still sometimes omitted the required terminal function.
- Item requests encouraged unnecessary recipe-level reasoning.
- Item quantity semantics were easy for the model to misinterpret.
- The snapshot did not support sound food-source or hunting decisions.
- Navigation could neither target Y nor efficiently follow a moving destination.
- There was no structured checklist proving completion of a multi-item order.
- Long composite tasks exposed too little phase or progress information.

## Strategic Direction

The target architecture should be:

```text
player request
  -> typed objective and acceptance criteria
  -> validated bounded action plan
  -> deterministic execution
  -> evidence-based completion check
  -> terminal response or bounded replan
```

The LLM should decide what the player means, choose among legitimate strategies,
and ask about genuine ambiguity. It should not need to micromanage stick recipes,
repeatedly sample a moving owner's coordinates, or infer completion solely from
free-form snapshots.

## Prioritized Next Steps

> **2026-08-05:** P0 is implemented, and P1-P3 below are superseded by the revised
> priorities in the addendum at the end of this document. The sections are kept as
> written for the record of what the 2026-08-04 session justified.

### P0: Close the remaining protocol and semantic gaps

1. Set Responses API `tool_choice` to `required`. Every valid hosted reply already
   requires at least one function call, whether it is a world action or
   `report_order_status`. This prevents zero-call speech replies at the provider
   boundary. It does not by itself ensure that a plan is complete.
2. Add an `ANSWERED` terminal disposition, or an equivalent explicit distinction
   for informational and social responses. Inventory questions and acknowledgments
   should not masquerade as completed world orders.
3. Normalize each player order into a typed contract before execution. A contract
   should identify required deliverables, recipient, destination, constraints, and
   completion evidence.
4. Validate the proposed plan against that contract. For example, a request to
   make something "for me" must include delivery, and speech that promises
   delivery must correspond to delivery actions.
5. Permit one bounded format or coverage repair attempt before closing an order
   whose reply is structurally valid but semantically incomplete.
6. Turn every request in this playtest into an offline semantic regression fixture.

### P1: Raise the tool surface to player-level objectives

1. Make `fetch` the canonical operation for "make/get/give/bring X to me." Its
   description and examples should explicitly say that it handles ingredients,
   crafting, already-held items, and delivery.
2. Rename or unambiguously document `count` as a desired total or requested
   delivery amount. Preserve compatibility internally if the schema name changes.
3. Add a structured multi-item delivery objective. A full stone-tool request
   should become a list of final items, then be expanded by deterministic code.
4. Add a finite `come_to_owner(distance)` task that continuously rebinds to the
   owner's position and completes inside the requested radius.
5. Support `goto(x, y, z, radius)` if the navigation layer can genuinely honor Y.
   Otherwise expose the limitation before accepting a three-dimensional order.

### P2: Improve world awareness and evidence

1. Include nearby passive mobs in snapshots with type, UUID, visibility, and
   distance.
2. Add a bounded `hunt(type, count, search_radius)` or higher-level cooked-food
   acquisition objective. Deterministic code should own search, target rebinding,
   drops, cooking, and delivery.
3. Add an order ledger containing normalized deliverables and their acquired,
   crafted, delivered, or blocked states.
4. Include relevant owner inventory changes or delivery receipts so that
   `COMPLETED` can be based on evidence rather than model inference.
5. Provide source distance, feasibility, and bounded search information for
   acquisition choices such as food.

### P3: Improve player experience and efficiency

1. Report the current phase of composite work: searching, mining, smelting,
   crafting, approaching, or delivering.
2. Expose useful progress and failure evidence without flooding the journal.
3. Handle trivial acknowledgments locally where appropriate, after the semantic
   protocol is stable.
4. Track actions per plan, planner calls per completed order, repair frequency,
   terminal accuracy, and completion cost as regular evaluation metrics.
5. Reconsider model or reasoning settings only after these structural changes are
   measured. This session provides no evidence that simply increasing reasoning
   effort would help.

## Acceptance Criteria for the Next Iteration

- No hosted reply can contain only assistant speech.
- "Make me an iron sword" is fulfilled without deferring known delivery work to a
  later planning turn.
- "What is in your inventory?" returns a valid terminal answer rather than a
  planning failure.
- "Give me a full set of stone tools" names and delivers final tools without
  separately crafting sticks in the hosted plan.
- "Come to me" remains one finite world task while the owner moves.
- A three-dimensional destination is either honored or rejected before movement.
- Hunting either uses visible/searchable passive-mob evidence or returns a precise
  bounded failure.
- Every `COMPLETED` item order is supported by a delivery receipt or equivalent
  structured evidence.
- The semantic regression suite asserts both protocol shape and whether the calls
  actually cover the player's requested outcome.

## Addendum (2026-08-05): P0 Implemented and Retested Live

### What P0 became

- `tool_choice` is `required`, so a zero-call speech reply is stopped at the provider
  boundary.
- `ANSWERED` is a terminal disposition alongside `COMPLETED`, `CLARIFICATION`,
  `UNSUPPORTED`, and `BLOCKED`, reserved for questions and social remarks answered from
  the snapshot.
- The contract is emitted by the LLM itself, not derived from the order text: a new
  hosted-protocol function `declare_order_contract` (deliverables as item/count pairs,
  plus `deliver_to_owner`) must accompany the first action reply of an order. It is
  parsed into an `OrderContract`, carried on the `Plan`, and stored on the
  `StandingOrder` only when its reply is accepted.
- Deterministic `ContractCoverage` validation: every deliverable bound for the owner
  must be covered by `fetch` calls in the same reply, because `fetch` is the one tool
  that acquires or crafts and then delivers. A plan already at the eight-step bound may
  end at a checkpoint; replans against a stored contract are not re-validated, since
  judging remainders needs the P2 evidence ledger.
- One bounded repair per order, charged to a `repairUsed` flag rather than the replan
  budget. A violating reply never executes; the repair request quotes the violation and
  the rejected reply's speech and calls. A second violation closes the order with an
  honest owner message.
- Offline regression fixtures: `llm_contract_repair` (coverage violation, repaired,
  accepted), `llm_contract_only_repair` (contract declared, no work planned, repaired),
  `llm_contract_exhausted` (missing contract, then still-uncovered repair, order
  closed), and `llm_answered`. The pure schema and protocol probes cover the ANSWERED
  round trip, contract parsing and normalization, the new malformed shapes, and the
  nested strict-schema form of the contract function.

### Live retest findings (runClient, 2026-08-05)

1. **The single-call habit collided with the contract protocol immediately.** The
   model's first reply to "can u make me a bucket" was speech plus exactly one function
   call - a correct `declare_order_contract` - and no world calls. The parser treated
   that as malformed, so the order died with the generic failure message, twice,
   identically. The fix, made the same session: a contract-only reply parses into an
   empty action plan, and dispatch turns it into the repairable violation "declared a
   contract but no world-tool calls" instead of a parse failure. The general lesson:
   any protocol that adds a required call must expect this model to send that call
   alone, and the repair path is the right place to absorb the habit.
2. **ANSWERED works in practice.** "hi!" and "thx!" both closed as terse in-character
   replies with no world action and no planning failure.
3. **The bucket order then succeeded end to end, but expensively.** The accepted plan
   was one `fetch(bucket, 1)` - the intended shape, since the design deliberately makes
   fetch the whole objective and keeps recipes out of the model's hands. The fetch spun
   on "0/1 bucket collecting" for 74 seconds and ended `BLOCKED resource_unavailable`,
   yet the very next replan said "I have the iron now" and crafted the bucket in two
   seconds; a second replan delivered it and the order closed `COMPLETED`. Roughly
   four to five requests and over two minutes for a bucket.
4. **The diagnosis moves the bottleneck down a layer.** The planner did everything
   right; the deterministic task under it did not. Two specific gaps: the no-progress
   watchdog counts only the final item, so it killed an acquisition pipeline that was
   actively mining and smelting iron and declared a false `BLOCKED`; and composite
   work exposes no phase, so the player watched an opaque "collecting" for 74 seconds
   with no hint that real progress was happening. The replan chain rescued the order,
   which proves the lifecycle but is the expensive way to finish a fetch.

### Status against the acceptance criteria

- No hosted reply can contain only assistant speech: **met**, at the provider boundary
  and in the parser.
- "What is in your inventory?" returns a valid terminal answer: **met** (`ANSWERED`).
- Item orders are not deferred to later replans: **enforced** by contract coverage plus
  one repair; verified offline, and live for the bucket order's plan shape.
- "Give me a full set of stone tools" names and delivers final tools: **partially** -
  the failure mode is now honest and bounded (fixture-verified close after repair), but
  a live success has not been observed yet.
- "Come to me" as one finite world task and 3D destinations: **delivered**
  (`come_to_owner`, and `goto` with an optional `y` honored by navigation). Hunting
  evidence and receipt-backed `COMPLETED`: **not started**; see revised priorities.

### Revised priorities

P0's protocol work moved the bottleneck: the semantic boundary now holds, and the live
evidence points at deterministic task depth and legibility. The original P1's item 1 was
delivered by P0 (fetch is the canonical delivery operation, enforced by contract
coverage), and its `count` clarification is partially delivered by the contract's
"desired total" phrasing; the registry rename remains a low-priority cleanup.

#### P1: Make composite acquisition reliable and legible

1. Fetch and craft progress probes must count ingredient and sub-goal progress, not
   only the final item, so the supervisor stops killing working pipelines as
   `no_progress` or `resource_unavailable`. This is the direct fix for the bucket
   order's false `BLOCKED` and would have made it one plan with zero replans.
2. Report the current phase of composite work - searching, mining, smelting, crafting,
   approaching, delivering - in progress messages (promoted from P3; the 74-second
   opaque "collecting" is the live justification).
3. A structured multi-item delivery objective, expanded by deterministic code, so a
   full tool set becomes a list of final items rather than eight parallel fetches.
4. A finite `come_to_owner(distance)` task that continuously rebinds to the owner's
   position.
5. `goto(x, y, z, radius)` if navigation can honor Y; otherwise reject or clarify
   before any movement starts. Delivered: navigation honors an optional `y` via
   `GetToBlockTask`, the arrival probe measures full 3D distance so a wrong-tier
   arrival is an honest `BLOCKED`, and a Y outside the current dimension rejects at
   bind time before any movement.

#### P2: World awareness and evidence (unchanged in substance)

1. Nearby passive mobs in snapshots; a bounded `hunt` or cooked-food objective owned by
   deterministic code.
2. An order ledger with per-deliverable acquired/crafted/delivered/blocked states. The
   contract now provides the normalized deliverables to seed it.
3. Delivery receipts and relevant owner-inventory changes, so `COMPLETED` rests on
   evidence rather than model inference - the bucket order's `COMPLETED` was still
   inferred.
4. Source distance and feasibility information for acquisition choices such as food.

#### P3: Experience and efficiency

1. Handle trivial acknowledgments locally once the semantic protocol has settled;
   `ANSWERED` currently costs a full model round trip per "thx!".
2. Track actions per plan, planner calls per completed order, repair frequency,
   terminal accuracy, and completion cost. The bucket order is the baseline anecdote:
   one deliverable, roughly five requests.
3. Reconsider model or reasoning settings only after P1's task-depth work is measured;
   the repair path now absorbs the single-call habit, which was the strongest argument
   for a model change.
