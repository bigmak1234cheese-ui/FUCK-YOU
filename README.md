# PvP Learner (Fabric mod, Minecraft 1.21.11, client-side, single-player)

A client-side Fabric mod. When you turn it on, it takes over your character's movement, aim and
attacks to fight the nearest hostile mob or other player, and it learns a better fighting style
over time. It saves what it learns in `config/pvplearner.properties`.

It only runs in single-player worlds. It turns itself off on multiplayer servers and in worlds
opened to LAN, on purpose, so you can't get yourself banned.

## What it can do

- **Melee:** holds spacing, strafes, waits for the weapon cooldown, and hunts critical hits.
- **Bow aiming:** if you hold a **bow**, it switches to ranged mode. It keeps its distance, draws, leads moving targets, compensates for arrow drop, and releases at its learned draw time.
- **Crouching:** it taps crouch to dodge and shrink its hitbox. How often is one of the things it learns.

## How it learns

Each fight is one test. The AI tries a slightly mutated copy of its best style (nine settings:
spacing, strafe timing, swing timing, crit hunting, aim accuracy, backing off, crouch rate, bow draw
time, bow distance). Score = damage dealt - damage taken, +10 for a kill, -10 for dying. If the score
beats its best, the new style becomes the best. Over many fights the numbers drift toward what works.
This learns a handful of settings, it is not a neural network.

## Score and logging

Score = damage dealt - damage taken, +10 for a kill, -10 for dying, plus a bonus of up to +5 for killing quickly
and a small bonus (up to about +3) for staying alive. `/pvpai status` also shows the win rate and average score
over the last 20 fights. Every fight is appended to `config/pvplearner_log.csv` (result, damage, score and all nine
settings), which you can open in a spreadsheet to graph progress, like TensorBoard.

## Getting the jar (pick one)

I could not build the jar myself (no network access to download Minecraft and Fabric). Two easy ways:

### Option A: GitHub builds it for you (no installs)

1. Make a free GitHub account if you don't have one, and create a new repository.
2. Upload everything in this folder to it, including the hidden `.github` folder (drag the whole folder into the page).
3. Open the repository's **Actions** tab. A "Build mod jar" run starts automatically (press **Run workflow** if it doesn't).
4. When it finishes, open the run and download the **pvp-learner-jar** file under Artifacts. Unzip it to get `pvp-learner-1.0.0.jar`.

### Option B: Build it on your own PC

You need **Java 21**.

1. Go to https://fabricmc.net/develop/template/ , pick Minecraft **1.21.11** and Mojang mappings, and download the template zip.
2. Copy `gradlew`, `gradlew.bat` and the `gradle/` folder from the template into this folder (next to `build.gradle`).
3. In this folder run: `./gradlew build` (Windows: `gradlew.bat build`)
4. Your jar is `build/libs/pvp-learner-1.0.0.jar` (not the `-sources` one).

If Gradle complains about a version number (loom, loader, Fabric API), copy the matching numbers from
the template's `gradle.properties` into this folder's `gradle.properties`.

## Install

Put the jar and **Fabric API** (for 1.21.11) in your Fabric instance's `mods` folder, then launch.

## Use

Open a single-player world, hold a sword, axe or **bow**, then type in chat:

| Command | What it does |
|---|---|
| `/pvpai start` | Turn the AI on. It fights anything hostile (or another player) within 16 blocks. |
| `/pvpai stop` | Turn it off and give control back. |
| `/pvpai status` | Show fights, wins, losses and its best style so far. |
| `/pvpai learn true` / `false` | Keep experimenting, or just use its best style. |
| `/pvpai eval` | Turn the AI on with learning off, so it just uses its best style. |
| `/pvpai reset` | Forget everything and start over. |

Tips:
- Make a practice arena with `/summon zombie` or `/summon skeleton ~ ~ ~`, or use a fake-player mod to spawn another "player" to fight.
- Use `/gamerule minecraft:keep_inventory true` (1.21.11 name) so dying during training doesn't cost you gear.
- It does not equip weapons, restock arrows or eat food for you, so hold your weapon first and keep arrows in your inventory.
- Switch between sword and bow between fights and it will use the right style (melee and bow settings are learned separately).
- You can stop it at any time with `/pvpai stop`.

## Known limits

- I have not compiled or run this. If the build fails, send me the error and I'll fix it. Minecraft 1.21.11 renamed a lot of internals, so a small fix is possible.
- Bows only (no crossbow or trident yet).
- The AI can't see the screen. It reads entity positions and health from the game.
- Learning against mobs is noisy because every mob fights a little differently, so give it dozens of fights.
