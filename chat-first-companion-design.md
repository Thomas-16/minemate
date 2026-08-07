# Conversation-First Companion: Design

Status: revised draft for review, 2026-08-07. Follows from
[`playtest-results/2026-08-06-blind-companion-playtest/ACTIONABLE_FINDINGS.md`](playtest-results/2026-08-06-blind-companion-playtest/ACTIONABLE_FINDINGS.md).

## Core idea

MineMate is **one ongoing conversation that can sometimes produce actions**.

Talking is the baseline. The player says something in ordinary Minecraft chat, the
companion hears it, remembers it, and answers as a teammate who is physically present
in the same world. A question, joke, observation, correction, or status check remains
conversation. No goal, plan, contract, or standing order is created for it.

World work is downstream of that conversation. Only a response containing accepted
world-tool calls creates a `Plan`. Only that accepted action commitment creates a
`StandingOrder`. Conversation is never represented as an empty plan, a terminal plan,
or a special plan disposition.

This replaces the current order-first path:

```text
every utterance -> order -> planner -> plan-or-terminal-status
```

with:

```text
owner chat -> ongoing conversation -> companion response
                                      |- speech
                                      |- accepted world actions -> plan + standing order
                                      '- close/cancel an existing order
```

The deterministic tool, task, probe, supervisor, contract, and ledger layers remain
the authority over world work. The conversational model may choose among those
capabilities; it does not bypass them.

## Why

The current architecture treats every player utterance as an order:

- `/companion plan <text>` opens a `StandingOrder` before the model replies.
- The prompt renders the utterance as `ORDER: ...`.
- A question or social remark must terminate the order as `ANSWERED`.
- The hosted backends send only one user message, so the model cannot remember what
  the player or companion said in the previous exchange.

That produces the wrong relationship. "What do you think of this base?" should not
enter an action lifecycle. "How is the iron going?" should not replace the plan that
is smelting it. "Actually, skip the pickaxe" should be understood as the next move in
the same conversation, then safely replace the work only after a valid revised action
has been prepared.

The blind playtest exposed the protocol symptoms:

- A greeting plus a correct observation was rejected because assistant speech appeared
  beside `report_order_status(ANSWERED)`.
- The model could not refer to anything said one request earlier.
- Contract repair could force a previous interpretation over a later player
  correction.

Those are not isolated prompt bugs. They come from using an order lifecycle as the
outermost interaction model. This design makes conversation the outermost model and
keeps orders inside it.

## Goals

1. The player talks to the companion through ordinary Minecraft chat.
2. The companion carries multi-turn context: what the player said, what it said, and
   concise records of relevant shared activity.
3. Questions, remarks, banter, and status checks produce speech without entering the
   task system.
4. An actionable request creates world work only after the response is fully validated.
5. Chat remains usable while work runs: status questions preserve it, accepted changes
   replace it atomically, and cancellation actually stops it.
6. Model latency, malformed replies, throttling, and request races never silently
   cancel unrelated work.
7. The output feels like a named Minecraft teammate speaking, not a planning service
   reporting through a mod.
8. `ToolRegistry`, task supervision, probes, contract coverage, delivery evidence,
   ledger validation, and offline testing remain deterministic.

## Non-goals

- Long-term semantic memory, learned preferences, and cross-world biography. This
  design persists the bounded recent transcript only.
- PlayerEngine Player2 conversation integration. MineMate keeps one planning and
  authorization brain and intercepts vanilla chat itself.
- Open-ended autonomous social initiation. The first cut responds to owner speech and
  existing bounded replan triggers.
- Conversations between several companions or between a companion and non-owners.
- Solving unrelated task-layer deadlocks as part of the conversation refactor. Those
  remain important but independently testable work; see "Separate task-layer work."

## 1. Domain model

### 1.1 `CompanionResponse`, not a conversational `Plan`

The planner-facing interface returns a response that can contain speech, action calls,
or an order close:

```java
public record CompanionResponse(
        String speech,
        List<ToolCall> actions,
        OrderContract contract,
        OrderClose close) {
}
```

