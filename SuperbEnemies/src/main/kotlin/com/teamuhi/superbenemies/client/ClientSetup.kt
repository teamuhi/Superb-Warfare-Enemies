package com.teamuhi.superbenemies.client

import com.atsuishio.superbwarfare.client.renderer.entity.A10Renderer
import com.atsuishio.superbwarfare.client.renderer.entity.Ac130hRenderer
import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.Ac130Config
import com.teamuhi.superbenemies.config.BossConfig
import com.teamuhi.superbenemies.config.DummyConfig
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.init.ModEntities
import com.teamuhi.superbenemies.init.ModItems
import net.minecraft.client.Minecraft
import net.minecraft.client.color.item.ItemColor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.entity.NoopRenderer
import net.minecraft.network.chat.Component
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.client.event.EntityRenderersEvent
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
                            requires = mapOf(BossConfig.FLARE_MISS_CHANCE to BossConfig.DEFENSIVE_FLARES)
                        ),
                        ConfigPage(
                            tab("ac130"), Ac130Config.SPEC, Ac130Config.ALL,
                            requires = mapOf(Ac130Config.FLARE_MISS_CHANCE to Ac130Config.DEFENSIVE_FLARES)
                        ),
                        ConfigPage(tab("dummy"), DummyConfig.SPEC, DummyConfig.ALL)
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
        // The crew is invisible and sits inside the aircraft; it needs a renderer but draws nothing.
        event.registerEntityRenderer(ModEntities.BOSS_PILOT.get()) { context ->
            NoopRenderer<BossCrewEntity>(context)
        }
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
    }

    private const val EGG_BASE_COLOR = 0x4B5320
    private const val EGG_SPOT_COLOR = 0xFF5A00
    private const val AC130_EGG_BASE_COLOR = 0x2E2E2E
    private const val AC130_EGG_SPOT_COLOR = 0xE8E8E8
}
