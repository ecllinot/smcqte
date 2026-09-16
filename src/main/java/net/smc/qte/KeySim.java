package net.smc.qte;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Hand;

import java.lang.reflect.Method;

public final class KeySim {
    private KeySim() {
    }

    public static void clickRight(MinecraftClient client) {
        if (client == null || client.player == null) return;

        boolean invoked = invokeClientMethod(client, "doItemUse");
        if (!invoked && client.interactionManager != null) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        }

        pulseKey(client.options.useKey);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    public static void clickRightRapid(MinecraftClient client) {
        clickRight(client);
        clickRight(client);
    }

    public static void clickLeft(MinecraftClient client) {
        if (client == null || client.player == null) return;

        boolean invoked = invokeClientMethod(client, "doAttack");
        if (!invoked) {
            pulseKey(client.options.attackKey);
        }

        client.player.swingHand(Hand.MAIN_HAND);
    }

    public static void clickLeftRapid(MinecraftClient client) {
        clickLeft(client);
        clickLeft(client);
    }

    public static void holdLeft(MinecraftClient client) {
        if (client != null) {
            client.options.attackKey.setPressed(true);
        }
    }

    public static void releaseLeft(MinecraftClient client) {
        if (client != null) {
            client.options.attackKey.setPressed(false);
        }
    }

    public static void releaseRight(MinecraftClient client) {
        if (client != null) {
            client.options.useKey.setPressed(false);
        }
    }

    public static void releaseAll(MinecraftClient client) {
        releaseLeft(client);
        releaseRight(client);
    }

    private static boolean invokeClientMethod(MinecraftClient client, String methodName) {
        try {
            Method method = MinecraftClient.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(client);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void pulseKey(KeyBinding keyBinding) {
        keyBinding.setPressed(true);
        keyBinding.setPressed(false);
    }
}