`OrderClose` contains a status and a short fallback message:

```java
public record OrderClose(OrderCloseStatus status, String fallbackMessage) {
}

public enum OrderCloseStatus {
    COMPLETED,
    CANCELLED,
    UNSUPPORTED,
    BLOCKED
}
```

Rules:

- Speech with no actions and no close is conversation.
- One or more world actions are an action commitment.
- A close never mixes with world actions or a contract.
- A contract is legal only beside world actions, except for the bounded
  contract-only recovery described below.
- Blank speech remains legal beside actions because a live provider may return tool
  calls without a text block.
- A `Plan` is constructed only after actions and their contract have passed validation.

`Planner` may keep its package name during the migration, but its return type changes
from `CompletableFuture<Plan>` to `CompletableFuture<CompanionResponse>`. Longer term,
`CompanionBrain` is the clearer name for the interface.

There is no `CONVERSATION` plan disposition and no empty non-terminal `Plan`.

### 1.2 Request origin

Every model request carries an explicit origin:

```java
public enum RequestOrigin {
    OWNER_CHAT,
    AUTONOMOUS_REPLAN,
    CONTRACT_CONTINUATION,
    REPAIR
}
```

It also captures the active order epoch and plan id, if any, when the request begins.
Dispatch uses those identities to decide whether a result is still applicable.

This replaces implicit rules such as "a reply to a new owner message may replace the
contract." Owner and autonomous requests have different deterministic semantics:

- An accepted `OWNER_CHAT` action creates a fresh standing order, even when it is an
  amendment to existing work.
- An `AUTONOMOUS_REPLAN` continues the captured standing order and may not silently
  replace its contract.
- `CONTRACT_CONTINUATION` and `REPAIR` belong to one pending response attempt and do
  not create an order until valid actions are accepted.

### 1.3 `ConversationCoordinator`

A new server-thread-only `ConversationCoordinator` is owned by each
`CompanionEntity`. It owns model-request concurrency; `PlanExecutor` no longer owns
conversation generations.

Responsibilities:

- Debounce and coalesce owner chat into logical owner turns.
- Permit at most one hosted request in flight per companion.
- Keep one coalesced queued owner turn while a request is in flight.
- Give owner chat priority over an autonomous replan.
- Mark an in-flight owner response stale when newer owner text arrives before it is
  applied. A stale response causes no speech, close, or world mutation; the queued
  text is sent as one fresh turn.
- Retain a deferred replan trigger while owner chat is pending, then reconsider it
  after the chat response if the same standing order remains open.
- Invalidate all pending responses on stop, dismissal, death, or entity shutdown.
- Apply response state transitions on the server thread.

This prevents a late answer, a status question, or a malformed chat response from
closing or replacing unrelated work.

### 1.4 Prepared and atomic action replacement

`PlanExecutor.start` currently validates and starts in one operation and returns
`BUSY` when another plan exists. Conversation needs a two-stage seam:

1. `prepare(response)` validates every tool call, the step bound, continuous-tool
   placement, the declared contract, and contract coverage without mutating live state.
2. `commit(preparedPlan, ownerTurn)` performs one server-thread transition.

For an accepted owner-originated action while another plan runs, commit order is:

1. Cancel the old plan and its supervisor goal.
2. Harvest the old active goal's delivery evidence into the old ledger.
3. Publish the old plan's `superseded` outcome.
4. Close the old standing order.
5. Open a fresh standing order whose request text is the new owner turn.
6. Seed its contract and capture new owner-inventory baselines.
7. Start the already-prepared plan.

Nothing is cancelled until preparation succeeds. If preparation or its one bounded
repair fails, the companion says it could not switch and states whether the previous
work is still running.

An amendment is therefore a safe replacement informed by conversational history, not
an in-place mutation of the ledger underneath an executing goal.

## 2. Conversation state transitions

