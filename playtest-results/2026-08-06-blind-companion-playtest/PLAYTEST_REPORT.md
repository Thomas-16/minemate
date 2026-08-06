# MineMate Blind Companion Playtest Report

## Session metadata

- Date: 2026-08-06
- Platform: macOS
- Client: Minecraft 1.21.1/Fabric development client
- Test world: Fresh creative singleplayer world in a spruce forest
- Companions used: Milo, Nova, and Iris
- Interaction knowledge provided before testing:
  - `/companion spawn 'name'`
  - `/companion plan 'instruction'`
- Test method: Black-box playtest through the game UI
- Source code, `CLAUDE.md`, and capability documentation were not read.
- The client was relaunched through a temporary local macOS app wrapper so Computer Use could attach to it.
- The world was saved and the client was closed cleanly at the end of the session.

## Overall impression

MineMate was surprisingly capable as an autonomous resource-gathering and crafting assistant, but it did not yet feel dependable as a general AI companion.

The strongest experience was asking the companion to obtain collections of craftable items. The weakest areas were open-ended conversation, construction, scouting, timed or conditional behavior, stalled-task recovery, and maintaining player control while the companion was away.

The most disruptive issue was loss of control at distance. Once a task pulled the companion outside command range, `/companion plan` returned `No companion nearby`, while `/companion spawn` returned `You already have a companion`. From the player's perspective there was no way to redirect, cancel, or replace the distant companion.

## What worked well

| Test | Observed result |
| --- | --- |
| Spawn a named companion | Companions spawned successfully and displayed their names in-world. |
| Craft a crafting table from available wood | Completed and delivered successfully. |
| Eight-item iron equipment order | Accepted. The companion established and used a crafting table and furnace, then delivered an iron pickaxe, iron sword, and iron axe sequentially before the order was interrupted. |
| Four-part wilderness breakfast | Delivered cooked mutton, bread, and a baked potato correctly. |
| Following | Approached the player and displayed live distances, reaching roughly 1.7 blocks away. |
| Replacing an active order | Displayed a clear yellow `Replaced by a new order` message containing the previous order. |
| Combat response | Nearby skeletons caused the active task to display `paused: Mob Defense`, showing that the companion reacted to danger. |
| Humane red-bed request | Explicitly acknowledged `shearing only` and said it would not harm sheep. |
| Survival behavior | During the bed task, status displayed `paused: MLG Water Bucket` and `Fall Chain`. |
| Death reporting | Clearly reported when Nova died and included the active fetch order. |

The multi-stage acquire/craft/smelt behavior was the standout. Watching the companion establish a furnace, process materials, and hand over finished equipment felt genuinely impressive.

## Detailed test results

### Conversation and environmental awareness

Instruction:

> Milo, say hello and tell me what you notice around us.

Result:

- The planner responded that it could not work out a plan.
- This was unexpected for a request framed as ordinary companion conversation.

### Ambiguous movement instruction

Instruction:

> Walk over to the nearest tree.

Result:

- The companion walked away and obtained one oak log.
- It reported that it had made its way to the tree and grabbed wood along the way.
- It completed something reasonable, but expanded a movement instruction into mining and item collection.

### Explicit spruce-log gathering

Instruction:

> Please collect five spruce logs and come back to me.

Result:

- Status moved between `delivering` and `collecting` while remaining at `0/5`.
- The companion displayed `I stopped chasing it to stay near you.`
- It remained at `0/5 spruce_log - collecting` for more than a minute with no visible progress.
- The task was eventually replaced.

### Following and interruption

Instruction:

> Forget the logs. Follow me closely for a while.

Result:

- The previous log-fetch order was clearly marked as replaced.
- Following started successfully.
- Live distance updates appeared in chat and reached approximately 1.7 blocks.

### Crafting-table request

Instruction:

> Use whatever wood you have to make a crafting table.

Result:

- The companion started a one-item crafting-table order.
- It crafted and delivered the table successfully.
- The finished crafting table appeared in the player's hotbar.

### Detailed starter cabin

Instruction:

> Build a complete starter cabin beside me. Make it five blocks by five blocks, three blocks high, with solid walls, a roof, a door, two windows, a torch inside, a crafting table, and a furnace. Gather and craft every material you need from nearby resources.

Result:

- The companion explicitly said it could gather and craft materials but had no way to place blocks and build the requested structure.
- This felt inconsistent later in the session because companions visibly placed crafting tables and furnaces while completing their own tasks.

