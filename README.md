# Superb Warfare Enemies

Enemy and boss addons for the [Superb Warfare](https://github.com/atsuishio/SuperbWarfare) mod (Minecraft 1.20.1, Forge 47).

| Folder | What it is |
|---|---|
| `SuperbEnemies/` | The addon described below (Kotlin, mod id `superbenemies`). |
| `SuperbWarfare/`, `SimpleEnemies/` | Read-only reference copies of other mods. Not part of the build. |

## Superb Enemies

Adds two flying bosses built on Superb Warfare aircraft and flown by an AI crew, plus a player dummy to test them on and a Dummy Soldier that shoots back:

- **The Plane That Burns the Sky**: an A-10 Warthog that dives, strafes and bombs.
- **Angel of Death**: an AC-130H gunship that circles high above its target and pounds it with its side guns.

### The A-10 boss

- Unlimited cannon, rockets, bombs, AGM-65 missiles, flares and fuel by default. With `limitedAmmo` it has a finite load per weapon; when everything it may use is spent it flies ~600 blocks away to resupply and **despawns once no player can see it** (or when it reaches the resupply point). After `resupplySeconds` a replacement with **the same health and a full load** appears 120-180 blocks from the fight and hunts again, so a withdrawn plane costs no chunk loading or ticking. The pending return is saved with the world (it waits while nobody is online or no free spot is loaded). While it is away its boss bar is hidden; chat announces "A10 withdraws for now" (orange) when it leaves and "A10 begins hunting" (bold red) once it has rearmed. Health is finite and shown in a red boss bar
  (the bar empties at 10% health, when the plane starts to crash).
- Hunts survival/adventure players anywhere in its dimension (`globalAggro`) and never loses them by distance. With `randomTargeting` (default) it draws a **random player (within 700 blocks if any are) at the start of every attack** instead of sticking to one; the victim is never swapped in the middle of a dive or bomb run. Off: it hunts the nearest player (or whoever last hurt it).
- **Can attack mobs too** (`targetPriority`): default `PLAYERS`. `PLAYERS_AND_MOBS` also hunts any living mob within `mobScanRange` that stands under open sky; `MOBS_ONLY` never attacks players. Random draws prefer players and only pick a mob when no player qualifies. Player dummies count as players. Read live; a target that is no longer allowed is dropped at once.
- **Experimental `OTHER_PLANES`** (`targetPriority`): hunts other aircraft only. A plane is any Superb Warfare airplane or helicopter that is manned (a passenger who is not a creative / spectator player) or another boss; parked empty planes, wrecks and crippled bosses are ignored, as are players on foot and mobs. The normal dives, bombs and guns are reused, so fast movers are often missed.
- Cruises about 40 blocks above the target player and circles close (radius 90) and avoids terrain (look-ahead heightmap scan plus Superb Warfare's own orbit autopilot).
- Attack patterns, chosen at random. Each attack uses **one weapon type only**; a cannon run can repeat up to three times, the others never back to back:
  - **Gun run** (most common): backs off ~110 blocks, checks that the dive line and the run-out beyond the target clear every ridge (climbing higher, or abandoning the pass, in mountains), then flies a low strafing pass: the nose lines up on the player's **last known position** and it fires long, scattered GAU-8 barrages (no lead) whether or not the player is behind cover, then pulls out with ground clearance. In the bombing variant (`gunRunWithBombsChance`, its own attack type) it starts the run farther out (220 blocks), **strafes with the GAU-8 from afar, then within ~110 blocks stops firing and drops a stick of three Mk 82s on the player** (steering so the predicted impact sits on the target) before pulling out. If the stick could not go then, it is tossed on the pull-up; if that is missed too, a separate bomb run follows.
  - **Rocket run**: the same approach, but fires a ripple of unguided rockets around the player instead of the cannon.
  - **Rocket run with homing missiles**: the same rocket dive (no cannon), and it also launches two AGM-65 homing missiles at the player while the rockets are leaving.
  - **Double gun run**: a gun run, then a pull-out into a **climb-stall-rotate** (a hammerhead: straight up above the ridges, a pivot on the spot to face the player, then a drop) and a second gun run from the opposite direction.
  - **Cannon-to-rocket run** (`cannonRocketStallChance`): a gun run, the same climb-stall-rotate, then a rocket run from the opposite side.
  - **Rocket-to-bomb run** (`rocketBombStallChance`): a rocket run, the climb-stall-rotate, then a dive-bomb pass (a stick of three Mk 82s). The second pass of every stall-turn attack starts slow, so it pulls out much earlier and holds more power than a normal dive.
  - **Missile orbit** (rare, about 1 in 10 attacks): circles the player and fires AGM-65 homing missiles only, one every 60-90 ticks.
  - **Bomb run**: straight pass that predicts where a Mk 82 will land and releases a stick of three bombs straddling the player.
  - **Taunt** (rare, harmless, `tauntEnabled`): either a low flyby right over the player or a higher pass with a full barrel roll, dropping flares on the way. Fires nothing.
- Releases flares and breaks away when a homing missile is tracking it.
- Three phases by health (above 60%, 60-30%, below 30%): the bomb run joins from phase 2, phase 3 has shorter cooldowns, earlier flare reactions and a flame trail.
- Defeat: at 10% health it crashes, the wreck explodes, and it drops loot (diamonds, netherite scrap, iron blocks, fireworks, a chance of an elytra) and experience.
- The boss bar is hidden from players deep underground (more than 8 blocks below the surface; not in the Nether). Players cannot board, loot, repair or tow it.
- Light on the server: missiles are tracked through an event-driven list (no per-tick world entity scans), terrain and line-of-sight checks never load chunks,
  and the bomb-impact prediction only samples terrain near the release point.

### The AC-130 boss (Angel of Death)

- Superb Warfare's AC-130H with a hidden crew: a pilot plus three gunners on the left-side guns, a 25mm M61 cannon, a 40mm Bofors and a 105mm M102 howitzer.
  Unlimited ammunition by default, `limitedAmmo` works like the A-10's (it flies off, despawns out of sight and a replacement with the same health returns after `resupplySeconds`). While it is away its boss bar is hidden; chat announces "AC-130 withdraws for now" (orange) when it leaves and "AC-130 begins hunting" (bold red) once it has rearmed. Purple boss bar.
- **No dives.** It flies a shallow-bank left-hand pylon turn (counter-clockwise, guns facing inward) while actively holding about 90 blocks above the target. It continuously adjusts the orbit radius so the target stays inside its guns' firing arcs.
- All three gunners lead the target independently: the 25mm fires long scattered bursts, the 40mm steady rounds, the 105mm one heavy shell per reload (slightly slower above 60% health).
- It barrages: cover does not stop it. There is no line-of-sight check, so shells smash through roofs and trees (with `blockDamage`). When the target slips just outside a gun's arc, that gun keeps firing as long as its rounds still land within 10-14 blocks.
  By default all guns fire at once; with the `*Simultaneous` options they can take turns instead.
- **Aggressive and agile:** long 25mm bursts with very short pauses, a wider firing cone and splash window, a faster 105mm in the opening phase, banking up to 65 degrees (faster yaw and roll in its vehicle data) and an orbit flown about 15% tighter.
- **Fire patterns while circling** (rolled every 15-25 s; `splitFireChance` and `focusFireChance`, 20% each by default, need two usable guns):
  - **Split fire**: every gun shoots a different target at the same time (the main target plus other players or mobs within 70 blocks; a gun with no victim of its own shoots a spot on a ring around the main one), and the orbit centres on them.
  - **Focus fire**: all guns pound one fixed spot (where the target stood when it began) with a tight 25mm spread, and the orbit centres on it. A target that keeps moving walks out of it.
  Both ignore the `*Simultaneous` turn-taking while they last.
- **Attacks mobs too** (`targetPriority`): by default any living mob (monsters, animals, villagers) within `mobScanRange` counts as well as survival/adventure players,
  and it engages whichever is nearest. Set `targetPriority` to `PLAYERS` (players only) or `MOBS_ONLY` (never players) to change that.
- **Experimental `OTHER_PLANES`** (`targetPriority`): shells other aircraft only. A plane is any Superb Warfare airplane or helicopter that is manned (a passenger who is not a creative / spectator player) or another boss; parked empty planes, wrecks and crippled bosses are ignored, as are players on foot and mobs. Planes follow the player aggro range and the normal choice rules; split fire can also cover several planes.
  It prefers whoever last hit it, if that is an allowed target, and only swaps to a much closer target. Mobs must be under open sky, so it does not shell caves.
- Breaks away from incoming homing missiles (flares only with `defensiveFlares`). Rare harmless taunt (`tauntEnabled`): a low pass dropping a trail of "angel" flares.
- Defeat works like the A-10's: crash at 10% health, explosion, richer loot and more experience.

### Player dummy

A player-shaped test target, mainly for watching the bosses attack while you are in creative or spectator.

- **Spawn egg** `superbenemies:player_dummy_spawn_egg` (creative tab *Superb Enemies*), or `/summon superbenemies:player_dummy`. Sneak + right-click it with empty hands to pick it up again (you get the egg back outside creative).
- Vanilla player model with the default Steve or Alex skin (picked from its UUID). A name tag shows its health and the last hit, e.g. `Player Dummy ❤ 18.5 / 20 (-4.5)`.
- **Bosses hunt it like a survival player** (`bossesTargetDummy`): the A-10 and the AC-130 both pick it as a target, and the AC-130 shells it even under cover.
- **Movement:**
  - `STRAFE` (default): A/D-style sidestepping with random direction changes, jumps, short sprint bursts and crouch spells, staying within `strafeRadius` of where it was placed.
  - `ROAM`: walks to a random reachable spot up to `roamDistance` blocks from where it stands, pauses up to `roamPauseSeconds`, then picks the next one, chaining across the area but staying within 48 blocks of where it was placed. Uses the `sprinting` toggle and only jumps to climb obstacles on its path (never at random); its body faces where it walks and its head traces the path ahead (turning into corners early) and glances around while it pauses; with a boss or player near, it faces them whenever it stands still.
  - `STATIC`: stands still (walks back if pushed) and turns to face the nearest boss or player.
- **Hiding** (`hideFromBosses`, off by default): while a boss is within 400 blocks it sprints to the nearest roofed spot it can reach (under a roof or trees, within 24 blocks), crouches there, and looks for new cover if its roof gets blown away. With no cover around it strafes instead. Once no boss has been seen for 5 seconds it walks back to its spot.
- `invulnerable`: hits still flash and show on the tag but do no damage (`/kill` still works). It never despawns and drops nothing.

### Dummy Soldier

A player-shaped soldier armed with Superb Warfare's **Igla 9K38 MANPADS**. It hunts the boss aircraft, and the bosses hunt it back.

- **Spawn egg** `superbenemies:dummy_soldier_spawn_egg` (creative tab *Superb Enemies*), or `/summon superbenemies:dummy_soldier`. Sneak + right-click it with empty hands to pick it up again (you get the egg back outside creative). It never despawns and drops nothing. Same name tag, health sync and invulnerability option as the player dummy, plus armor by default.
- **Targets: boss aircraft only** (the A-10 and the AC-130), farther than `minEngageRange` and within `engageRange`. The boss must be at least 16 blocks above the ground under it (the Igla's minimum target height), in clear line of sight (blocks break it), not in smoke, and not crippled or a wreck.
- **Smart aiming down sights:** it spots a boss, reacts for `reactionTicks`, raises the Igla (about 7 ticks of zoom-in) and turns head, body and pitch toward the boss at `aimTurnSpeed` degrees per tick with a small wobble. It neither moves nor jumps while it aims. With several bosses in range it picks the one closest to where it is already looking.
- **The Superb Warfare lock rules:** the lock builds only while the boss stays within 20 degrees of the sights, with a clear line and no smoke. Any break resets it to zero. The locking tone plays while it builds (you can hear it coming) and the locked tone at the end. It fires once the lock time (`lockTimeTicks`, default 32 ticks) is passed.
- **The missile guides only while the soldier aims.** Superb Warfare's Igla loses guidance when its shooter lowers the sights, but a missile owned by a mob would never lose it by itself, so the soldier enforces the rule: it keeps aiming and tracking the boss all the way to impact, and if it stops aiming, is killed or is unloaded the missile goes lost and flies on unguided. Kill the soldier mid-flight and the missile loses the boss.
- **Holds fire on flares** (`holdFireOnFlares`): while decoy flares are burning near the boss it keeps the lock (up to 3 seconds) and waits for them to burn out before firing. Losing the lock while it waits sends it back to seeking.
- **Staggered shots** (`staggerShots`): it will not fire while another soldier's missile is already guiding on the same boss, so a single flare burst cannot spoof two missiles. It holds the lock until that missile resolves.
- **Cover after a hit** (`coverAfterHit`, optionally `coverAfterMiss`): once its missile has hit (or missed) it lowers the Igla and sprints for the nearest roofed cover (the same search as the player dummy's hiding), crouches there for `coverSeconds`, then walks back to its spot and re-engages.
- **Ammo, reload, resupply:** `missiles` counts the loaded one. After each shot there is a reload of `reloadTicks`. With the magazine and reserve empty it hides from nearby bosses (`hideWhenOutOfAmmo`) and a fresh load arrives `resupplySeconds` later (0 = never). `unlimitedAmmo` skips all of that. The ammo counts are saved with the soldier.
- **Bosses hunt soldiers** (`bossesTargetSoldier`, on by default): the A-10 and the AC-130 pick a soldier like a survival player (the AC-130 shells it even under cover). Each dummy type has its own toggle, so the soldier can be hunted while the player dummy is ignored, or the other way round.
- **Movement** (`STATIC` by default) uses the same modes as the player dummy: `STRAFE`, `ROAM` or `STATIC`. Aiming and cover take priority over all of them.
- **Skin:** the default Steve or Alex skin is used until you supply one. Put a **64x64 player-skin layout PNG with wide (4 pixel) arms** at `SuperbEnemies/src/main/resources/assets/superbenemies/textures/entity/dummy_soldier.png` (in a resource pack: `assets/superbenemies/textures/entity/dummy_soldier.png`). It is picked up on the next resource reload (F3+T); without the file the default skin is used.

### Spawning

- **Spawn eggs**: creative tab *Superb Enemies*, items `superbenemies:a10_boss_spawn_egg` and `superbenemies:ac130_boss_spawn_egg`. Right-click a block (or the air) and the boss appears 60 blocks above that point, already flying.
  Refused on Peaceful, in dimensions with a ceiling (Nether), near the world border, or without open sky above the clicked point (the AC-130 needs a wider clear airspace for its 28-block wingspan).
- **Commands** (for testing): `/summon superbenemies:a10_warthog_boss ~ ~60 ~`, `/summon superbenemies:ac130_angel_of_death ~ ~80 ~`
- They have no natural spawns. They ignore creative and spectator players and idle over their home point when there is nothing to attack.

### Config

A-10: `config/superbenemies-common.toml`. AC-130: `config/superbenemies-ac130-common.toml`. Player dummy: `config/superbenemies-dummy-common.toml`. Dummy Soldier: `config/superbenemies-soldier-common.toml`. All four can also be edited in game under **Mods > Superb Enemies > Config**: a tab per file
(A-10 / AC-130 / Player Dummy / Dummy Soldier), a search box, on/off buttons, choice buttons, number boxes with range checks, a description when you hover a row, a reset-to-default button per option plus
**Reset Tab**, yellow markers on edited options with an unsaved-changes count, and a discard prompt when you leave with unsaved edits. The in-game editor changes the local config files only; a
dedicated server uses its own.

#### A-10

| Option | Default | Meaning |
|---|---|---|
| `maxHealth` | 2000 | Boss health (applied when it is created) |
| `cruiseHeightAboveTarget` | 40 | Cruise altitude above the target (20-400). An existing config file keeps its old value: edit or delete it |
| `globalAggro` | true | Hunt any player in the dimension; also shows the boss bar to everyone in it |
| `aggroRange` | 256 | Horizontal targeting range, only used when `globalAggro` is false |
| `targetPriority` | `PLAYERS` | What it attacks: `PLAYERS` (players only), `PLAYERS_AND_MOBS`, `MOBS_ONLY` (never players) or `OTHER_PLANES` (experimental: manned airplanes / helicopters and other bosses only). Read live |
| `mobScanRange` | 128 | Range (32-256) in which mobs are targeted, regardless of `globalAggro`. Unused with `PLAYERS` |
| `randomTargeting` | true | Draw a random target (players first) at the start of every attack (read live) |
| `orbitRadius` | 90 | Orbit radius during the barrage and while idling |
| `cannonDamageMultiplier`, `rocketDamageMultiplier`, `bombDamageMultiplier`, `missileDamageMultiplier` | 1.0 | Damage scaling per weapon (applied when it is created) |
| `cannonEnabled`, `rocketsEnabled`, `bombsEnabled`, `missilesEnabled` | true | Which weapons the boss may use (`[weapons]`, read live). A disabled weapon removes the attack types that use it: no cannon = no gun runs of either kind, no bombs = no gun run with bombs and no bomb run. With all four off it only circles the target |
| `gunRunChance`, `gunRunWithBombsChance`, `rocketRunChance`, `bombRunChance`, `missileOrbitChance`, `rocketMissileRunChance`, `doubleGunRunChance`, `cannonRocketStallChance`, `rocketBombStallChance` | 25, 25, 20, 20, 10, 10, 10, 10, 10 | Percent share of each **attack type** (`[weapons]`, 0-100, read live): plain gun run, gun run that drops bombs, rocket run, straight bomb run (second phase on), homing missile orbit, rocket run with homing missiles (needs rockets and missiles) double gun run (needs the cannon), cannon-to-rocket run (cannon and rockets) and rocket-to-bomb run (rockets and bombs). Relative weights among the attack types whose weapons are enabled and have ammo, so they need not sum to 100; 0 = never used |
| `limitedAmmo` | false | Finite ammunition with a resupply trip (`[ammo]`, read live) |
| `cannonRounds`, `rockets`, `bombs`, `missiles` | 600, 56, 9, 8 | Munitions per load when `limitedAmmo` is on |
| `resupplySeconds` | 40 | Seconds a withdrawn boss stays gone before its replacement (same health, full load) appears near the fight; the flight away is extra |
| `blockDamage` | true | Whether its explosions break blocks (applied when it is created) |
| `defensiveFlares` | false | Whether it drops flares against homing missiles (off: player missiles can hit; it still breaks away). Taunt flares stop for 10 s after it is hit |
| `flareMissChance` | 10 | With `defensiveFlares`: percent chance (0-100) it fails to notice a missile; greyed out in the config screen while `defensiveFlares` is off. An old decimal value in an existing file is reset to the default on load |
| `ignoreBlockCollisions` | false | The plane flies through terrain and buildings (no crash damage, no bounce, no block smashing). Read live; a crippled boss collides normally so its wreck still crashes |
| `bossBarRange` | 300 | Distance at which players see the boss bar |
| `xpReward` | 500 | Experience dropped on defeat |
| `debugLogging` | false | Log bomb-run predictions to the server log |

#### AC-130

Same meaning as the A-10 options unless noted.

| Option | Default | Meaning |
|---|---|---|
| `maxHealth` | 3000 | Boss health (applied when it is created) |
| `cruiseHeightAboveTarget` | 90 | Orbit altitude above the target (40-400) |
| `globalAggro`, `aggroRange` | true, 256 | Player targeting range, as for the A-10 |
| `orbitRadius` | 180 | Base standoff orbit radius (100-400); the AI varies it between 0.45x and 1.8x while keeping the target in the gun arcs |
| `targetPriority` | `PLAYERS_AND_MOBS` | What it attacks: `PLAYERS` (players only), `PLAYERS_AND_MOBS` (the nearest player or living mob) `MOBS_ONLY` (never players) or `OTHER_PLANES` (experimental: manned airplanes / helicopters and other bosses only). Read live; a target that is no longer allowed is dropped at once |
| `randomTargeting`, `randomTargetSeconds` | true, 20 | Switch to a random target every ~`randomTargetSeconds` (5-300, +-25%) instead of sticking to the nearest. Players are drawn first, mobs only when no player qualifies. Read live |
| `mobScanRange` | 128 | Horizontal range (32-256, plus the current orbit radius) in which mobs are considered; unused with `PLAYERS` |
| `cannon25mmDamageMultiplier`, `bofors40mmDamageMultiplier`, `howitzer105mmDamageMultiplier` | 1.0 | Damage scaling per gun (applied when it is created) |
| `blockDamage` | true | Whether its shells and crash break blocks (applied when it is created) |
| `cannon25mmEnabled`, `bofors40mmEnabled`, `howitzer105mmEnabled` | true | Which guns may fire (read live) |
| `splitFireChance`, `focusFireChance` | 20, 20 | Percent chance (0-100) that the periodic fire pattern roll starts split fire / focus fire; they add up, the rest of the time the guns fire normally. Need at least two usable guns (read live) |
| `cannon25mmSimultaneous`, `bofors40mmSimultaneous`, `howitzer105mmSimultaneous` | true | Guns marked simultaneous fire together; each unmarked gun fires alone in its own turn (about 3 s; one shell for the 105mm; skipped early if that gun cannot fire). All on = everything at once, all off = one gun at a time. Read live |
| `limitedAmmo` | false | Finite ammunition with a resupply trip |
| `cannon25mmRounds`, `bofors40mmRounds`, `howitzer105mmRounds` | 1500, 120, 30 | Rounds per load with `limitedAmmo` |
| `resupplySeconds` | 40 | Seconds a withdrawn boss stays gone before its replacement (same health, full load) appears near the fight; the flight away is extra |
| `defensiveFlares`, `flareMissChance` | false, 10 | As for the A-10 (the miss chance is a percent) |
| `tauntEnabled` | true | Rare low "angel flares" pass (harmless) |
| `ignoreBlockCollisions` | false | Flies through terrain, as for the A-10 |
| `bossBarRange`, `xpReward`, `debugLogging` | 300, 800, false | As for the A-10 (debug logs target switches and orbit radius changes) |

#### Player dummy

All options are read live.

| Option | Default | Meaning |
|---|---|---|
| `maxHealth` | 20 | Dummy health (1-1024, the vanilla attribute cap); a full-health dummy is topped up when you raise it |
| `invulnerable` | false | Takes no damage; hits still flash and show on the tag. `/kill` still works |
| `showHealthTag` | true | Name tag with health and the last hit |
| `showDeathMessage` | true | Announce in chat when a dummy is killed ("Player Dummy was shot by ...") |
| `bossesTargetDummy` | true | Bosses hunt dummies like survival players. Off: the A-10 ignores them, the AC-130 treats them as ordinary mobs |
| `movement` | `STRAFE` | `STRAFE` (player-like dodging), `ROAM` (walks from random spot to random spot) or `STATIC` (stands still, turns to face threats) |
| `strafeRadius` | 6 | How far (1-32 blocks) it strafes from where it was placed |
| `roamDistance` | 12 | `ROAM`: how far (4-32 blocks) each new spot can be from where the dummy stands |
| `roamPauseSeconds` | 2 | `ROAM`: longest pause (0-30 s, random) at each spot |
| `jumping`, `sprinting`, `crouching` | true | Which moves `STRAFE` uses (`ROAM` uses only sprinting; `jumping` means random hops, which only `STRAFE` does) |
| `hideFromBosses` | false | Run for cover while a boss is within 400 blocks (any movement mode) |

#### Dummy Soldier

All options are read live. The movement options work as for the player dummy.

| Option | Default | Meaning |
|---|---|---|
| `maxHealth` | 20 | Soldier health (1-1024); a full-health soldier is topped up when you raise it |
| `armor` | 10 | Armor points (0-30) |
| `armorToughness` | 2 | Armor toughness (0-20) |
| `invulnerable` | false | Takes no damage; hits still flash and show on the tag. `/kill` still works |
| `showHealthTag` | true | Name tag with health and the last hit |
| `showDeathMessage` | true | Announce in chat when a soldier is killed |
| `bossesTargetSoldier` | true | The bosses hunt soldiers like survival players. Off: the A-10 ignores them, the AC-130 treats them as ordinary mobs |
| `unlimitedAmmo` | false | Never runs out of missiles (it still reloads between shots) |
| `missiles` | 4 | Missiles per load (1-64), including the loaded one |
| `reloadTicks` | 88 | Reload time after each shot (20-400 ticks) |
| `resupplySeconds` | 60 | Seconds until an empty soldier gets a fresh load (0 = never) |
| `missileDamage` | 260.0 | Direct-hit damage of the missile |
| `explosionDamage` | 90.0 | Explosion damage |
| `explosionRadius` | 6.0 | Explosion radius |
| `movement` | `STATIC` | `STRAFE`, `ROAM` or `STATIC` |
| `strafeRadius` | 6 | `STRAFE`: how far (1-32 blocks) from where it was placed |
| `roamDistance` | 12 | `ROAM`: how far (4-32 blocks) each new spot can be |
| `roamPauseSeconds` | 2 | `ROAM`: longest pause (0-30 s) at each spot |
| `jumping`, `sprinting`, `crouching` | true | Which moves `STRAFE` / `ROAM` use, as for the player dummy |
| `engageRange` | 300 | Farthest boss it will engage (32-384 blocks) |
| `minEngageRange` | 24 | Closest boss it will engage (0-128 blocks) |
| `lockTimeTicks` | 32 | Ticks the lock must hold before firing (5-200) |
| `aimTurnSpeed` | 12.0 | How fast it turns toward the boss while aiming (1-45 degrees per tick) |
| `reactionTicks` | 10 | Pause between spotting a boss and raising the Igla (0-100) |
| `holdFireOnFlares` | true | Wait for decoy flares near the boss to burn out before firing |
| `staggerShots` | true | Do not fire while another soldier's missile is already guiding on the same boss |
| `coverAfterHit` | true | Run for cover after a missile hit |
| `coverAfterMiss` | false | Run for cover after a missile miss |
| `coverSeconds` | 15 | How long it stays under cover (1-120 s) |
| `hideWhenOutOfAmmo` | true | Hide from nearby bosses while it has no missile left |

### Dependencies

- Minecraft 1.20.1, Forge 47.2.0 or newer 47.x.
- [Kotlin for Forge](https://github.com/thedarkcolour/KotlinForForge) 4.11.0 or newer.
- **Superb Warfare** 0.8.x (mandatory at runtime). For building, put its jar in `SuperbEnemies/libs/` (see `SuperbEnemies/libs/README.md`); the jar is git-ignored.

### Building

1. Copy the Superb Warfare jar (`superbwarfare*.jar`) into `SuperbEnemies/libs/`.
2. From `SuperbEnemies/`: `gradlew build` (jar ends up in `build/libs/`), or `gradlew runClient` to test in game.
   Requires JDK 17 (Gradle downloads one automatically if missing).
3. Optional flags: `-Psbw_remap=false` (jar is already dev-mapped), `-Psbw_extra_compile_deps=false` (skip the compile-only GeckoLib/Curios/SimpleBedrockModel dependencies).


## License

MIT, see `LICENSE`.

## Recent behavior updates

- AC-130 missile breaks are brief, and its side guns continue firing while it evades.
- A-10 and AC-130 flare loads can be configured with `flares` when `limitedAmmo` is enabled.
- Soldier movement supports `STATIC` and `ROAM`; soldiers no longer strafe, and ROAM soldiers do not actively hide.
- MANPAD locks take four seconds by default and use Superb Warfare's locking and locked sounds.
- AC-130 attack patterns roll every 5-9 seconds, and non-simultaneous guns change turns about every 1.2 seconds for much heavier pressure.
- During split fire against multiple enemies, the AC-130 moves to a farther 1.45x standoff ring so its left-side weapons can take pot shots without flying through the target cluster.
