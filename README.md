![image](https://github.com/user-attachments/assets/2716b281-48a6-4a1a-9305-0560684d4e0f)

# VillagerLobotimizer

A Minecraft Paper plugin that improves server performance by turning off villagers' AI when they're confined to trading halls.

<a href="https://modrinth.com/plugin/villagerlobotomy" target="_blank" rel="noopener noreferrer"><img alt="modrinth" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg"></a> <a href="https://hangar.papermc.io/mja00/VillagerLobotimizer"><img alt="hangar" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/hangar_vector.svg"></a> <a href="https://www.curseforge.com/minecraft/bukkit-plugins/villagerlobotomy" target="_blank" rel="noopener noreferrer"><img alt="curseforge" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg"></a>

![](https://img.shields.io/bstats/players/25704?color=green?label=Players) ![](https://img.shields.io/bstats/servers/25704?color=green?label=Servers) ![](https://modrinth.roughness.technology/full_villagerlobotomy_downloads.svg)

## Features

- Automatically detects when villagers are trapped in trading halls
- Disables AI for trapped villagers to improve server performance
- Maintains villager trading functionality while AI is disabled
- Automatically refreshes villager trades on either a real-time interval (with randomization support) or a fixed in-game daily clock schedule
- Profession-specific restock sounds and level-up celebrations
- Optimized job site detection for better performance
- Night-time trade refresh functionality
- Allows naming villagers to control their behavior ("nobrain", "alwaysbrain")
- Debug mode for troubleshooting
- Command system for checking villager status and managing the plugin

## Commands

- `/lobotomy info` - Shows statistics about lobotomized and active villagers
- `/lobotomy debug` - Shows detailed information about the villager you're looking at
- `/lobotomy debug <entity>` - Shows detailed information about a specific villager
- `/lobotomy debug toggle` - Toggles debug mode
- `/lobotomy wake` - Manually restores AI to the villager you're looking at
- `/lobotomy reload` - Reloads the configuration and applies changes to all villagers

## Configuration

```yaml
#Configuration version - DO NOT MODIFY MANUALLY
config-version: 4

#List of names that will always keep villagers active (case-insensitive)
always-active-names:
  - "alwaysbrain"

#Interval between trapped checks, in ticks, for active villagers
check-interval: 150

#Interval between trapped checks, in ticks, for inactive villagers
inactive-check-interval: 150

#Interval between villager trade restocks, in milliseconds
restock-interval: 540000

#Range (in milliseconds) before restock-interval to start random restock checks. If set to 0, restocking is not randomized. If equal to or greater than restock-interval, restock will always occur.
restock-random-range: 0

#Restock scheduling mode. "interval" = legacy real-time timer (restock-interval/restock-random-range above).
#"schedule" = fixed in-game daily clock times (restock-schedule.times below), independent of real-world elapsed time.
#IMPORTANT: populating "times" below does nothing by itself - mode must be set to "schedule" for it to take effect.
restock-schedule:
  mode: "interval"

  #List of in-game times at which a restock pass runs, in 24h HH:mm format, evaluated against each
  #world's own day/night cycle. Only used when mode is "schedule". Only daytime values are valid
  #(06:00-18:00 inclusive); night-time entries are rejected with a warning on load.
  times:
    - "08:00"
    - "10:30"
    - "13:00"

#Whether to only lobotomize villagers with jobs
only-lobotomize-villagers-with-professions: false

#Whether to only lobotomize villagers that have been traded at least once
only-lobotomize-villagers-with-experience: false

#Whether to lobotomize villagers in boats/minecarts. Does not apply to villagers riding on non-vehicle entities like horses.
always-lobotomize-villagers-in-vehicles: false

#Whether to make lobotomized villagers silent. When enabled, villagers will be muted when their AI is disabled.
silent-lobotomized-villagers: false

#The sound to play when a villager restocks. Leave empty ("") for default sounds.
#A list of sounds can be found at https://jd.papermc.io/paper/1.21.6/io/papermc/paper/registry/keys/SoundEventKeys.html
#Use the name found in the description column, e.g. "entity.villager.celebrate" for the sound played when a villager restocks.
restock-sound: ""

#The sound played when a villager is leveled up. Leave empty ("") for no sound.
level-up-sound: "entity.villager.celebrate"

#Debug mode. Prints debug messages to the console.
debug: false

#Chunk debug mode. Prints debug messages related to chunks
chunk-debug: false

#To ignore villagers stuck in doors, set this to true.
ignore-villagers-stuck-in-doors: false

#To not lobotomize villagers surrounded by non-solid blocks, set this to true.
ignore-non-solid-blocks: false

#To check if there is a roof above a villager before lobotomizing, set this to true
check-roof: true

#Create teams for debugging purposes. This will create colored teams for inactive and active villagers. We use this to color their glowing effect.
create-debug-teams: false

#Disable the update checker. You can disable this if you don't want to be notified about updates.
disable-update-checker: false

#Disable chunk forced Villager updating. This'll disable changes to blocks in a chunk from triggering Villagers in the chunk to be updated.
disable-chunk-villager-updates: false

#Prevent trading with unlobotomized villagers. When enabled, players can only trade with villagers that have been lobotomized.
prevent-trading-with-unlobotomized-villagers: false

#Persist lobotomized state across chunk unloads. When enabled, villagers that are lobotomized will remain lobotomized when their chunk is unloaded and reloaded.
#This prevents lag spikes from villagers needing to be re-evaluated and re-lobotomized after chunk loads.
persist-lobotomized-state: true

#Enable Sentry error tracking to help developers identify and fix bugs. See "Privacy & Telemetry" section below for details.
enable-sentry: true
```

## Restock Scheduling

Villager trades can be refreshed in one of two modes, set via `restock-schedule.mode`:

- **`interval`** (default): the legacy real-time timer. Trades refresh roughly every
  `restock-interval` milliseconds (optionally randomized by `restock-random-range`), independent
  of in-game time.
- **`schedule`**: trades refresh at fixed in-game clock times listed in `restock-schedule.times`
  (24h `HH:mm`, daytime only — 06:00 through 18:00), evaluated against each world's own day/night
  cycle rather than real-world elapsed time. A villager can restock once per configured time per
  day (e.g. three configured times allow up to three restocks/day). Restocks that would have fired
  while a schedule window was skipped over — by sleeping through the night or an admin `/time set`
  jump — fire on the next check rather than being silently dropped. Configured times fire exactly,
  with no randomization. If `mode: schedule` is set but no valid times are configured, the plugin
  falls back to `interval` behavior and logs a warning.

**`restock-schedule.mode` must be explicitly set to `"schedule"` to enable the new behavior.** The
shipped default `config.yml` ships `restock-schedule.times` pre-populated with an example, but
`mode` still defaults to `"interval"` — populating `times` alone has no effect until `mode` is
changed. If `restock-schedule.times` has entries but `mode` is still `"interval"`, the server log
gets an informational reminder of exactly this on every enable/reload (config.yml's own comment
can't reliably reach an already-migrated server, so this check runs at runtime instead — see
`ImplementationPlan.md` §11.5). After switching modes or editing `times`, apply the change with
`/lobotomy reload` (or a restart); avoid changing the *number* of entries in `times` on a live
server with villagers that have already restocked under the previous list unless you're running a
version that includes the restock-schedule stability fix from `reports/QA_report_24082026_1.md`.

`/lobotomy debug` shows the next scheduled restock time, plus job-site-nearby, restocks-today/cap,
and (in `schedule` mode) the raw `lastRestockPassId` PDC value for a targeted villager — useful for
diagnosing why a specific villager isn't restocking.

**Restocking is daytime-only in the overworld; leveling is not.** A lobotomized villager whose
accumulated trading experience crosses a level threshold levels up as soon as that's checked,
regardless of time of day — it does not wait for the next in-game dawn the way a restock does. Both
still require a nearby job site.

## Troubleshooting

**A villager stopped restocking/leveling after being infected and cured (zombify/cure).** Curing a
zombie villager converts it back to a villager *at whatever position the zombie wandered to*, not
back at its original workstation. Since restocking and leveling both require a workstation block
within one block of the villager (in any direction), a villager cured somewhere other than where it
was lobotomized can end up permanently stuck — its AI stays disabled, so it can't walk back on its
own. This is diagnosable via `/lobotomy debug`'s "Job site nearby" line (now also showing the exact
workstation material needed, e.g. `COMPOSTER` for a farmer), and the server log gets a one-time
warning naming the villager and the missing workstation the first time this is detected.

The plugin does **not** automatically teleport the villager back — that would be non-vanilla
behavior and could move a villager away from wherever you deliberately placed it. Instead, fix it
with either:
- **Place a matching workstation block within one block of the villager's current position.**
  `isJobSiteNearby` is a plain material check, not tied to a specific block — any workstation of the
  right type nearby satisfies it, so you don't need to recover the exact original block.
- **Physically move the villager back to its original workstation** using normal vanilla techniques
  (water currents, pistons, minecarts/boats) — villager AI is disabled while lobotomized, but it's
  still affected by physics like any other entity.

Either fix takes effect on the villager's next periodic check (`inactive-check-interval`, a few
seconds by default).