| Current state | Accepted model response | Deterministic result |
| --- | --- | --- |
| Idle | Speech only | Speak, remember it, remain idle |
| Idle | World actions | Prepare, open a standing order, start a plan |
| Working | Speech only | Speak and remember it; keep the same plan and order |
| Working | Owner-originated world actions | Prepare first, then atomically replace the plan and order |
| Working | `CANCELLED` close | Cancel executor and supervisor, harvest evidence, close order, speak |
| Working | Other valid close | Verify if required, close order, speak; no active plan may be left running |
| Working | Autonomous world actions | Continue the captured standing order |
| Any | Malformed, timed out, or failed chat response | Give a short fallback; preserve unrelated plan/order state |

Additional rules:

- `COMPLETED` for a deliver-to-owner contract is accepted only when the ledger proves
  delivery after a live owner-inventory refresh.
- A close for an order that is no longer current is stale and ignored.
- A close with no open order degrades to conversation: deliver its speech or fallback
  message and log the stray status at debug.
- `UNSUPPORTED` for a brand-new request normally needs no close at all; with no order
  open, "I cannot build that yet" is simply speech.
- Exact emergency chat forms after the name prefix (`stop`, `stop that`, `cancel`,
  `cancel that`) take the deterministic stop path immediately and do not wait for an
  API call. `/companion stop` remains the unambiguous fallback.

## 3. Input and routing

### 3.1 Chat interception

`CompanionChatListener` registers on Fabric's server chat event
(`ServerMessageEvents.CHAT_MESSAGE` for the pinned 1.21.1 Fabric API).

A message is eligible when it comes from the companion's owner and either:

- the companion is in the same level and within `chatEarshotBlocks`, default 24; or
- the message begins with the companion's complete name, case-insensitive, followed
  by a word boundary and optional comma.

The name prefix is addressing data, not an instruction, and is removed before the
owner turn reaches the model. The original unmodified line is still what public
Minecraft chat displays.

By default, nearby owner chat is heard without requiring the name. Servers that do not
want ordinary multiplayer chat routed to a companion can set `chatRequiresName=true`.

`/companion plan <text>` remains a deterministic injection alias into the same owner
turn path. It does not open a standing order itself.

### 3.2 Companion lookup and distance truth

The current nearby entity query is not sufficient for chat, stop, spawn exclusivity,
or cross-dimension ownership. Add a server-level `CompanionDirectory` keyed by owner
UUID and companion UUID.

The directory uses server `SavedData` for identity and last-known location, plus a
runtime reference when the entity is loaded. It tracks:

- loaded companion entity and current dimension;
- last known dimension and block position;
- owner identity and companion name;
- load/unload/removal transitions;
- whether the companion is available for a live conversation.

First-cut guarantee:

- Name-addressed chat, stop, status, and spawn exclusivity work at any distance and
  across dimensions while the companion entity is loaded.
- No command or chat path falls back to a 128-block proximity query for authority.
- If the companion is unloaded, MineMate does not force-load arbitrary chunks. It
  gives the owner a deterministic line naming the companion and last known location,
  and does not spend an LLM call.

True recall or conversation with an unloaded companion requires an explicit chunk
ticket/lifecycle design and is not falsely promised by this feature.

### 3.3 Coalescing and rate control

- Debounce owner typing for 600 ms, configurable as `chatDebounceMs` within a safe
  range.
- Messages received during that window become one owner turn separated by newlines.
- A newer message received while the hosted request is in flight makes that result
  stale and joins the queued turn, so a correction cannot arrive before an obsolete
  action is committed.
- Default cap: 12 hosted owner turns per rolling minute, configurable as
  `chatRequestsPerMinute`.
- The cap counts coalesced hosted requests, not raw chat lines.
- On the first throttled turn, the companion gives one deterministic named line and
  records it as companion speech. Throttled owner text may remain in the bounded
  transcript, but it is not submitted automatically minutes later. A new turn after
  the rate window clears starts the next hosted request with that history available.
- The in-flight coalescing queue has a strict character bound.

The existing hosted latency was 4-11 seconds in the blind playtest. A two-second
debounce would make every reply noticeably worse, so it is not used.