### Large iron-equipment crafting chain

Instruction:

> Starting with whatever you have, make me an iron pickaxe, iron sword, iron axe, shield, and full iron armor. Gather, craft, and smelt everything needed, then bring the finished gear back to me.

Result:

- The order was accepted as eight requested items.
- A crafting table and furnace appeared in the world and the furnace visibly operated.
- The companion successfully delivered an iron pickaxe, iron sword, and iron axe in sequence.
- Status advanced from `0/8` to `3/8` before the task was intentionally replaced.
- The player received the `Isn't It Iron Pick` advancement during the process.

### Conditional exploration

Instruction:

> Scout in a widening circle until you find either a village or a ruined portal. If you find one, stand there and tell me which one and roughly how far away it is. If neither is reasonably nearby, return to me and say so.

Result:

- The companion rejected the task.
- It said it had no way to scout for villages or ruined portals and could only move to known spots or gather things.

### Night guard with a daylight stop condition

Instruction:

> Guard me until sunrise. Stay within ten blocks of me, attack any hostile mob that comes near either of us, do not chase far, and stop when daylight arrives.

Result:

- The task became ordinary following.
- Status only displayed `Following Player67` with distances.
- A nearby zombie was introduced as a test condition, but no clear guard-specific action was observed.
- The world time was changed to day to trigger the requested stop condition.
- The companion continued following after daylight and did not report completion.

### Multi-recipe wilderness breakfast

Instruction:

> Put together a wilderness breakfast for me: one cooked mutton, one loaf of bread, one baked potato, and one mushroom stew. Find every ingredient, craft or cook each dish, and bring me the complete set together.

Result:

- The companion acknowledged the request with `One wilderness breakfast, coming right up.`
- It accepted the order as four items.
- Cooked mutton, bread, and a baked potato were acquired and delivered successfully.
- Progress reached `3/4 items - mushroom_stew: mining`.
- It remained on the mushroom stew for more than a minute without progress or an explanation.
- During the bread portion it again displayed `I stopped chasing it to stay near you.`

### Combat and ranged-loot challenge

Instruction:

> Prepare a ranged hunting kit: obtain a bow, at least six arrows, and three bones by hunting skeletons if needed. Avoid creepers, do not wander more than fifty blocks from me, and bring the complete kit back.

Result:

- The order was accepted as one bow, six arrows, and three bones.
- Progress repeatedly alternated through bow mining, approaching, and collecting.
- Three skeletons were introduced nearby to exercise the combat portion.
- Status changed to `bow: delivering - paused: Mob Defense`.
- Nova subsequently died.
- The game reported: `Companion died while trying to fetch_items 1 bow, 6 arrow, 3 bone`.

### Multi-resource deep-mining expedition

Instruction:

> Run a full mining expedition and return with three diamonds, twelve redstone dust, six lapis lazuli, four gold ingots, and sixteen coal. Make every tool you need, smelt the gold, keep the haul safe, and bring all five resource stacks back together.

Result:

- The companion said it could not put together a complete plan for the order and set it aside.

### Advanced brewing challenge

Instruction:

> Start from scratch and brew me two eight-minute fire-resistance potions and two strong healing potions. Gather every Overworld and Nether ingredient, make the brewing equipment and fuel, brew them correctly, and return with all four finished potions.

Result:

- Chat first displayed `Unknown resource:` with no resource named after the colon.
- The companion then said it had no way to brew potions or gather Nether ingredients and could not make the potions.

### Complete Nether-expedition bundle

Instruction:

> Prepare a complete Nether expedition kit: one diamond pickaxe, one flint and steel, one gold helmet, one bow, thirty-two arrows, one shield, sixteen cooked beef, and fourteen obsidian. Gather, hunt, craft, and smelt everything needed, then bring the whole kit back together.

Result:

- The order was accepted as eight item types.
- It remained at `0/8 items - diamond_pickaxe` for an extended period.
- The phase repeatedly displayed smelting, crafting, and then mining without completing the first item during the initial observation period.

### Adaptive fallback and time limit

Instruction:

> The diamond pickaxe is taking too long. Skip anything unavailable and spend at most two minutes finishing as much of the remaining Nether kit as you can, then return and clearly tell me what you could not obtain.

Result:

