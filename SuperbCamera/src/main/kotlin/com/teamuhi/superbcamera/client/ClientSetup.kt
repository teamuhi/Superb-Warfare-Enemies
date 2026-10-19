package com.teamuhi.superbcamera.client

import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import thedarkcolour.kotlinforforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.forge.MOD_BUS

/** Client-only wiring, called from the mod constructor on the physical client. */
object ClientSetup {

    fun init() {
        CameraStore.load()
        MOD_BUS.addListener { event: RegisterKeyMappingsEvent -> event.register(CameraKeys.EDITOR) }
        FORGE_BUS.register(CameraController)
    }
}