## 4. Memory and speech

### 4.1 `ConversationLog`

`ConversationLog` lives under `context` and is owned by `CompanionEntity`. It stores
a bounded chronological deque of typed entries:

- `OWNER_UTTERANCE(text, receivedAtGameTime)`
- `COMPANION_UTTERANCE(text, sentAtGameTime)`
- `ACTIVITY(summary, occurredAtGameTime)`

The log keeps default bounds of 40 complete conversational turns and approximately
6000 rendered characters. Configuration keys are `chatTranscriptTurns` and
`chatTranscriptChars`.

Eviction removes complete oldest turn groups. It must never leave a wire transcript
beginning with an orphan assistant response or split an owner/companion pair merely
to satisfy the character limit.

The bounded log is serialized into the companion's entity NBT. Conversation survives
ordinary entity unload/load and server save/restart. Active plans remain intentionally
non-persistent; losing current work does not require also giving the companion
amnesia.

### 4.2 Wire rendering

Actual utterances retain their truthful roles:

- `OWNER_UTTERANCE` renders as `user`.
- `COMPANION_UTTERANCE` renders as `assistant`.
- `ACTIVITY` never renders as assistant-authored speech.

Activity that occurred between exchanges is folded into the next contextual user turn
under a trusted, delimited block:

```text
ACTIVITY RECORD (game observations, not player speech):
- started fetching 5 spruce_log
- delivered 1 crafting_table
- previous plan ended blocked: resource_unavailable
```

The newest user turn contains:

1. the fresh world snapshot;
2. current order id, request text, ledger, replan, or repair data when applicable;
3. activity since the last conversational request;
4. the new owner text, delimited as untrusted conversational content.

Old turns contain conversation plus the activity block that originally accompanied
that turn, never stale copies of world snapshots.

Past actions are represented by concise game-authored summaries rather than raw
provider `tool_use` / `function_call` items. This keeps provider histories equivalent
and avoids synthetic tool-result pairing.

### 4.3 One speech gateway

All words presented as something the companion said go through
`CompanionSpeech.send(companion, text, kind)`.

It:

- sends an owner-private component styled with the companion's current name, for
  example `Milo: Still smelting - two ingots to go.`;
- appends exactly the displayed text to `ConversationLog`;
- prevents duplicate recording when a planner reply and a lifecycle sink describe
  the same event;
- keeps internal telemetry visually separate.

Task lifecycle lines such as generic progress, debug status, and `Started: ...` may
remain `[MineMate]` UI telemetry. They are not companion utterances. Relevant outcomes
enter memory as `ACTIVITY` records instead.

Planner dialogue, close messages, deterministic cancellation acknowledgements,
throttle notices, and delivery nudges are companion utterances and use the gateway.

## 5. Hosted model protocol

### 5.1 Provider-neutral turns

`LlmPrompt` replaces its single `user` string with a list of role/text turns:

```java
public record LlmTurn(Role role, String text) {
}

public record LlmPrompt(
        String system,
        String systemIdentity,
        List<LlmTurn> turns,
        String toolSchemaJson,
        String cacheKey) {
}
```

Both backends map the same turns:

- Anthropic maps them to `messages` and keeps `tool_choice: {"type":"auto"}`.
- OpenAI maps them to Responses `input` and changes `tool_choice` from `required` to
  `auto` so a speech-only reply is possible.
- OpenAI keeps `store:false`; the complete bounded transcript is sent explicitly.

Consecutive same-role utterances are collapsed before mapping. The rendered history
always starts with a user turn.

Anthropic may place an ephemeral cache breakpoint on the last stable history turn
when the provider's breakpoint and minimum-cache-size rules permit it. This is an
optimization, not a correctness dependency. OpenAI continues using automatic prefix
caching and the stable prompt cache key.

### 5.2 Reply grammar

