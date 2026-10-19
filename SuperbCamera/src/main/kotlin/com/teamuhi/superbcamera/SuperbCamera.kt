package com.teamuhi.superbcamera

import com.teamuhi.superbcamera.client.ClientSetup
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLEnvironment
import org.apache.logging.log4j.LogManager

/**
 * Mod entry point. Superb Camera is client-only: on a dedicated server nothing is registered and no client class is
 * touched. Superb Warfare is an optional dependency (see [com.teamuhi.superbcamera.client.SbwBridge]).
 */
@Mod(SuperbCamera.MOD_ID)
object SuperbCamera {
    const val MOD_ID = "superbcamera"

    @JvmField
    val LOGGER = LogManager.getLogger(MOD_ID)

    init {
        if (FMLEnvironment.dist == Dist.CLIENT) ClientSetup.init()
    }
}
