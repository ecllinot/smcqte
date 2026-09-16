package net.smc.qte.mixin;

import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import net.smc.qte.AutoFishClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundSystem.class)
public class SoundSystemCaptureMixin {

    @Inject(method = "play", at = @At("HEAD"), require = 0)
    private void smcqte$capturePlay(SoundInstance sound, CallbackInfo ci) {
        if (sound != null) {
            AutoFishClient.captureSound(sound.getId());
        }
    }

    @Inject(method = "playDelayed", at = @At("HEAD"), require = 0)
    private void smcqte$capturePlayDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
        if (sound != null) {
            AutoFishClient.captureSound(sound.getId());
        }
    }
}