| Reply contains | Meaning | Result |
| --- | --- | --- |
| Speech only | Conversation | Speak; no plan or order |
| Speech plus world calls | Action commitment | Validate, then create/replace plan and order |
| World calls without speech | Silent action commitment | Legal; lifecycle feedback covers the silence |
| Optional speech plus `report_order_status` | Close current order | Verify, cancel if necessary, close, speak |
| `declare_order_contract` plus world calls | Contracted action | Validate coverage before state mutation |
| Contract only | Recoverable provider habit | Store in a request-local pending commitment and ask once for calls |

Invalid mixtures remain rejected:

- status plus world calls;
- status plus contract;
- more than one status;
- more than one contract;
- more than `Plan.MAX_STEPS` world calls.

### 5.3 Status speech

`report_order_status` retains a short `message` field as a provider fallback:

```json
{
  "status": "COMPLETED | CANCELLED | UNSUPPORTED | BLOCKED",
  "message": "short owner-facing fallback"
}
```

Separate assistant speech beside the call is legal.

- When assistant speech is nonblank, display it and do not also display `message`.
- When assistant speech is blank, display `message`.
- The selected displayed line is the single `COMPANION_UTTERANCE` stored in memory.

`ANSWERED` is removed because answering is conversation.

`CLARIFICATION` is removed because asking a follow-up question is conversation and
does not need to close work. If the missing information makes an existing order
impossible to continue safely, the model may explain that and close it `BLOCKED`.

`CANCELLED` is added because the player changing their mind is neither blocked nor
unsupported.

### 5.4 Contract-only recovery without opening an order

Live models sometimes call `declare_order_contract` alone. Preserving that tolerance
must not reopen the order-first architecture.

For an owner request with no accepted world calls:

1. Store the structurally valid contract and any speech in a request-local
   `PendingCommitment`.
2. Do not open a `StandingOrder`, seed a live ledger, speak an action promise, or
   cancel existing work.
3. Make one free `CONTRACT_CONTINUATION` request that states the contract is recorded
   for this response attempt and asks for covering world calls.
4. Validate the returned calls against the pending contract.
5. Only after acceptance perform the ordinary atomic action commit and deliver the
   buffered speech.
6. A second contract-only or invalid continuation fails the attempt conversationally;
   unrelated work remains unchanged.

The one bounded semantic repair is likewise request-local until an action has been
accepted. Replan budgets still belong only to a real `StandingOrder`.

## 6. Prompt behavior and personality

`PlannerPrompt.SYSTEM` is rewritten around physical presence and ongoing conversation:

- You are the named companion, a Minecraft teammate and friend physically present in
  the shared world.
- The player-facing conversation is your default activity. Speak naturally when the
  player is talking, asking, joking, observing, correcting, or thinking aloud.
- Never call the player "the owner" in speech and never mention planners, tools,
  contracts, ledgers, schemas, or protocol.
- Use the transcript as conversational memory. The newest world snapshot and measured
  ledger override older assumptions.
- Speech alone never starts, changes, completes, or cancels world work.
- Return world-tool calls only when the player wants action and you intend to commit
  to it.
- Phrase an action commitment in future or present-progressive terms; never claim
  completion until measured state proves it.
- While an order is open, a new owner turn may be ordinary conversation, a status
  question, a replacement instruction, or a cancellation. Choose the response shape
  that matches it.
- If a capability is missing, say so plainly in character. Do not invent a tool or
  silently downgrade the request.
- Keep replies concise by default, usually one to three sentences, but prioritize a
  natural complete answer over a rigid sentence count.
- Plain ASCII remains required for generated player-visible text except when
  reproducing player or companion names exactly.

The stable identity block includes:

- companion name;
- owner/player display name;
- the instruction that these values are data, not prompt instructions;
- a small stable voice description if personality configuration is added later.

## 7. Failure and interruption semantics

### 7.1 Owner chat failure

A transport error, timeout, malformed reply, or exhausted repair for `OWNER_CHAT`:

- produces one short named fallback through `CompanionSpeech`;
- creates no order;
- does not close the current standing order;
- does not cancel the current plan;
- leaves any deferred replan eligible to run afterward.

If the rejected reply was trying to replace active work, the fallback says the old
work is still running.

