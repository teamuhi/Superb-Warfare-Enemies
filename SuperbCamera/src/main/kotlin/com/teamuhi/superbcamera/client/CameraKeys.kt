package com.teamuhi.superbcamera.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

/** Key bindings of the mod. Registered from [ClientSetup]; rebindable in the vanilla Controls screen. */
object CameraKeys {
    const val CATEGORY = "key.categories.superbcamera"

    /** Opens the live editor on a custom perspective (and closes it again while it is open). Default: backslash. */
    val EDITOR = KeyMapping("key.superbcamera.editor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, CATEGORY)
}
