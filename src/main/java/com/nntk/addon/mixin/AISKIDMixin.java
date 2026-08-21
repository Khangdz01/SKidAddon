package com.nntk.addon.mixin;

import com.nntk.addon.AISKID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.RunArgs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class AISKIDMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onGameLoaded(RunArgs runArgs, CallbackInfo ci) {
        AISKID.LOG.info("Hello from AISKIDMixin!");
    }
}
