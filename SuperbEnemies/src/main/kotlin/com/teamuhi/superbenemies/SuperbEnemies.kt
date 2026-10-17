package com.teamuhi.superbenemies

import com.teamuhi.superbenemies.client.ClientSetup
import com.teamuhi.superbenemies.config.Ac130Config
import com.teamuhi.superbenemies.config.BossConfig
import com.teamuhi.superbenemies.config.DummyConfig
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedBosses
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedProjectiles
import com.teamuhi.superbenemies.entity.dummy.TrackedDummies
import com.teamuhi.superbenemies.init.ModCreativeTabs
import com.teamuhi.superbenemies.init.ModEntities
import com.teamuhi.superbenemies.init.ModItems
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import org.apache.logging.log4j.LogManager
import thedarkcolour.kotlinforforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.forge.MOD_BUS

/**
 * Mod entry point. Superb Warfare is a mandatory dependency (declared in mods.toml with `ordering = "AFTER"`),
 * so every SBW class referenced from this mod is already loaded when the registries below fire.
 */
@Mod(SuperbEnemies.MOD_ID)
object SuperbEnemies {
    const val MOD_ID = "superbenemies"

    @JvmField
    val LOGGER = LogManager.getLogger(MOD_ID)

    init {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BossConfig.SPEC)
        // One file per boss so each can be tuned (and reset) independently.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Ac130Config.SPEC, "superbenemies-ac130-common.toml")
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, DummyConfig.SPEC, "superbenemies-dummy-common.toml")
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable { ClientSetup.registerConfigScreen() } }

        val bus = MOD_BUS
        ModEntities.REGISTRY.register(bus)
        ModItems.REGISTRY.register(bus)
        ModCreativeTabs.REGISTRY.register(bus)

        // Entity trackers the AI reads instead of scanning levels (guided missiles, boss aircraft, player dummies).
        TrackedProjectiles.register(FORGE_BUS)
        TrackedBosses.register(FORGE_BUS)
        TrackedDummies.register(FORGE_BUS)
    }
}