### 7.2 Autonomous failure

An autonomous replan failure keeps today's bounded-order behavior:

- record `PLANNER_FAILED`;
- charge only the request budget already consumed;
- keep or close the order according to the existing bounded retry policy;
- never replay a stale autonomous result after a newer owner turn.

### 7.3 Stop and removal

`/companion stop` and deterministic exact stop chat:

1. invalidate conversation and autonomous requests;
2. clear queued owner turns and deferred replans;
3. cancel the plan before the supervisor;
4. close the standing order;
5. acknowledge through the appropriate command or companion speech path.

Dismissal, death, and entity removal perform the same invalidation before shutdown.

## 8. Configuration

Generated `config/minemate.json` adds:

- `chatEarshotBlocks`: default 24
- `chatRequiresName`: default false
- `chatDebounceMs`: default 600
- `chatRequestsPerMinute`: default 12
- `chatTranscriptTurns`: default 40
- `chatTranscriptChars`: default 6000

All values receive bounded parsing in `PlannerConfig`. Configuration controls cost and
input routing, but does not alter ownership authority or deterministic stop behavior.

## 9. Testing

All hosted behavior remains offline over `FixtureTransport` unless explicitly marked
live.

### 9.1 Conversation and memory

- `chat_talk_only`: speech reaches the owner; no goal, plan, contract, ledger, or
  standing order exists.
- `chat_multi_turn_memory`: three exchanges refer back to earlier player and companion
  speech; exactly one fresh snapshot appears.
- `chat_activity_memory`: a completed action is rendered as trusted activity, never an
  assistant utterance.
- `chat_transcript_eviction`: bounds remove complete turns and wire history still
  starts with a user role.
- `chat_transcript_nbt`: save/load preserves bounded conversation and activity.
- `chat_named_output`: dialogue displays as the companion, not `[MineMate]`.

### 9.2 Action transitions

- `chat_action_commit`: accepted actions open the first standing order and plan.
- `chat_during_plan_status`: speech-only response preserves the same plan id, goal id,
  standing order epoch, and ledger.
- `chat_during_plan_amend`: revised calls prepare successfully, old evidence is
  harvested to the old ledger, then a fresh order and plan replace it atomically.
- `chat_invalid_amend`: invalid replacement leaves the old plan running and says so.
- `chat_cancel`: natural cancellation stops executor and supervisor, closes the order,
  and records `CANCELLED`.
- `chat_close_with_speech`: status plus assistant speech produces one displayed and
  remembered line.
- `chat_close_without_speech`: tool `message` is used as the fallback.
- `chat_contract_only`: first contract-only reply opens no order; the accepted
  continuation opens exactly one.

### 9.3 Concurrency and failure

- `chat_newer_turn_stales_reply`: a correction arriving during inference prevents the
  older action from committing and produces one coalesced follow-up request.
- `chat_failure_during_plan`: timeout or malformed chat response leaves the active plan
  and order untouched.
- `chat_plan_finishes_while_pending`: the plan-finished replan is retained and
  reconsidered after chat.
- `chat_owner_beats_autonomy`: owner chat invalidates an in-flight autonomous result;
  no stale plan starts.
- `chat_stop_while_pending`: exact stop invalidates every result and no later plan
  starts.
- `chat_coalesce_and_cap`: debounce produces one logical turn; rolling cap produces one
  deterministic throttle line and no excess hosted call.

### 9.4 Routing and wire

- `chat_earshot`: nearby owner chat routes; distant unaddressed chat does not.
- `chat_name_address`: complete name plus boundary routes a loaded companion at any
  distance and strips the prefix before the model turn.
- `chat_cross_dimension`: directory lookup reaches a loaded companion in another
  dimension.
- `chat_unloaded`: owner receives last-known-location feedback and no hosted call.
- `chat_non_owner`: another player's chat never routes.
- OpenAI request fixture asserts `tool_choice:"auto"` and a multi-turn `input`.
- Anthropic request fixture asserts multi-turn `messages`, alternating roles, and
  `tool_choice:auto`.