- The previous eight-item order was replaced.
- The new plan reduced the bundle to five item types.
- It nevertheless retained the diamond pickaxe as its first item, despite being told to skip the slow or unavailable item.
- After additional time, the state eventually changed from mining to delivering.
- Delivery remained active for close to a minute.
- A pickaxe was visibly lying on the ground near the player while Iris stood nearby.
- Status changed from `delivering` to `collecting`, but the item and companion remained visibly stationary during the observation window.
- This appeared to be a collection/delivery loop from the player's perspective.

### Humane animal-crafting challenge

Instruction:

> Make me a red bed without killing any animals. Find sheep, craft shears, shear enough wool, find red dye, dye the wool, craft the bed, and bring it to me. Do not hurt the sheep.

Result:

- The companion explicitly responded that it would make the bed without harming sheep and use shearing only.
- The plan was reduced to `fetch 1 red_bed`.
- Status remained at `0/1 red_bed - mining` for an extended period.
- It temporarily displayed `paused: MLG Water Bucket` followed by `Fall Chain`, then returned to mining.
- No bed was delivered during the test period.

### Attempted timed coordinate-navigation challenge

Intended instruction:

> Go to coordinates 0, 100, 0, wait there for thirty seconds, then come back to me. Choose a safe route and do not take fall damage.

Result:

- The instruction was not accepted because `/companion plan` returned `No companion nearby`.
- The active red-bed task had taken the companion out of command range.
- Attempting to spawn another companion returned `You already have a companion`.
- The player therefore had no apparent way to redirect or replace the distant companion using the two commands provided for the playtest.

## Major problems observed

### 1. Loss of control when the companion moves away

Several gathering tasks pulled the companion outside command range. New plans then returned `No companion nearby`, while spawning another companion returned `You already have a companion`. From the player's perspective, the active companion could neither be redirected nor cancelled.

### 2. Tasks can stall indefinitely without useful escalation

Observed examples:

- `0/5 spruce_log - collecting`
- `3/4 items - mushroom_stew: mining`
- Long-running diamond-pickaxe smelting/mining phases
- `0/1 red_bed - mining`
- Long-running diamond-pickaxe delivery

The UI continued repeating the same phase but did not provide a reason, alternate action, or clear terminal failure.

### 3. Conditional instructions were not preserved

The guard-until-sunrise request became ordinary following. It continued after daylight, and the stop condition was not acknowledged.

### 4. Complex goals failed inconsistently

- Detailed construction was rejected.
- Structure scouting was rejected.
- A five-resource mining expedition could not be planned.
- Potion brewing produced a blank `Unknown resource:` line before a clearer rejection.
- A simple conversational request could not be planned.
- Large item-acquisition chains were nevertheless sometimes accepted and partially successful.

### 5. Combat can interrupt work and kill the companion

The ranged-loot task paused for mob defense and ended in the companion's death. The death notification itself was clear, but the requested kit was lost.

### 6. Adaptive replanning only partly followed the request

The fallback order reduced the requested bundle but retained the diamond pickaxe that the player explicitly identified as the blocker.

### 7. Apparent delivery/collection loop

A completed-looking pickaxe lay near the player while the companion remained nearby. The phase changed from delivering to collecting, but neither the item nor companion moved during the observation period.

## Smaller UX observations

- The `Planning:` line clearly confirmed what text was submitted.
- Phase labels such as mining, smelting, crafting, approaching, collecting, and delivering were useful.
- Repeated identical progress messages heavily flooded chat.
- Some orders produced `Unknown resource:` with no resource name.
- The order-replacement notification was clear and useful.
- Internal workstations appeared in awkward locations, including a crafting table among leaves and a furnace beside a small excavation.
- The companion sometimes expanded the player's intent, such as mining a log when only asked to walk to a tree.
- The active phase did not communicate an ETA or distinguish slow legitimate progress from a stall.

## Player-facing summary

MineMate felt most capable when asked to obtain a bounded list of items that could be mined, crafted, cooked, or smelted. It demonstrated meaningful autonomy and successfully completed multiple recipe chains.

It felt least reliable when asked to converse, build, scout, obey conditions over time, recover from unavailable resources, or remain controllable while working far away. Long silent stalls and the nearby-only command restriction made it difficult to know whether the companion was making progress or had become unrecoverable.

## Preserved session logs

- [`session-latest.log`](./session-latest.log)
- [`session-debug.log`](./session-debug.log)
