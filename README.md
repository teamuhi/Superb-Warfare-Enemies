# Superb Enemies

Enemy and boss addons for [Superb Warfare](https://github.com/Mercurows/SuperbWarfare) on Minecraft 1.20.1 and Forge 47.

<table>
<tr><th>Folder</th><th>Purpose</th></tr>
<tr><td><code>SuperbEnemies/</code></td><td>Boss and enemy addon.</td></tr>
<tr><td><code>SuperbWarfare/</code>, <code>SimpleEnemies/</code></td><td>Read-only reference copies.</td></tr>
</table>

## Features

- A-10 Warthog boss with cannon, rockets, bombs, missiles, flares and multiple attack runs.
- AC-130H boss with 25mm, 40mm and 105mm guns, orbit attacks and multi-target fire.
- Player Dummy for testing boss behavior.
- Dummy Soldier with Igla MANPADS, missile guidance, cover behavior and resupply.
- Optional client hotbar counters for loaded player dummies and dummy soldiers.
- Configurable targeting, weapons, movement, ammunition, flares and resupply.
- Weighted, cover-aware boss targeting: bosses lose sight of targets under overhead cover and attack their last known position.
- In-game config screens with search, sliders, validation, reset controls and unsaved-change tracking.

## Bosses

### A-10 Warthog

- Hunts players, mobs, hostile mobs or other aircraft according to its target weights.
- Uses gun, rocket, bomb, missile, combination and taunt attacks.
- Circles the spot where its last target was seen (for example where it died) until a new target appears.
- Avoids terrain, reacts to homing missiles and supports weighted flare maneuvers.
- Treats terrain beyond the nearest player's render distance as flat, so a short render distance (for example with Distant Horizons) does not stop it from attacking.
- Has health phases, a boss bar, crash behavior, loot and experience rewards.
- Supports finite ammunition and saved resupply trips.

### AC-130H Angel of Death

- Holds a high left-hand orbit instead of diving.
- Fires the 25mm cannon, 40mm Bofors and 105mm howitzer independently.
- Supports normal, split-fire and focus-fire patterns.
- Can attack players, mobs, hostile mobs or other aircraft according to its target weights.
- Automatically switches to piercing rounds when a block is between a gun and its target.
- Supports missile evasion, taunt flares, finite ammunition and resupply.

## Test Entities

### Player Dummy

- Spawn with `superbenemies:player_dummy` or its spawn egg.
- Supports `STRAFE`, `ROAM` and `STATIC` movement.
- Can display health, avoid bosses, use cover and be made invulnerable.

### Dummy Soldier

- Spawn with `superbenemies:dummy_soldier` or its spawn egg.
- Finds, locks and fires Igla missiles at boss aircraft.
- Supports flare discipline, staggered shots, reloads, cover and resupply.
- Uses the same movement modes as the Player Dummy.

## Spawning

- Boss spawn eggs are available in the **Superb Enemies** creative tab.
- Test commands:
  - `/summon superbenemies:a10_warthog_boss ~ ~60 ~`
  - `/summon superbenemies:ac130_angel_of_death ~ ~80 ~`
- Bosses do not spawn naturally and refuse Peaceful, Nether and unsafe locations.

## Configuration

All options are live unless stated otherwise. Config files are available in `config/` and in **Mods > Superb Enemies > Config**.

### Targeting

Both bosses share the same targeting options, each set independently per boss.

- **Target weights** (`playerWeight`, `mobWeight`, `hostileMobWeight`, `planeWeight`): sliders from 0 to 5. 0 means the boss never attacks that kind of target.
  - `mobWeight` covers non-hostile mobs such as animals, villagers and golems. `hostileMobWeight` covers monsters.
  - `planeWeight` is experimental. It targets manned airplanes, manned helicopters and the other boss.
  - Player dummies with their boss-targeting option on count as players.
  - With `randomTargeting` on, the weight is the relative chance of being drawn. With it off, the boss picks the best distance-to-weight score, so a weight 5 target counts as 5 times closer than a weight 1 target.
- **Targeting method** (`targetingMethod`):
  - `STATIC`: the boss always knows where its target is.
  - `DYNAMIC` (default): a target with a cover block anywhere above its head is hidden unless the boss has a clear direct ray to it. The boss switches to an exposed target if one exists. Otherwise it keeps attacking the hidden target's last known position until the target steps back under open sky or becomes directly visible.
  - These blocks don't count as cover: air, water, plants and other blocks without collision, glass, glass panes, leaves, fences, fence gates, carpets, iron bars, chains and scaffolding.
  - The see-through list is the block tag `superbenemies:boss_see_through`, which datapacks can extend for other mods' blocks.
- The A-10 only commits to mobs that are under open sky, whichever method is selected.

### A-10

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code></td><td>2000</td><td>Boss health.</td></tr>
<tr><td><code>cruiseHeightAboveTarget</code></td><td>40</td><td>Flight height.</td></tr>
<tr><td><code>globalAggro</code></td><td>true</td><td>Dimension-wide player hunting.</td></tr>
<tr><td><code>aggroRange</code></td><td>256</td><td>Player range.</td></tr>
<tr><td><code>targetingMethod</code></td><td>DYNAMIC</td><td>Static or cover-aware targeting.</td></tr>
<tr><td><code>playerWeight</code>, <code>mobWeight</code>, <code>hostileMobWeight</code>, <code>planeWeight</code></td><td>5, 0, 0, 0</td><td>Target weights (0-5).</td></tr>
<tr><td><code>mobScanRange</code></td><td>128</td><td>Mob range.</td></tr>
<tr><td><code>randomTargeting</code></td><td>true</td><td>New target per attack.</td></tr>
<tr><td><code>orbitRadius</code></td><td>90</td><td>Orbit distance.</td></tr>
<tr><td><code>*DamageMultiplier</code></td><td>1.0</td><td>Weapon damage.</td></tr>
<tr><td><code>*Enabled</code></td><td>true</td><td>Weapon availability.</td></tr>
<tr><td><code>*Chance</code></td><td>varies</td><td>Attack and flare weights, including the Mk 84 nuke run.</td></tr>
<tr><td><code>limitedAmmo</code></td><td>false</td><td>Finite ammunition.</td></tr>
<tr><td><code>cannonRounds</code>, <code>rockets</code>, <code>bombs</code>, <code>nukes</code>, <code>missiles</code></td><td>600, 56, 9, 1, 8</td><td>Weapon loads.</td></tr>
<tr><td><code>nukeEnabled</code>, <code>nukeRunChance</code></td><td>true, 5</td><td>Single Mk 84 nuke attack availability and weight.</td></tr>
<tr><td><code>flares</code></td><td>varies</td><td>Flare load.</td></tr>
<tr><td><code>resupplySeconds</code></td><td>40</td><td>Resupply delay.</td></tr>
<tr><td><code>blockDamage</code></td><td>true</td><td>Explosion block damage.</td></tr>
<tr><td><code>defensiveFlares</code></td><td>false</td><td>Missile evasion.</td></tr>
<tr><td><code>flareMissChance</code></td><td>10</td><td>Threat miss chance.</td></tr>
<tr><td><code>ignoreBlockCollisions</code></td><td>false</td><td>Fly through blocks.</td></tr>
<tr><td><code>bossBarRange</code></td><td>300</td><td>Boss bar range.</td></tr>
<tr><td><code>xpReward</code></td><td>500</td><td>Defeat experience.</td></tr>
</table>

### AC-130

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code></td><td>3000</td><td>Boss health.</td></tr>
<tr><td><code>cruiseHeightAboveTarget</code></td><td>90</td><td>Orbit height.</td></tr>
<tr><td><code>globalAggro</code>, <code>aggroRange</code></td><td>true, 256</td><td>Player targeting.</td></tr>
<tr><td><code>orbitRadius</code></td><td>180</td><td>Standoff distance.</td></tr>
<tr><td><code>targetingMethod</code></td><td>DYNAMIC</td><td>Static or cover-aware targeting.</td></tr>
<tr><td><code>playerWeight</code>, <code>mobWeight</code>, <code>hostileMobWeight</code>, <code>planeWeight</code></td><td>5, 2, 4, 0</td><td>Target weights (0-5).</td></tr>
<tr><td><code>randomTargeting</code>, <code>randomTargetSeconds</code></td><td>true, 20</td><td>Target switching.</td></tr>
<tr><td><code>mobScanRange</code></td><td>128</td><td>Mob range.</td></tr>
<tr><td><code>*DamageMultiplier</code></td><td>1.0</td><td>Gun damage.</td></tr>
<tr><td><code>*Enabled</code></td><td>true</td><td>Gun availability.</td></tr>
<tr><td><code>splitFireChance</code>, <code>focusFireChance</code></td><td>20, 20</td><td>Special fire chance.</td></tr>
<tr><td><code>longBurstTargetSwitchChance</code></td><td>10</td><td>25mm target switching.</td></tr>
<tr><td><code>*Simultaneous</code></td><td>true</td><td>Fire guns together.</td></tr>
<tr><td><code>limitedAmmo</code></td><td>false</td><td>Finite ammunition.</td></tr>
<tr><td><code>*Rounds</code></td><td>1500, 120, 30</td><td>Gun loads.</td></tr>
<tr><td><code>flares</code>, <code>resupplySeconds</code></td><td>varies</td><td>Flares and delay.</td></tr>
<tr><td><code>defensiveFlares</code>, <code>flareMissChance</code></td><td>false, 10</td><td>Missile evasion.</td></tr>
<tr><td><code>tauntEnabled</code></td><td>true</td><td>Harmless flare passes.</td></tr>
<tr><td><code>blockDamage</code>, <code>ignoreBlockCollisions</code></td><td>true, false</td><td>Block interaction.</td></tr>
<tr><td><code>bossBarRange</code>, <code>xpReward</code></td><td>300, 800</td><td>Bar range and XP.</td></tr>
</table>

### Player Dummy

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code></td><td>20</td><td>Dummy health.</td></tr>
<tr><td><code>invulnerable</code></td><td>false</td><td>Ignore damage.</td></tr>
<tr><td><code>showHealthTag</code>, <code>showDeathMessage</code></td><td>true, true</td><td>Status display.</td></tr>
<tr><td><code>bossesTargetDummy</code></td><td>true</td><td>Boss targeting.</td></tr>
<tr><td><code>movement</code></td><td>STRAFE</td><td>STRAFE, ROAM or STATIC.</td></tr>
<tr><td><code>strafeRadius</code>, <code>roamDistance</code></td><td>6, 12</td><td>Movement distance.</td></tr>
<tr><td><code>roamPauseSeconds</code></td><td>2</td><td>Roam pause.</td></tr>
<tr><td><code>jumping</code>, <code>sprinting</code>, <code>crouching</code></td><td>true</td><td>Movement actions.</td></tr>
<tr><td><code>hideFromBosses</code></td><td>false</td><td>Seek cover.</td></tr>
</table>

### Dummy Soldier

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code>, <code>armor</code>, <code>armorToughness</code></td><td>20, 10, 2</td><td>Defensive stats.</td></tr>
<tr><td><code>invulnerable</code></td><td>false</td><td>Ignore damage.</td></tr>
<tr><td><code>showHealthTag</code>, <code>showDeathMessage</code></td><td>true, true</td><td>Status display.</td></tr>
<tr><td><code>bossesTargetSoldier</code></td><td>true</td><td>Boss targeting.</td></tr>
<tr><td><code>unlimitedAmmo</code></td><td>false</td><td>Ignore missile reserves.</td></tr>
<tr><td><code>missiles</code>, <code>reloadTicks</code></td><td>4, 88</td><td>Load and reload.</td></tr>
<tr><td><code>resupplySeconds</code></td><td>60</td><td>Fresh load delay.</td></tr>
<tr><td><code>missileDamage</code>, <code>explosionDamage</code>, <code>explosionRadius</code></td><td>260, 90, 6</td><td>Missile damage.</td></tr>
<tr><td><code>movement</code></td><td>STATIC</td><td>STRAFE, ROAM or STATIC.</td></tr>
<tr><td><code>engageRange</code>, <code>minEngageRange</code></td><td>300, 24</td><td>Engagement range.</td></tr>
<tr><td><code>lockTimeTicks</code>, <code>aimTurnSpeed</code>, <code>reactionTicks</code></td><td>32, 12, 10</td><td>Aiming response.</td></tr>
<tr><td><code>holdFireOnFlares</code></td><td>true</td><td>Wait for flares.</td></tr>
<tr><td><code>staggerShots</code></td><td>true</td><td>Coordinate launches.</td></tr>
<tr><td><code>coverAfterHit</code>, <code>coverAfterMiss</code></td><td>true, false</td><td>Seek cover after firing.</td></tr>
<tr><td><code>coverSeconds</code>, <code>hideWhenOutOfAmmo</code></td><td>15, true</td><td>Cover and ammo behavior.</td></tr>
</table>

## Dependencies

- Minecraft 1.20.1.
- Forge 47.2.0 or newer 47.x.
- Kotlin for Forge 4.11.0 or newer.
- Superb Warfare 0.8.x at runtime.

## Building

1. Put `superbwarfare*.jar` in `SuperbEnemies/libs/`.
2. Run `gradlew build` from `SuperbEnemies/`.
3. Use `gradlew runClient` for a test client.

JDK 17 is required. The built jar is placed in `SuperbEnemies/build/libs/`.

## License

MIT, see `LICENSE`.
