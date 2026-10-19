package com.teamuhi.superbcamera.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the protected setters of {@link Camera}. The vanilla render path reads {@code camera.getPosition()} after the
 * {@code ViewportEvent.ComputeCameraAngles} event, so setting the position and rotation from that event is enough to
 * take over the view without touching {@code Camera.setup}.
 */
@Mixin(Camera.class)
public interface CameraAccessor {

    @Invoker("setPosition")
    void superbcamera$setPosition(double x, double y, double z);

    @Invoker("setRotation")
    void superbcamera$setRotation(float yaw, float pitch);
}
