# Minecraft AI Companion — Build Spec

Target: singleplayer, all inference hosted, **single-jar distribution, no sidecar**.

## Product goal

Build a genuinely intelligent Minecraft companion with strong world awareness, multi-step
planning, reliable task execution and recovery, useful memory, player understanding, and
initiative. It should feel like a capable teammate, not a natural-language command parser.

The LLM decides intent and plans with high-level tools; deterministic game code executes and
verifies every step. Better context, skills, outcomes, and evaluation matter as much as the model.

Notes:

1. Treat these are only recommendations and not of must-dos.
2. Some things here could be incorrect or not the best decisions so please remember to use your own best judgement as much as possible.

## Stack

| Layer      | Choice                                         | Why                                                          |
| ---------- | ---------------------------------------------- | ------------------------------------------------------------ |
| Version    | MC 1.21.1, Fabric, Java 21                     | Only version where PlayerEngine + Baritone + SVC all line up |
| Body       | PlayerEngine (MIT)                             | Gives a `LivingEntity` inventory, block interaction, hunger  |
| Navigation | Automatone (serverside Baritone fork)          | Pathfinds on any `Entity`, not just the client player        |
| STT        | Groq `whisper-large-v3-turbo`                  | OpenAI-compatible endpoint, fast, generous free tier         |
| TTS        | Groq speech endpoint (Orpheus / PlayAI Dialog) | 48kHz out — matches SVC natively                             |
| Audio out  | Simple Voice Chat `EntityAudioChannel`         | Positional voice; works in singleplayer                      |
| Brain      | Hosted LLM w/ prompt caching + streaming       | Tool calling holds up over long task chains                  |

**Zero new Java dependencies.** `java.net.http.HttpClient` (JDK), Gson (Minecraft bundles it),
`javax.sound.sampled` (JDK — mic capture, WAV parsing, resampling if needed).

Planner can run on Groq too (one key, one provider) but open models are weaker at long
tool-call chains. Two keys is not a real cost.

## Architecture

```
Minecraft JVM (Fabric 1.21.1) — one jar
├── Client: PTT keybind → mic capture (TargetDataLine, 16kHz mono)
└── Server: PlayerEngine entity + Automatone + task executor
        │
        ├──► HTTPS ──► Groq: STT (multipart) + TTS (JSON → WAV)
        └──► HTTPS ──► LLM planner
```

Put the audio layer behind a `SpeechBackend` interface (`transcribe()` / `synthesize()`).
Not for a sidecar — so "swap TTS provider" is a one-file change after 20 hours of hearing
the same voice.

## What ships in the jar

- **In:** your mod code, shaded pure-Java libs via Loom `include()`.
- **Out:** PlayerEngine, Automatone, SVC, Fabric API → declared in `fabric.mod.json` `depends`.
- **Never:** API keys. Generate a config file on first launch.

## Two loops

- **Fast (20Hz, server tick, pure Java):** follow distance, threat response, eating, tool
  selection, hazard avoidance. No inference. This is what makes it feel alive.
- **Slow (seconds, LLM):** receives a compact, relevant world snapshot and emits dialogue plus
  a bounded structured plan. It never emits movement, and replans after meaningful events or
  task outcomes instead of running a long plan blindly.

Tool schema at the level of `mine_iron(32)`, not `press_w()`. Every tool interruptible,
resumable, and returns a structured failure reason.

Snapshot contents: position, health/hunger, hotbar + notable inventory, time of day,
nearby threats/entities, current plan/task + progress, last outcome, recent events, and the
player's relative position. Keep canonical world state separate from the budgeted LLM view.

Trigger planner calls on events (took damage, task failed, nightfall, player moved 100+
blocks) rather than a fixed timer.

## PlayerEngine capability base

Treat PlayerEngine as the companion's deterministic motor-skill library; MineMate supplies
world context, judgment, memory, composition, and verified outcomes. Its existing capabilities
include:

- Survival: MLG bucket clutching, lava/drowning/fire escape, eating, and unstuck recovery.
- Defense: shielding, projectile dodging, creeper spacing, retreating, and weapon selection.
- Items: equipment, crafting, cooking, smelting, smithing, and tool acquisition.
- Storage: chest scanning, depositing, withdrawing, and organized storage.
- Projects: farm setup/repair/harvest, block placement, structures, and Nether portals.
- Exploration: boats, dimension travel, beds, fishing, temples, and strongholds.
- Experimental: full-game and Ender Dragon task sequences.