## Special Villager Names

- Name a villager with "nobrain" to force it to always be lobotomized
- Name a villager with "alwaysbrain" to prevent it from ever being lobotomized (configurable in `always-active-names`)

## Sound System

The plugin features an enhanced sound system:
- **Default restock sounds**: When `restock-sound` is left empty, villagers will play default sounds when restocking
- **Customizable sounds**: You can override the default sounds by specifying a custom sound in the configuration
- **Sound reference**: A complete list of available sounds can be found in the [Paper API documentation](https://jd.papermc.io/paper/1.21.6/io/papermc/paper/registry/keys/SoundEventKeys.html)
- **Level-up celebrations**: Villagers play celebration sounds when they level up their trades

## Privacy & Telemetry

This plugin uses two services to help improve quality and fix bugs:

### bStats (Usage Analytics)

The plugin uses [bStats](https://bstats.org/) to collect anonymous usage statistics, including:
- Number of servers using the plugin
- Number of players on those servers
- Server software (Paper, Purpur, etc.)
- Plugin version distribution

This data helps us understand how the plugin is used and prioritize development efforts. All data is anonymous and publicly viewable at [bStats Plugin Page](https://bstats.org/plugin/bukkit/VillagerLobotimizer/25704).

**Opt-out:** You can disable bStats globally in `plugins/bStats/config.yml` by setting `enabled: false`.

### Sentry (Error Tracking)

The plugin uses [Sentry](https://sentry.io/) to automatically report errors and exceptions, helping developers identify and fix bugs proactively.

**What data is collected:**
- Exception stack traces and error messages
- Server type and version (Paper, Purpur, Folia, etc.)
- Minecraft version, Bukkit API version, and Java version
- Plugin version
- Thread information and task scheduling context

**What data is NOT collected:**
- Player names, UUIDs, or any player-identifiable information
- Chat messages or commands
- World data, coordinates, or block information
- Server IP address or hostname
- Any personally identifiable information (PII)

All error data is anonymized and used solely for debugging purposes.

**Opt-out:** Set `enable-sentry: false` in `config.yml` to disable error reporting.

## Requirements

- Paper (or its forks) 1.21.6+
- Java Development Kit (JDK) 21 for development

## Installation

1. Download the latest release from [Modrinth](https://modrinth.com/plugin/villagerlobotomy), [Hangar](https://hangar.papermc.io/mja00/VillagerLobotimizer), or [CurseForge](https://www.curseforge.com/minecraft/bukkit-plugins/villagerlobotimizer)
2. Place the .jar file in your server's plugins folder
3. Restart your server or use a plugin manager to load the plugin
4. Configure the plugin settings in `plugins/VillagerLobotimizer/config.yml` if needed

## Development

### Prerequisites

- Java Development Kit (JDK) 21
- Gradle (wrapper included)

### Building

1. Clone the repository
   ```bash
   git clone https://github.com/mja00/VillagerLobotimizer.git
   cd VillagerLobotimizer
   ```

2. Build the plugin
   ```bash
   ./gradlew build
   ```
   The built plugin will be in `build/libs/VillagerLobotimizer-<version>.jar`

### Running a test server

The project uses the run-paper plugin to easily test changes:

```bash
./gradlew runServer
```

This will download a Paper server for Minecraft 1.21.5 and start it with the plugin installed.

### Publishing

You can publish to Hangar, Modrinth, and CurseForge using the same shaded artifact built by `shadowJar`.

- **Hangar**:

  ```bash
  ./gradlew publishPluginPublicationToHangar
  ```

  Requires `HANGAR_API_KEY` in the environment. The task auto-detects whether the current commit is tagged to decide Release vs Snapshot.

- **Modrinth**:

  ```bash
  ./gradlew modrinth
  ```

  Requires `MODRINTH_TOKEN` in the environment. Optionally set the project id/slug via Gradle property:

  ```bash
  ./gradlew modrinth -Pmodrinth.projectId=villagerlobotomy
  ```

  Game versions are published for `1.21.6`, `1.21.7`, and `1.21.8`. Tagged commits publish a Release; otherwise a Snapshot-like Beta with a short git hash suffix.

- **Publish everywhere** (Hangar + Modrinth):

  ```bash
  ./gradlew publishAll
  ```

- **CurseForge**:

  ```bash
  CURSEFORGE_TOKEN=… \
  CURSEFORGE_GAME_VERSIONS="1.21.11,26.1,26.1.1,26.1.2,26.2" \
  CURSEFORGE_JAR="$(ls build/libs/VillagerLobotimizer-*.jar)" \
  ./scripts/publish-curseforge.sh
  ```

  CurseForge is published by `scripts/publish-curseforge.sh`, not Gradle: project `1601723` only accepts CurseForge's legacy flat "Minecraft" version list (typeID 1), which the CurseForgeGradle plugin cannot emit. The script resolves those IDs and uploads via the API. Keep `CURSEFORGE_GAME_VERSIONS` in sync with `modrinthGameVersions` in `build.gradle.kts`. In CI the release workflow runs this automatically (Release on a tag, otherwise Beta).

## Support

If you encounter any issues, please report them on [GitHub](https://github.com/mja00/VillagerLobotimizer/issues).

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
