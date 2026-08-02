# Minecraft AI Companion — Build Spec

Target: singleplayer, all inference hosted, **single-jar distribution, no sidecar**.

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
- **Slow (seconds, LLM):** receives a compact world snapshot (<300 tokens), emits a _goal_
  plus a line of dialogue. Never a movement.

Tool schema at the level of `mine_iron(32)`, not `press_w()`. Every tool interruptible,
resumable, and returns a structured failure reason.

Snapshot contents: position, health/hunger, hotbar + notable inventory, time of day,
nearby hostile count, current task + progress, last few events, player's relative position.

Trigger planner calls on events (took damage, task failed, nightfall, player moved 100+
blocks) rather than a fixed timer.

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
3. TTS only — type in chat, hear positional speech. Proves the WAV → `short[]` path.
4. Swap command parser for the LLM planner.
5. Add STT + push-to-talk keybind.
6. Memory, initiative, event-triggered personality.

## Gotchas

- **Never block the server tick thread.** `CompletableFuture` on its own executor, apply
  results via `server.execute(...)`. One `.get()` on the main thread = multi-second freeze
  per utterance. Bigger risk now that every call is a network call.
- **Handle 429s gracefully.** Groq free tier is ~2,000 audio requests/day — far more than
  you'll use — but a rate-limited companion should _say something_, not hang silently.
- **No internet, no companion.** Fail with a clear message, not a stall.
- **Silence hallucination.** PTT sends near-empty clips; Whisper invents text
  ("Thanks for watching!"). Gate on clip length / RMS before sending.
- **Building isn't supported** by PlayerEngine. Mining/fetching/fighting work. Scope around it.
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