Classify these as automatic **reflexes**, planner-facing **reliable tools**, or hidden
**experimental skills**. Verify each against `CompanionEntity`, add reflex/task telemetry to the
world snapshot, and expose only capabilities with good outcome checks and scenario coverage.

## Latency

| Path                              | Budget                                                     |
| --------------------------------- | ---------------------------------------------------------- |
| Fast path (keyword match in Java) | ~250ms                                                     |
| Full loop                         | STT + LLM + TTS round trips = **~1.5–2.5s to first audio** |

Everything crosses the network now, so the fast path matters more. `stop`, `wait`,
`come here`, `follow me`, `stay` are keyword-matched in Java and never hit an API.
Roughly 40% of real usage.

Stream the model's dialogue field _before_ its tool call, and start TTS on the first
complete sentence. Emit a filler ack ("hm—") on transcript receipt to mask the rest.

## Audio details

- Capture: 16kHz mono PCM on key-down, close on key-up, ship buffer on release.
- STT call: hand-rolled `multipart/form-data` POST. Tedious, ~40 lines, written once.
- TTS return: strip WAV header, little-endian 16-bit bytes → `short[]`.
- SVC wants **48kHz 16-bit PCM**. Groq TTS appears to output 48kHz — _verify off the
  response header_, don't assume. If it matches, no resampling at all.
- `api.createAudioPlayer(channel, api.createEncoder(), audio)` on an entity-anchored channel.

## Build order

1. PlayerEngine entity that follows you. No AI, no voice. _(Expect this to take a week.)_
2. Chat commands → hardcoded tasks. Build success/failure/blocked reporting here.
3. Audit PlayerEngine's existing skills, verify `CompanionEntity` compatibility, instrument its
   reflexes, and expose a small set of reliable high-level tools.
4. Add planner foundations: world snapshots, recent-event journal, typed tool registry,
   multi-step plan executor, and a scripted fake planner for headless tests.
5. Add a thin text-only hosted LLM planner. Validate every tool call, execute one step at a
   time, feed outcomes back, and handle malformed output/network failure safely.
6. Expand deterministic skills from evaluation failures: inventory/storage, equipment,
   safe travel, landmarks, protection, and useful task compositions.
7. Add episodic/spatial memory, player modeling, initiative, and reflection.
8. Add positional TTS, then STT + push-to-talk, without coupling voice to the planner.

Maintain a small scenario suite throughout: plan validity, completion and recovery rates,
unnecessary model calls, missing context, latency, and cost. Improve intelligence from evidence,
not prompt feel alone.

## Gotchas

- **Never block the server tick thread.** `CompletableFuture` on its own executor, apply
  results via `server.execute(...)`. One `.get()` on the main thread = multi-second freeze
  per utterance. Bigger risk now that every call is a network call.
- **Handle 429s gracefully.** Groq free tier is ~2,000 audio requests/day — far more than
  you'll use — but a rate-limited companion should _say something_, not hang silently.
- **No internet, no companion.** Fail with a clear message, not a stall.
- **Silence hallucination.** PTT sends near-empty clips; Whisper invents text
  ("Thanks for watching!"). Gate on clip length / RMS before sending.
- **Advanced building is experimental.** PlayerEngine contains placement and structure tasks,
  but verify them with `CompanionEntity` before exposing them to the planner.
- **Chunk loading.** A non-player entity likely won't tick its own chunks off-screen. Decide
  early: leashed to render distance, or forced chunk ticket?
- **Version drift.** Pin 1.21.1 in `gradle.properties`. Chasing latest MC kills the project.
- **Personality lives in the fast loop**, not the system prompt. Idle chatter and reactions
  come from event triggers you write.

## If you ever want local models again

- STT: `io.github.givimad:whisper-jni` (Apache 2.0, Maven Central, bundles native binaries).
- TTS: `piper-jni` (same author). Kokoro has no Java path — its phonemizer is Python/C only.
- Still single-jar; models download to config dir on first run, never bundled.

## Where the edge is

Existing mods hand the LLM a generic `mine(block)` and hope. Encoding real game knowledge as
deterministic tasks — correct Y-levels, bridge vs. tunnel, spawn-proofing before a mining
session, torch spacing — is what makes it feel like it has played the game before.

## Links

- PlayerEngine — github.com/shakey2/PlayerEngine (verify fork activity before committing)
- Automatone — github.com/Ladysnake/Automatone
- SVC API — modrepo.de/minecraft/voicechat/api/examples
- Groq STT — console.groq.com/docs/speech-to-text
- Groq TTS — console.groq.com/docs/text-to-speech
