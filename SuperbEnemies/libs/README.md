# libs/

Drop the **Superb Warfare mod jar** here. The jar is not committed (`libs/*.jar` is in the root `.gitignore`).

- Name: any file matching `superbwarfare*.jar`, for example `superbwarfare-0.8.10-snapshot-mc1.20.1-abc1234.jar`.
  `*-sources.jar` and `*-javadoc.jar` are ignored.
- Version: Superb Warfare 0.8.x for Minecraft 1.20.1 / Forge 47 (the boss uses `A10Entity`, `VehicleEntity`,
  `ProjectileCalculator`, `ITrackableProjectile`, `GunData` and the `sbw/vehicles` data loader, all of which must exist in the jar).
- Use the normal release jar (SRG names). Gradle remaps it to the dev (Mojang/Parchment) names automatically.
  If you instead build Superb Warfare from source and use its dev-mapped `jar`, pass `-Psbw_remap=false`.

The same jar is used for compiling and for `runClient`. Superb Warfare bundles GeckoLib, Curios, Cloth Config and its other
libraries inside its jar (Jar-in-Jar). If `runClient` reports a missing mod, put that mod's jar into `SuperbEnemies/run/mods/`.

Build: from `SuperbEnemies/` run `./gradlew build` (Windows: `gradlew.bat build`).
