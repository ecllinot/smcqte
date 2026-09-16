package net.smc.qte.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import net.smc.qte.AutoFishClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudCaptureMixin {

    @Inject(method = "setTitle", at = @At("HEAD"), require = 0)
    private void smcqte$captureTitle(Text title, CallbackInfo ci) {
        AutoFishClient.captureHudTitle(title);
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"), require = 0)
    private void smcqte$captureSubtitle(Text subtitle, CallbackInfo ci) {
        AutoFishClient.captureHudSubtitle(subtitle);
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"), require = 0)
    private void smcqte$captureOverlayMessage(Text message, boolean tinted, CallbackInfo ci) {
        AutoFishClient.captureHudActionbar(message);
    }
}
