package com.teamuhi.superbenemies.client

import com.atsuishio.superbwarfare.client.renderer.entity.A10Renderer
import com.atsuishio.superbwarfare.client.renderer.entity.Ac130hRenderer
import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.Ac130Config
import com.teamuhi.superbenemies.config.BossConfig
import com.teamuhi.superbenemies.config.DummyConfig
import com.teamuhi.superbenemies.config.SoldierConfig
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.init.ModEntities
import com.teamuhi.superbenemies.init.ModItems
import net.minecraft.client.Minecraft
import net.minecraft.client.color.item.ItemColor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.entity.NoopRenderer
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.client.event.RegisterColorHandlersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import java.util.function.BiFunction

/**
 * Client-only registrations. The bosses reuse Superb Warfare's own A-10 and AC-130H renderers unchanged, so they look
 * and animate exactly like the player-flown aircraft (flaps, propellers, landing gear).
 */
@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object ClientSetup {

    /** Adds the Config button for this mod on the Mods screen. Called from the mod constructor on the client only. */
    fun registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory::class.java) {
            ConfigScreenHandler.ConfigScreenFactory(BiFunction<Minecraft, Screen, Screen> { _, parent ->
                BossConfigScreen(
                    parent,
                    listOf(
                        ConfigPage(
                            tab("a10"), BossConfig.SPEC, BossConfig.ALL,
                            requires = mapOf(
                                BossConfig.FLARE_MISS_CHANCE to BossConfig.DEFENSIVE_FLARES,
                                BossConfig.DEFAULT_FLARE_CHANCE to BossConfig.DEFENSIVE_FLARES,
                                BossConfig.BARREL_ROLL_FLARE_CHANCE to BossConfig.DEFENSIVE_FLARES,
                                BossConfig.CLIMB_FLARE_CHANCE to BossConfig.DEFENSIVE_FLARES,
                                BossConfig.SIDE_FLARES_CHANCE to BossConfig.DEFENSIVE_FLARES,
                                BossConfig.CANNON_DAMAGE_MULT to BossConfig.CANNON_ENABLED,
                                BossConfig.ROCKET_DAMAGE_MULT to BossConfig.ROCKETS_ENABLED,
                                BossConfig.BOMB_DAMAGE_MULT to BossConfig.BOMBS_ENABLED,
                                BossConfig.MISSILE_DAMAGE_MULT to BossConfig.MISSILES_ENABLED,
                                BossConfig.CANNON_ROUNDS to BossConfig.CANNON_ENABLED,
                                BossConfig.ROCKET_COUNT to BossConfig.ROCKETS_ENABLED,
                                BossConfig.BOMB_COUNT to BossConfig.BOMBS_ENABLED,
                                BossConfig.MISSILE_COUNT to BossConfig.MISSILES_ENABLED,
                                BossConfig.GUN_RUN_CHANCE to BossConfig.CANNON_ENABLED,
                                BossConfig.GUN_RUN_BOMB_CHANCE to BossConfig.CANNON_ENABLED,
                                BossConfig.ROCKET_RUN_CHANCE to BossConfig.ROCKETS_ENABLED,
                                BossConfig.BOMB_RUN_CHANCE to BossConfig.BOMBS_ENABLED,
                                BossConfig.NUKE_RUN_CHANCE to BossConfig.NUKE_ENABLED,
                                BossConfig.MISSILE_ORBIT_CHANCE to BossConfig.MISSILES_ENABLED,
                                BossConfig.ROCKET_MISSILE_RUN_CHANCE to BossConfig.ROCKETS_ENABLED,
                                BossConfig.DOUBLE_GUN_RUN_CHANCE to BossConfig.CANNON_ENABLED,
                                BossConfig.CANNON_ROCKET_STALL_CHANCE to BossConfig.CANNON_ENABLED,
                                BossConfig.ROCKET_BOMB_STALL_CHANCE to BossConfig.ROCKETS_ENABLED,
                                BossConfig.FLARE_COUNT to BossConfig.LIMITED_AMMO,
                                BossConfig.RESUPPLY_SECONDS to BossConfig.LIMITED_AMMO
                            ),
                            disabledWhenEnabled = mapOf(
                                BossConfig.AGGRO_RANGE to BossConfig.GLOBAL_AGGRO
                            )
                        ),
                        ConfigPage(
                            tab("ac130"), Ac130Config.SPEC, Ac130Config.ALL,
                            requires = mapOf(
                                Ac130Config.FLARE_MISS_CHANCE to Ac130Config.DEFENSIVE_FLARES,
                                Ac130Config.CANNON_SIMULTANEOUS to Ac130Config.CANNON_ENABLED,
                                Ac130Config.BOFORS_SIMULTANEOUS to Ac130Config.BOFORS_ENABLED,
                                Ac130Config.HOWITZER_SIMULTANEOUS to Ac130Config.HOWITZER_ENABLED,
                                Ac130Config.CANNON_DAMAGE_MULT to Ac130Config.CANNON_ENABLED,
                                Ac130Config.BOFORS_DAMAGE_MULT to Ac130Config.BOFORS_ENABLED,
                                Ac130Config.HOWITZER_DAMAGE_MULT to Ac130Config.HOWITZER_ENABLED,
                                Ac130Config.CANNON_ROUNDS to Ac130Config.CANNON_ENABLED,
                                Ac130Config.BOFORS_ROUNDS to Ac130Config.BOFORS_ENABLED,
                                Ac130Config.HOWITZER_ROUNDS to Ac130Config.HOWITZER_ENABLED,
                                Ac130Config.SPLIT_FIRE_CHANCE to Ac130Config.CANNON_ENABLED,
                                Ac130Config.FOCUS_FIRE_CHANCE to Ac130Config.CANNON_ENABLED,
                                Ac130Config.FLARE_COUNT to Ac130Config.LIMITED_AMMO,
                                Ac130Config.RESUPPLY_SECONDS to Ac130Config.LIMITED_AMMO
                            ),
                            disabledWhenEnabled = mapOf(
                                Ac130Config.AGGRO_RANGE to Ac130Config.GLOBAL_AGGRO
                            )
                        ),
                        ConfigPage(tab("dummy"), DummyConfig.SPEC, DummyConfig.ALL),
                        ConfigPage(
                            tab("soldier"), SoldierConfig.SPEC, SoldierConfig.ALL,
                            requires = mapOf(
                                SoldierConfig.RELOAD_TICKS to SoldierConfig.UNLIMITED_AMMO,
                                SoldierConfig.RESUPPLY_SECONDS to SoldierConfig.UNLIMITED_AMMO
                            )
                        )
                    )
                )
            })
        }
    }

    /** Tab title of a config page. */
    private fun tab(id: String): Component = Component.translatable("screen.superbenemies.config.tab.$id")

    @SubscribeEvent
    fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(ModEntities.A10_WARTHOG_BOSS.get(), ::A10Renderer)
        event.registerEntityRenderer(ModEntities.AC130_ANGEL_OF_DEATH.get(), ::Ac130hRenderer)
        event.registerEntityRenderer(ModEntities.PLAYER_DUMMY.get(), ::PlayerDummyRenderer)
        event.registerEntityRenderer(ModEntities.DUMMY_SOLDIER.get(), ::DummySoldierRenderer)
        // The crew is invisible and sits inside the aircraft; it needs a renderer but draws nothing.
        event.registerEntityRenderer(ModEntities.BOSS_PILOT.get()) { context ->
            NoopRenderer<BossCrewEntity>(context)
        }
    }

    /** The soldier renderer caches whether the optional custom skin exists; a resource reload may add or remove it. */
    @SubscribeEvent
    fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ResourceManagerReloadListener { DummySoldierRenderer.invalidateSkinCache() })
    }

    /** The egg uses the vanilla spawn-egg model, which is tinted per layer. */
    @SubscribeEvent
    fun registerItemColors(event: RegisterColorHandlersEvent.Item) {
        event.register(
            ItemColor { _, tintIndex -> if (tintIndex == 0) EGG_BASE_COLOR else EGG_SPOT_COLOR },
            ModItems.A10_BOSS_SPAWN_EGG.get()
        )
        event.register(
            ItemColor { _, tintIndex -> if (tintIndex == 0) AC130_EGG_BASE_COLOR else AC130_EGG_SPOT_COLOR },
            ModItems.AC130_BOSS_SPAWN_EGG.get()
        )
        event.register(
            ItemColor { _, tintIndex ->
                if (tintIndex == 0) ModItems.DUMMY_EGG_BASE_COLOR else ModItems.DUMMY_EGG_SPOT_COLOR
            },
            ModItems.PLAYER_DUMMY_SPAWN_EGG.get()
        )
        event.register(
            ItemColor { _, tintIndex ->
                if (tintIndex == 0) ModItems.SOLDIER_EGG_BASE_COLOR else ModItems.SOLDIER_EGG_SPOT_COLOR
            },
            ModItems.DUMMY_SOLDIER_SPAWN_EGG.get()
        )
    }

    private const val EGG_BASE_COLOR = 0x4B5320
    private const val EGG_SPOT_COLOR = 0xFF5A00
    private const val AC130_EGG_BASE_COLOR = 0x2E2E2E
    private const val AC130_EGG_SPOT_COLOR = 0xE8E8E8
}
