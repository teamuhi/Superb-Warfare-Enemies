package com.teamuhi.superbcamera.client

import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft

/**
 * Adds the custom perspectives to the F5 cycle: first person, back, front, custom 1 .. n, first person.
 *
 * Only active while at least one perspective exists; with none, vanilla F5 is left completely alone. The key click is
 * drained here (client tick START, before vanilla's `handleKeybinds` runs) so the vanilla handler never sees it.
 */
object PerspectiveCycle {

    /** Handles pending F5 presses. Does nothing while a screen is open, like vanilla. */
    fun tick(mc: Minecraft) {
        if (CameraStore.perspectives.isEmpty() || mc.screen != null) return
        while (mc.options.keyTogglePerspective.consumeClick()) advance(mc)
    }

    private fun advance(mc: Minecraft) {
        val active = CameraStore.activeIndex
        if (active >= 0) {
            val next = active + 1
            if (next < CameraStore.perspectives.size) {
                CameraStore.activate(next)
            } else {
                CameraStore.deactivate()
                CameraController.switchCameraType(CameraType.FIRST_PERSON)
            }
            mc.levelRenderer.needsUpdate()
            return
        }
        when (mc.options.cameraType) {
            CameraType.FIRST_PERSON -> CameraController.switchCameraType(CameraType.THIRD_PERSON_BACK)
            CameraType.THIRD_PERSON_BACK -> CameraController.switchCameraType(CameraType.THIRD_PERSON_FRONT)
            CameraType.THIRD_PERSON_FRONT -> {
                CameraStore.activate(0)
                mc.levelRenderer.needsUpdate()
            }
        }
    }
}