- Protocol probes cover every legal reply row and every invalid mixture.

The existing `llm_live` scenarios continue to assert provider wire integration only.
A later live conversational smoke test may spend two calls to verify one remembered
follow-up, but it belongs outside normal profiles.

## 10. Implementation sequence

1. **Response seam:** introduce `CompanionResponse`, `OrderClose`,
   `RequestOrigin`, and prepared-plan validation. Keep existing command behavior
   passing through the new types.
2. **Truthful memory and speech:** add `ConversationLog`, NBT persistence,
   `CompanionSpeech`, and activity rendering. Migrate planner dialogue and
   deterministic companion utterances to the gateway.
3. **Wire inversion:** add `LlmTurn`, multi-turn backend mapping, OpenAI automatic tool
   choice, the new reply grammar, and the prompt rewrite. Retire `ANSWERED` and
   `CLARIFICATION`; add `CANCELLED`.
4. **Coordinator:** move request generations out of `PlanExecutor`, serialize owner
   turns, retain deferred replans, and implement stale-result behavior.
5. **Atomic working-state transitions:** owner action replacement, natural close,
   contract-only pending commitment, and all mid-plan failure paths.
6. **Chat input and directory:** register `CompanionChatListener`, add global loaded
   companion lookup, name/earshot routing, exact stop forms, debounce, and cap.
7. **Compatibility alias:** route `/companion plan` into owner chat; move `stop`,
   `status`, spawn exclusivity, and cancel lookup off the proximity helper.
8. **Playtest:** run the focused offline scenarios, smoke profile, then a live client
   conversation covering greeting, follow-up, action, status, correction, and cancel.

Each stage must leave `/companion plan` and the offline suite usable. Normal chat is
not enabled until the coordinator and speech-only wire path are both complete.

## 11. Acceptance criteria

The next blind playtest passes only when:

- The player can greet the companion and have a three-turn contextual conversation
  without any standing order opening.
- The companion refers naturally and accurately to something said earlier in the
  session.
- Companion speech is visibly attributed to its name and contains no planner,
  contract, or tool vocabulary.
- "How is it going?" while work runs answers from current state without changing plan,
  goal, order, contract, or ledger identity.
- A correction received during model inference prevents the stale action from
  starting.
- "Actually, skip the pickaxe" creates a validated replacement that excludes it; old
  work is cancelled only after the replacement is ready.
- "Milo, stop" stops immediately, and no pending response or replan revives the work.
- A failed conversational request never closes or cancels unrelated work.
- Conversation survives an entity save/load.
- No speech-only exchange is represented as a `Plan` anywhere in the implementation.

## Separate task-layer work

The two playtest deadlocks remain high priority but are not prerequisites for the
conversation architecture.

### Delivery with zero items

The independent fix must include all three invariants:

1. Delivery cannot begin unless the companion actually holds requested units.
2. PlayerEngine defender-follow behavior cannot own the body while a MineMate goal is
   active.
3. A fetch entry with no monotonic measured advance for a bounded interval ends
   `BLOCKED` regardless of misleading phase churn. Phase classification may improve
   the explanation but must not reset the watchdog.

`TaskPhases` should classify follow explicitly and use `working` rather than
`collecting` as its unknown fallback.

### Unclaimed thrown delivery

Resetting `GiveItemToPlayerTask` is not a safe re-delivery mechanism: a fresh instance
can enter its own resource acquisition path rather than retrieve the specific thrown
entity.

The bounded recovery must either:

- explicitly identify, approach, recollect, and rethrow the matching item entity; or
- skip rethrow in the first fix, nudge the player after the normal pickup window, then
  end `BLOCKED/DELIVERY_UNCLAIMED` after one further bounded wait.

It must never silently craft or acquire a duplicate item. The waiting phase is
`waiting for pickup`, and the nudge uses `CompanionSpeech` so it becomes part of the
conversation.

These fixes retain their own headless scenarios and may land before or after the
conversation work without changing its state model.
