// src/main/java/net/smc/qte/mixin/InGameHudAccessor.java
package net.smc.qte.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(InGameHud.class)
public interface InGameHudAccessor {

    @Accessor("title")
    Text getTitle();

    @Accessor("subtitle")
    Text getSubtitle();

    @Accessor("titleRemainTicks")
    int getTitleRemainTicks();
}