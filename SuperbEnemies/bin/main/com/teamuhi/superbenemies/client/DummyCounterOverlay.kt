package com.teamuhi.superbenemies.client

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.DummyConfig
import com.teamuhi.superbenemies.config.SoldierConfig
import com.teamuhi.superbenemies.entity.dummy.PlayerDummyEntity
import com.teamuhi.superbenemies.entity.soldier.DummySoldierEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = [Dist.CLIENT])
object DummyCounterOverlay {

    @SubscribeEvent
    fun render(event: RenderGuiOverlayEvent.Post) {
        if (event.overlay != VanillaGuiOverlay.HOTBAR.type()) return
        val player = Minecraft.getInstance().player ?: return
        val level = Minecraft.getInstance().level ?: return
        if (player.isSpectator || (!DummyConfig.SHOW_COUNTER.get() && !SoldierConfig.SHOW_COUNTER.get())) return

        val entities = level.entitiesForRendering()
        val players = if (DummyConfig.SHOW_COUNTER.get()) entities.count { it is PlayerDummyEntity && it.isAlive } else 0
        val soldiers = if (SoldierConfig.SHOW_COUNTER.get()) entities.count { it is DummySoldierEntity && it.isAlive } else 0
        if (players == 0 && soldiers == 0 && !DummyConfig.SHOW_COUNTER.get() && !SoldierConfig.SHOW_COUNTER.get()) return

        val lines = ArrayList<String>(2)
        if (DummyConfig.SHOW_COUNTER.get()) {
            lines += playerName(players)
        }
        if (SoldierConfig.SHOW_COUNTER.get()) {
            lines += soldierName(soldiers)
        }

        val graphics = event.guiGraphics
        val minecraft = Minecraft.getInstance()
        val font = minecraft.font
        val y = minecraft.window.guiScaledHeight - 49
        for ((index, line) in lines.withIndex()) {
            graphics.drawString(font, Component.literal(line), 4, y - index * 10, 0xFFFFFF, true)
        }
    }

    private fun playerName(count: Int): String = "Player dummies: $count"

    private fun soldierName(count: Int): String = "Dummy soldiers: $count"
}