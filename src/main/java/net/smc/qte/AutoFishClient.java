package net.smc.qte;

import net.smc.qte.mixin.PlayerInventoryAccessor;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.smc.qte.mixin.InGameHudAccessor;
import org.lwjgl.glfw.GLFW;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class AutoFishClient implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private static KeyBinding debugKey;
    private static KeyBinding settingsKey;
    private static KeyBinding toggleLuckPotionKey;
    private static KeyBinding toggleLavaFishingKey;
    private static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of("smc-qte", "general"));

    private boolean isRunning = false;
    private boolean showDebug = false;
    private boolean showSettings = false;

    // ===== 白名单验证 =====
    private static final String WHITELIST_SERVER_URL = "https://whalemc.com/api/whitelist/check";
    private static boolean isWhitelisted = false;
    private static boolean whitelistChecked = false;
    private static boolean whitelistCheckInProgress = false;
    private static String whitelistMessage = "§e正在验证白名单...";
    private static String playerUUID = "";
    private static String playerName = "";
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson gson = new Gson();

    // 设置选项
    private static boolean onlyDaytime = false;
    private static boolean onlyNighttime = false;
    private static boolean onlyRaining = false;
    private static boolean onlyNotRaining = false;
    private static boolean checkDurability = false;
    private static int durabilityThreshold = 10;
    private static boolean autoReplaceRod = false;

    // ===== 岩浆钓鱼设置 =====
    private static boolean lavaFishingMode = false;
    private static int lavaSettleTime = 60;
    private static int lavaRecastDelay = 20;
    private static double lavaBobberRiseThreshold = 0.01;

    // ===== 岩浆钓鱼状态变量 =====
    private double lastBobberY = Double.NaN;
    private boolean bobberSettled = false;
    private int settleTimer = 0;
    private int riseTickCount = 0;
    private boolean lavaWaitingToRecast = false;
    private int lavaRecastTimer = 0;

    // ===== 岩浆钓鱼QTE状态机 =====
    private enum LavaQteState {
        IDLE,
        WAITING_FOR_QTE,
        QTE_ACTIVE
    }
    private LavaQteState lavaQteState = LavaQteState.IDLE;
    private int lavaQteWaitTimer = 0;
    private static int lavaQteWaitTimeout = 60;  // QTE检测超时时间（tick），可在设置中调整

    // 设置界面
    private int selectedOption = 0;
    private static final int TOTAL_OPTIONS = 15;
    private long lastKeyPressTime = 0;

    // counter
    private int qteCount = 0;
    private static int totalQteCount = 0;
    private static int rodsReplacedCount = 0;
    private static int lavaFishCount = 0;

    // ===== QTE tracking =====
    private static final char DIAMOND_CHAR = '◈';
    private static final char DIAMOND_SUIT_CHAR = '♦';
    private static final char LEFT_ARROW_CHAR = '\u2190';
    private static final char RIGHT_ARROW_CHAR = '\u2192';
    private static final char UP_ARROW_CHAR = '\u2191';
    private static final char DOWN_ARROW_CHAR = '\u2193';
    private static final int CLICK_QTE_TICK_INTERVAL = 2;
    private static final int ARROW_QTE_TICK_INTERVAL = 12;
    private static final int JUMP_KEY_HOLD_TICKS = 3;
    private static final int SNEAK_KEY_HOLD_TICKS = 10;
    private static final long HUD_TEXT_FRESH_MS = 3000;
    private static final long SOUND_FRESH_MS = 1800;
    private static final long BALANCE_BAR_FRESH_MS = 1200;
    private static final Pattern MINECRAFT_FORMATTING_PATTERN = Pattern.compile("§[0-9A-FK-ORa-fk-or]");
    private static final char BALANCE_BAR_START_CHAR = '뀌';
    private static final char BALANCE_GREEN_START_CHAR = '뀒';
    private static final char BALANCE_POINTER_CHAR = '뀁';
    private static final int BALANCE_GREEN_WIDTH = 45;
    private static final int BALANCE_TOLERANCE = 5;
    private static final int BALANCE_START_POSITION = 256;
    private static final long BALANCE_FALLBACK_REEL_COOLDOWN_MS = 500;
    private static final String[] BALANCE_FALLBACK_SYMBOLS = {"💰", "♦", "💎", "⭐", "◈", "◆", "◇", "●", "○"};
    private static final int[] BALANCE_WIDTH_OFFSETS = new int[63520];

    private static volatile String capturedTitleText = "";
    private static volatile String capturedSubtitleText = "";
    private static volatile String capturedActionbarText = "";
    private static volatile long capturedTitleAt = 0L;
    private static volatile long capturedSubtitleAt = 0L;
    private static volatile long capturedActionbarAt = 0L;
    private static volatile Identifier lastSoundId = null;
    private static volatile long lastSoundAt = 0L;
    private static volatile BalanceBarSnapshot latestBalanceBar = BalanceBarSnapshot.EMPTY;
    private static volatile long latestBalanceBarAt = 0L;

    private enum QteMode {
        NONE,
        VISUAL_BALANCE_BAR,
        CLICK_SPAM,
        ARROW_SEQUENCE
    }

    private enum ClickQteAction {
        LEFT,
        RIGHT,
        UNKNOWN
    }

    private record BalanceBarSnapshot(int greenStart, int greenEnd, int pointer, boolean valid) {
        private static final BalanceBarSnapshot EMPTY = new BalanceBarSnapshot(0, 0, 0, false);
    }

    static {
        BALANCE_WIDTH_OFFSETS[0xF801] = -3;
        BALANCE_WIDTH_OFFSETS[0xF802] = -4;
        BALANCE_WIDTH_OFFSETS[0xF803] = -6;
        BALANCE_WIDTH_OFFSETS[0xF804] = -10;
        BALANCE_WIDTH_OFFSETS[0xF805] = -18;
        BALANCE_WIDTH_OFFSETS[0xF806] = -34;
        BALANCE_WIDTH_OFFSETS[0xF807] = -66;
        BALANCE_WIDTH_OFFSETS[0xF808] = -130;
        BALANCE_WIDTH_OFFSETS[0xF811] = -1;
        BALANCE_WIDTH_OFFSETS[0xF812] = 1;
        BALANCE_WIDTH_OFFSETS[0xF813] = 3;
        BALANCE_WIDTH_OFFSETS[0xF814] = 7;
        BALANCE_WIDTH_OFFSETS[0xF815] = 15;
        BALANCE_WIDTH_OFFSETS[0xF816] = 31;
        BALANCE_WIDTH_OFFSETS[0xF817] = 63;
        BALANCE_WIDTH_OFFSETS[0xF818] = 127;
    }

    private QteMode activeQteMode = QteMode.NONE;
    private int qteTickCounter = 0;
    private BalanceBarSnapshot activeBalanceBar = BalanceBarSnapshot.EMPTY;
    private boolean balanceFallbackActive = false;
    private boolean balanceFallbackTriggered = false;
    private String balanceFallbackSymbol = "";
    private int balanceFallbackBaseline = 0;
    private long lastBalanceFallbackReelTime = 0L;
    private final Deque<Character> arrowQueue = new ArrayDeque<>();
    private ClickQteAction clickQteAction = ClickQteAction.LEFT;
    private int jumpKeyPressedTicks = 0;
    private int sneakKeyPressedTicks = 0;

    private int lastDiamondCount = 0;
    private int currentDiamondCount = 0;
    private boolean qteActive = false;
    private String lastArrowPrompt = "";

    // ===== 幸运药水配置 =====
    private static boolean autoUseLuckPotion = true;
    private static int luckPotionThreshold = 30;
    private static int luckPotionsUsedCount = 0;

    // ===== 物品切换延迟配置 =====
    private static int itemSwitchDelay = 2;

    // ===== 喝药水状态 =====
    private static boolean isDrinkingPotion = false;
    private static int drinkingTickCounter = 0;
    private static int originalRodSlot = -1;
    private static int potionSourceSlot = -1;
    private static boolean potionWasInHotbar = false;
    private static final int DRINKING_DURATION = 40;
    private static boolean waitingForHookRetract = false;

    // ===== 喝药水阶段状态机 =====
    private enum DrinkingPhase {
        IDLE,
        WAIT_AFTER_SWAP_TO_POTION,
        DRINKING,
        WAIT_AFTER_DRINK,
        WAIT_AFTER_SWAP_BACK
    }
    private static DrinkingPhase drinkingPhase = DrinkingPhase.IDLE;
    private static int phaseTimer = 0;

    // 动作状态机
    private enum ActionState {
        IDLE,
        WAIT_SECOND_CLICK,
        COOLDOWN,
        WAIT_ROD_REPLACE
    }
    private ActionState actionState = ActionState.IDLE;
    private int actionTimer = 0;
    private long lastClickTime = 0;

    // 鱼竿操作冷却
    private long lastRodActionTime = 0;
    private static final long ROD_ACTION_COOLDOWN = 2000;

    // 鱼竿替换冷却
    private long lastRodReplaceTime = 0;
    private static final long ROD_REPLACE_COOLDOWN = 500;
    private static final int ROD_REPLACE_DELAY = 10;

    private static final int SECOND_CLICK_DELAY = 10;
    private static final int COOLDOWN_TICKS = 30;

    // 状态显示
    private volatile String statusText = "等待中...";

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.smc-qte.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                KEY_CATEGORY
        ));

        debugKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.smc-qte.debug",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KEY_CATEGORY
        ));

        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.smc-qte.settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                KEY_CATEGORY
        ));

        toggleLuckPotionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.smc-qte.toggleLuckPotion",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_L,
                KEY_CATEGORY
        ));

        toggleLavaFishingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.smc-qte.toggleLavaFishing",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                KEY_CATEGORY
        ));

        HudRenderCallback.EVENT.register(this::renderDebugOverlay);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // ===== 白名单验证检查 =====
            if (!whitelistChecked && !whitelistCheckInProgress) {
                checkWhitelist(client);
                return;
            }

            if (whitelistCheckInProgress) {
                statusText = whitelistMessage;
                return;
            }

            if (!isWhitelisted) {
                statusText = whitelistMessage;
                // 不在白名单，禁止所有功能
                handleNotWhitelisted(client);
                return;
            }

            // ===== 以下是原有的功能逻辑 =====
            while (toggleKey.wasPressed()) {
                isRunning = !isRunning;
                resetAllState();
                client.player.sendMessage(Text.of("自动QTE: " + (isRunning ? "§a开启" : "§c关闭")), true);
            }

            while (debugKey.wasPressed()) {
                showDebug = !showDebug;
                client.player.sendMessage(Text.of("调试显示: " + (showDebug ? "§a开启" : "§c关闭")), true);
            }

            while (settingsKey.wasPressed()) {
                showSettings = !showSettings;
                client.player.sendMessage(Text.of("设置界面: " + (showSettings ? "§a开启" : "§c关闭")), true);
            }

            while (toggleLuckPotionKey.wasPressed()) {
                autoUseLuckPotion = !autoUseLuckPotion;
                client.player.sendMessage(Text.of("§e[自动钓鱼] §f自动喝药水: " +
                        (autoUseLuckPotion ? "§a开启" : "§c关闭")), true);
            }

            while (toggleLavaFishingKey.wasPressed()) {
                lavaFishingMode = !lavaFishingMode;
                resetLavaBobberTracking();
                client.player.sendMessage(Text.of("§6[自动钓鱼] §f岩浆钓鱼模式: " +
                        (lavaFishingMode ? "§a开启" : "§c关闭")), true);
            }

            if (showSettings) {
                handleSettingsInput(client);
                return;
            }

            // 处理喝药水状态机
            if (drinkingPhase != DrinkingPhase.IDLE) {
                processDrinkingStateMachine(client);
                return;
            }

            if (waitingForHookRetract) {
                if (client.player.fishHook == null) {
                    waitingForHookRetract = false;
                    startDrinkingLuckPotion(client);
                } else {
                    statusText = "§e等待鱼钩收回...";
                }
                return;
            }

            if (!isRunning) {
                statusText = "已关闭 [P开启]";
                return;
            }

            if (!isHoldingFishingRod(client)) {
                statusText = "§c请手持鱼竿";
                return;
            }

            if (actionState == ActionState.WAIT_ROD_REPLACE) {
                actionTimer--;
                statusText = "§e鱼竿替换中: " + actionTimer;
                if (actionTimer <= 0) {
                    actionState = ActionState.IDLE;
                }
                return;
            }

            if (checkDurability && !isDurabilityEnough(client)) {
                boolean rodCast = isRodCast(client);

                if (rodCast) {
                    if (canRodAction()) {
                        doRightClick(client);
                        statusText = "§c耐久不足，收回鱼竿";
                        client.player.sendMessage(Text.of("§c[自动收竿] 耐久度不足"), true);
                    }
                    return;
                }

                if (autoReplaceRod) {
                    int bestSlot = findBestRodSlot(client);
                    if (bestSlot != -1) {
                        if (canRodReplace()) {
                            swapRodFromSlot(client, bestSlot);
                            rodsReplacedCount++;
                            client.player.sendMessage(Text.of("§a[自动换竿] 已替换为槽位 " + (bestSlot + 1) + " 的鱼竿"), true);

                            actionState = ActionState.WAIT_ROD_REPLACE;
                            actionTimer = ROD_REPLACE_DELAY;
                            statusText = "§a正在替换鱼竿...";
                        }
                        return;
                    } else {
                        statusText = "§c耐久不足，背包无可用鱼竿";
                        return;
                    }
                }

                statusText = "§c耐久不足 (" + getRodDurabilityPercent(client) + "% < " + durabilityThreshold + "%)";
                return;
            }

            boolean rodCast = isRodCast(client);
            boolean conditionMet = canFish(client);

            if (!conditionMet) {
                if (rodCast) {
                    if (canRodAction()) {
                        doRightClick(client);
                        statusText = "§e条件不满足，收回鱼竿...";
                        client.player.sendMessage(Text.of("§e[自动收竿] 条件不满足"), true);
                    }
                    return;
                }
                statusText = "§7等待条件满足...";
                return;
            }

            if (shouldDrinkLuckPotion(client)) {
                int potionCount = countDrinkablePotions(client);
                if (potionCount > 0) {
                    if (rodCast) {
                        if (canRodAction()) {
                            doRightClick(client);
                            waitingForHookRetract = true;
                            statusText = "§e收回鱼钩准备喝药水...";
                            client.player.sendMessage(Text.of("§e[自动收竿] 准备喝药水"), true);
                        }
                        return;
                    }
                    startDrinkingLuckPotion(client);
                    return;
                }
            }

            // ===== 根据模式选择处理逻辑 =====
            if (lavaFishingMode) {
                handleLavaFishing(client);
            } else {
                handleNormalFishing(client, rodCast);
            }
        });
    }

    public static void captureHudTitle(Text text) {
        String value = textToString(text);
        if (!value.isEmpty()) {
            capturedTitleText = value;
            capturedTitleAt = System.currentTimeMillis();
        }
    }

    public static void captureHudSubtitle(Text text) {
        String value = textToString(text);
        if (!value.isEmpty()) {
            capturedSubtitleText = value;
            capturedSubtitleAt = System.currentTimeMillis();
            captureBalanceBar(value);
        }
    }

    public static void captureHudActionbar(Text text) {
        String value = textToString(text);
        if (!value.isEmpty()) {
            capturedActionbarText = value;
            capturedActionbarAt = System.currentTimeMillis();
        }
    }

    public static void captureSound(Identifier soundId) {
        if (soundId != null) {
            lastSoundId = soundId;
            lastSoundAt = System.currentTimeMillis();
        }
    }

    private static String textToString(Text text) {
        if (text == null) return "";
        String value = text.getString();
        return value == null ? "" : value;
    }

    private static void captureBalanceBar(String text) {
        BalanceBarSnapshot snapshot = parseBalanceBar(text);
        if (snapshot.valid()) {
            latestBalanceBar = snapshot;
            latestBalanceBarAt = System.currentTimeMillis();
        }
    }

    private static BalanceBarSnapshot parseBalanceBar(String text) {
        if (text == null || text.isEmpty()) {
            return BalanceBarSnapshot.EMPTY;
        }

        int position = 0;
        int greenStart = -1;
        int pointer = -1;
        boolean started = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == BALANCE_BAR_START_CHAR) {
                started = true;
                position = BALANCE_START_POSITION;
            } else if (c == BALANCE_GREEN_START_CHAR) {
                if (started) {
                    greenStart = position;
                    position += BALANCE_GREEN_WIDTH;
                }
            } else if (c == BALANCE_POINTER_CHAR) {
                if (started) {
                    pointer = position;
                }
            } else if (c >= 0xF801 && c < BALANCE_WIDTH_OFFSETS.length) {
                position += BALANCE_WIDTH_OFFSETS[c];
            }
        }

        if (greenStart >= 0 && pointer >= 0) {
            return new BalanceBarSnapshot(greenStart, greenStart + BALANCE_GREEN_WIDTH, pointer, true);
        }
        return BalanceBarSnapshot.EMPTY;
    }

    // ===== 白名单验证相关方法 =====

    /**
     * 获取玩家信息并发起白名单检查
     */
    private void checkWhitelist(MinecraftClient client) {
        if (client.player == null || client.getSession() == null) return;

        whitelistCheckInProgress = true;
        whitelistMessage = "§e正在验证白名单...";

        // 获取玩家UUID和用户名
        playerUUID = client.getSession().getUuidOrNull() != null
                ? client.getSession().getUuidOrNull().toString()
                : "";
        playerName = client.getSession().getUsername();

        // 异步发起白名单验证请求
        CompletableFuture.runAsync(() -> {
            try {
                boolean result = verifyWhitelist(playerUUID, playerName);

                // 在主线程更新状态
                MinecraftClient.getInstance().execute(() -> {
                    isWhitelisted = result;
                    whitelistChecked = true;
                    whitelistCheckInProgress = false;

                    if (isWhitelisted) {
                        whitelistMessage = "§a白名单验证通过";
                        if (client.player != null) {
                            client.player.sendMessage(Text.of("§a[自动钓鱼] 白名单验证通过，欢迎 " + playerName + "！"), false);
                        }
                    } else {
                        whitelistMessage = "§c您不在白名单中，无法使用此Mod";
                        if (client.player != null) {
                            client.player.sendMessage(Text.of("§c[自动钓鱼] 您不在白名单中，请联系管理员"), false);
                        }
                    }
                });
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> {
                    // 验证失败时的处理策略：
                    // 选项1：验证失败则禁止使用（更安全）
                    isWhitelisted = false;
                    whitelistMessage = "§c白名单验证失败: " + e.getMessage();

                    // 选项2：验证失败则允许使用（更宽松，取消下面的注释启用）
                    // isWhitelisted = true;
                    // whitelistMessage = "§e白名单服务器无法连接，已跳过验证";

                    whitelistChecked = true;
                    whitelistCheckInProgress = false;

                    if (client.player != null) {
                        client.player.sendMessage(Text.of("§c[自动钓鱼] " + whitelistMessage), false);
                    }
                });
            }
        });
    }

    /**
     * 向白名单服务器发送验证请求
     * 你需要根据你的服务器API格式修改此方法
     */
    private boolean verifyWhitelist(String uuid, String username) throws Exception {
        // 方式1：GET请求带参数
        String url = WHITELIST_SERVER_URL + "?uuid=" + uuid + "&username=" + username;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "AutoFishMod/1.0")
                .GET()
                .build();

        // 方式2：POST请求带JSON body（如果你的服务器需要POST，取消下面的注释）
        /*
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("uuid", uuid);
        requestBody.addProperty("username", username);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(WHITELIST_SERVER_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "AutoFishMod/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();
        */

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            // 解析响应JSON
            // 假设服务器返回格式为: {"whitelisted": true/false, "message": "..."}
            JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);

            if (jsonResponse.has("whitelisted")) {
                return jsonResponse.get("whitelisted").getAsBoolean();
            }

            // 如果服务器只返回简单的 true/false
            if (jsonResponse.has("result")) {
                return jsonResponse.get("result").getAsBoolean();
            }

            // 如果响应体直接是 "true" 或 "false"
            String body = response.body().trim().toLowerCase();
            return body.equals("true") || body.equals("1");
        }

        // 非200状态码视为验证失败
        throw new Exception("服务器返回错误: " + response.statusCode());
    }

    /**
     * 处理不在白名单的情况
     */
    private void handleNotWhitelisted(MinecraftClient client) {
        // 清空所有按键事件，防止功能被使用
        while (toggleKey.wasPressed()) {
            client.player.sendMessage(Text.of("§c[自动钓鱼] 您不在白名单中，无法使用此功能"), true);
        }
        while (debugKey.wasPressed()) {
            client.player.sendMessage(Text.of("§c[自动钓鱼] 您不在白名单中，无法使用此功能"), true);
        }
        while (settingsKey.wasPressed()) {
            client.player.sendMessage(Text.of("§c[自动钓鱼] 您不在白名单中，无法使用此功能"), true);
        }
        while (toggleLuckPotionKey.wasPressed()) {
            client.player.sendMessage(Text.of("§c[自动钓鱼] 您不在白名单中，无法使用此功能"), true);
        }
        while (toggleLavaFishingKey.wasPressed()) {
            client.player.sendMessage(Text.of("§c[自动钓鱼] 您不在白名单中，无法使用此功能"), true);
        }
    }

    /**
     * 重新验证白名单（可用于手动刷新）
     */
    private void reCheckWhitelist(MinecraftClient client) {
        whitelistChecked = false;
        whitelistCheckInProgress = false;
        isWhitelisted = false;
        checkWhitelist(client);
    }

    // ===== 普通钓鱼处理 =====
    private void handleNormalFishing(MinecraftClient client, boolean rodCast) {
        if (actionState == ActionState.WAIT_SECOND_CLICK) {
            actionTimer--;
            statusText = "§e等待出杆: " + actionTimer;
            if (actionTimer <= 0) {
                doRightClick(client);
                client.player.sendMessage(Text.of("§a[出杆!]"), true);

                actionState = ActionState.COOLDOWN;
                actionTimer = COOLDOWN_TICKS;
                statusText = "§a已出杆!";
            }
            return;
        }

        if (actionState == ActionState.COOLDOWN) {
            actionTimer--;
            statusText = "§7冷却中: " + actionTimer;
            if (actionTimer <= 0) {
                actionState = ActionState.IDLE;
            }
            return;
        }

        if (!rodCast) {
            if (canRodAction()) {
                doRightClick(client);
                statusText = "§a抛出鱼竿...";
                client.player.sendMessage(Text.of("§a[自动抛竿]"), true);
            }
            return;
        }

        processTitle(client);
    }

    // ===== 岩浆钓鱼处理 =====
    private void handleLavaFishing(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        if (actionState == ActionState.WAIT_SECOND_CLICK) {
            actionTimer--;
            statusText = "§e等待出杆: " + actionTimer;
            if (actionTimer <= 0) {
                doRightClick(client);
                client.player.sendMessage(Text.of("§a[出杆!]"), true);

                actionState = ActionState.COOLDOWN;
                actionTimer = COOLDOWN_TICKS;
                statusText = "§a已出杆";
                resetLavaBobberTracking();
            }
            return;
        }

        if (actionState == ActionState.COOLDOWN) {
            actionTimer--;
            statusText = "§7冷却中: " + actionTimer;
            if (actionTimer <= 0) {
                actionState = ActionState.IDLE;
            }
            return;
        }

        if (lavaWaitingToRecast) {
            lavaRecastTimer--;
            double remainingSeconds = lavaRecastTimer / 20.0;
            statusText = String.format("§e等待重新抛竿... %.1fs", remainingSeconds);

            if (lavaRecastTimer <= 0) {
                if (canRodAction()) {
                    doRightClick(client);
                    resetLavaBobberTracking();
                    lavaWaitingToRecast = false;
                    statusText = "§a重新抛竿";
                    client.player.sendMessage(Text.of("§a[岩浆钓鱼] 重新抛竿"), true);
                }
            }
            return;
        }

        String titleText = getCurrentTitleText(client);
        int diamondCount = countDiamonds(titleText);
        lastDiamondCount = currentDiamondCount;
        currentDiamondCount = diamondCount;

        boolean rodCast = isRodCast(client);

        switch (lavaQteState) {
            case IDLE:
                if (!rodCast) {
                    if (canRodAction()) {
                        doRightClick(client);
                        resetLavaBobberTracking();
                        statusText = "§a抛出鱼竿...";
                        client.player.sendMessage(Text.of("§6[岩浆钓鱼] 抛竿"), true);
                    }
                    return;
                }

                if (detectLavaFishingBite(client)) {
                    doRightClick(client);
                    lavaQteState = LavaQteState.WAITING_FOR_QTE;
                    lavaQteWaitTimer = 0;
                    resetQteModeState();
                    client.player.sendMessage(Text.of("§6[岩浆钓鱼] 检测到咬钩，收杆等待QTE..."), true);
                    statusText = "§6收杆！等待QTE出现...";
                }
                break;

            case WAITING_FOR_QTE:
                lavaQteWaitTimer++;

                if (isAnyQtePrompt(titleText)) {
                    updateQteTrackingFromText(client, titleText);
                    lavaQteState = LavaQteState.QTE_ACTIVE;
                    statusText = "§eQTE出现!";
                    client.player.sendMessage(Text.of("§e[岩浆钓鱼] QTE出现"), true);
                } else if (lavaQteWaitTimer >= lavaQteWaitTimeout) {
                    lavaQteState = LavaQteState.IDLE;
                    lavaWaitingToRecast = true;
                    lavaRecastTimer = lavaRecastDelay;
                    resetLavaBobberTracking();
                    resetQteModeState();
                    statusText = "§cQTE超时，准备重新抛竿...";
                    client.player.sendMessage(Text.of("§c[岩浆钓鱼] QTE超时，重新抛竿"), true);
                } else {
                    double remainingSeconds = (lavaQteWaitTimeout - lavaQteWaitTimer) / 20.0;
                    statusText = String.format("§6等待QTE出现... %.1fs", remainingSeconds);
                }
                break;

            case QTE_ACTIVE:
                if (titleText == null || titleText.isEmpty()) {
                    lavaQteState = LavaQteState.IDLE;
                    resetQteModeState();
                    lavaWaitingToRecast = true;
                    lavaRecastTimer = lavaRecastDelay;
                    resetLavaBobberTracking();
                    statusText = "§eQTE结束，准备重新抛竿...";
                    break;
                }

                updateQteTrackingFromText(client, titleText);
                if (didQteSucceed(client, titleText)) {
                    qteCount++;
                    totalQteCount++;
                    lavaFishCount++;

                    lavaQteState = LavaQteState.IDLE;
                    resetQteModeState();
                    resetLavaBobberTracking();

                    actionState = ActionState.WAIT_SECOND_CLICK;
                    actionTimer = SECOND_CLICK_DELAY;
                    statusText = "§a收杆成功！等待出杆...";
                } else if (didQteFail(titleText)) {
                    lavaQteState = LavaQteState.IDLE;
                    resetQteModeState();
                    lavaWaitingToRecast = true;
                    lavaRecastTimer = lavaRecastDelay;
                    resetLavaBobberTracking();
                    statusText = "§cQTE失败，准备重新抛竿...";
                }
                break;
        }
    }

    private boolean detectLavaFishingBite(MinecraftClient client) {
        if (client.player == null) return false;

        FishingBobberEntity bobber = client.player.fishHook;

        if (bobber == null) {
            resetLavaBobberTracking();
            return false;
        }

        boolean inLava = bobber.isInLava();
        if (!inLava) {
            if (!bobberSettled) {
                statusText = "§7浮标未在岩浆中...";
            }
        }

        double currentY = bobber.getY();

        if (Double.isNaN(lastBobberY)) {
            lastBobberY = currentY;
            settleTimer = 0;
            bobberSettled = false;
            return false;
        }

        double deltaY = currentY - lastBobberY;

        if (!bobberSettled) {
            settleTimer++;
            int remainingTicks = Math.max(0, lavaSettleTime - settleTimer);
            double remainingSeconds = remainingTicks / 20.0;
            statusText = String.format("§e浮标稳定中... %.1fs", remainingSeconds);

            if (settleTimer >= lavaSettleTime) {
                if (Math.abs(deltaY) < 0.01) {
                    bobberSettled = true;
                    statusText = "§6浮标已稳定，等待咬钩...";
                    client.player.sendMessage(Text.of("§6[岩浆钓鱼] 浮标已稳定，等待咬钩..."), true);
                } else {
                    settleTimer = lavaSettleTime - 10;
                }
            }
            lastBobberY = currentY;
            return false;
        }

        if (hasFreshFishingSound()) {
            lastSoundAt = 0L;
            statusText = "§a检测到咬钩音效！";
            return true;
        }

        if (deltaY > lavaBobberRiseThreshold) {
            riseTickCount++;
            statusText = "§e检测到浮动: " + String.format("%.4f", deltaY) + " (计数: " + riseTickCount + ")";
        } else {
            riseTickCount = Math.max(0, riseTickCount - 1);
            if (riseTickCount == 0) {
                statusText = "§6等待咬钩... Y=" + String.format("%.2f", currentY);
            }
        }

        lastBobberY = currentY;

        if (riseTickCount >= 2) {
            statusText = "§a检测到咬钩！";
            return true;
        }

        return false;
    }

    // ===== 重置岩浆钓鱼追踪状态 =====
    private void resetLavaBobberTracking() {
        lastBobberY = Double.NaN;
        bobberSettled = false;
        settleTimer = 0;
        riseTickCount = 0;
    }

    // ===== 重置岩浆QTE状态 =====
    private void resetLavaQteState() {
        lavaQteState = LavaQteState.IDLE;
        lavaQteWaitTimer = 0;
        resetQteModeState();
    }

    private void switchToSlot(MinecraftClient client, int slot) {
        if (client.player == null || slot < 0 || slot > 8) return;

        PlayerInventoryAccessor accessor = (PlayerInventoryAccessor) client.player.getInventory();
        accessor.setSelectedSlot(slot);

        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private int getSelectedSlot(MinecraftClient client) {
        if (client.player == null) return 0;
        return client.player.getInventory().getSelectedSlot();
    }

    // ===== 钻石检测核心逻辑（普通模式使用） =====
    private void processTitle(MinecraftClient client) {
        String titleText = getCurrentTitleText(client);

        if (titleText == null || titleText.isEmpty()) {
            if (qteActive) {
                statusText = "§7QTE结束，等待下一次...";
                resetQteModeState();
            } else {
                statusText = "§7等待QTE... (钓鱼中)";
            }
            return;
        }

        updateQteTrackingFromText(client, titleText);

        if (didQteSucceed(client, titleText)) {
            qteCount++;
            totalQteCount++;

            resetQteModeState();
            actionState = ActionState.WAIT_SECOND_CLICK;
            actionTimer = SECOND_CLICK_DELAY;
            statusText = "§e等待出杆...";
        } else if (didQteFail(titleText)) {
            resetQteModeState();
            statusText = "§cQTE失败，等待下一次...";
        }
    }

    private void updateQteTrackingFromText(MinecraftClient client, String titleText) {
        if (titleText == null) return;

        String normalized = normalizeQteText(mergeFreshHudText(titleText));
        if (normalized.isEmpty()) return;

        lastDiamondCount = currentDiamondCount;
        currentDiamondCount = countDiamonds(normalized);

        if (!qteActive) {
            beginQteMode(client, normalized);
        }

        if (!qteActive) {
            return;
        }

        switch (activeQteMode) {
            case VISUAL_BALANCE_BAR:
                handleVisualBalanceBarQte(client, normalized);
                break;
            case CLICK_SPAM:
                handleClickSpamQte(client);
                break;
            case ARROW_SEQUENCE:
                handleArrowSequenceQte(client);
                break;
            case NONE:
            default:
                break;
        }
    }

    private void beginQteMode(MinecraftClient client, String normalizedText) {
        if (hasArrowPrompt(normalizedText)) {
            arrowQueue.clear();
            clickQteAction = ClickQteAction.LEFT;
            lastArrowPrompt = normalizedText;
            for (int i = 0; i < normalizedText.length(); i++) {
                char c = normalizedText.charAt(i);
                if (c == LEFT_ARROW_CHAR || c == RIGHT_ARROW_CHAR
                        || c == UP_ARROW_CHAR || c == DOWN_ARROW_CHAR) {
                    arrowQueue.add(c);
                }
            }
            if (!arrowQueue.isEmpty()) {
                activeQteMode = QteMode.ARROW_SEQUENCE;
                qteActive = true;
                qteTickCounter = ARROW_QTE_TICK_INTERVAL;
                statusText = "§e方向QTE: " + arrowQueue.size() + "步";
                return;
            }
        }

        if (isVisualBalanceStartPrompt(normalizedText) || (hasFreshBalanceBar() && isVisualBalanceStartPrompt(getFreshHudText(capturedTitleText, capturedTitleAt)))) {
            activeQteMode = QteMode.VISUAL_BALANCE_BAR;
            qteActive = true;
            qteTickCounter = 0;
            activeBalanceBar = getFreshBalanceBar();
            startBalanceCharacterFallbackIfPresent(normalizedText);
            doLeftClick(client);
            lastClickTime = System.currentTimeMillis();
            statusText = "§e平衡条QTE: 点击左键启动";
            return;
        }

        if (startBalanceCharacterFallbackIfPresent(normalizedText)) {
            activeQteMode = QteMode.VISUAL_BALANCE_BAR;
            qteActive = true;
            qteTickCounter = 0;
            statusText = "§e平衡条QTE(字符): 基准 '" + balanceFallbackSymbol + "' ×" + balanceFallbackBaseline;
            return;
        }

        if (isClickQtePrompt(normalizedText)) {
            activeQteMode = QteMode.CLICK_SPAM;
            qteActive = true;
            qteTickCounter = 0;
            updateClickQteActionFromPrompt(client);
            statusText = clickQteAction == ClickQteAction.RIGHT ? "§e连点QTE(右键)..." : "§e连点QTE(左键)...";
        }
    }

    private void handleVisualBalanceBarQte(MinecraftClient client, String normalizedText) {
        if (updateBalanceCharacterFallback(client, normalizedText)) {
            return;
        }

        BalanceBarSnapshot snapshot = getFreshBalanceBar();
        if (!snapshot.valid()) {
            long now = System.currentTimeMillis();
            if (now - lastClickTime >= 150) {
                KeySim.clickLeftRapid(client);
                lastClickTime = now;
                statusText = "§e平衡条QTE: 等待subtitle定位，盲点左键";
            }
            return;
        }

        activeBalanceBar = snapshot;
        long now = System.currentTimeMillis();
        if (now - lastClickTime < 80) {
            return;
        }

        if (snapshot.pointer() < snapshot.greenStart() - BALANCE_TOLERANCE) {
            KeySim.holdLeft(client);
            doLeftClick(client);
            lastClickTime = now;
            statusText = "§e平衡条QTE: 指针" + snapshot.pointer() + " 在绿区" + snapshot.greenStart() + "-" + snapshot.greenEnd() + "左侧，点击左键右移";
        } else {
            releaseAttackKey(client);
            if (snapshot.pointer() > snapshot.greenEnd() + BALANCE_TOLERANCE) {
                statusText = "§e平衡条QTE: 指针" + snapshot.pointer() + " 在绿区右侧，松开左移";
            } else {
                statusText = "§a平衡条QTE: 指针在绿区内，保持";
            }
        }
    }

    private boolean updateBalanceCharacterFallback(MinecraftClient client, String normalizedText) {
        if (!balanceFallbackActive) {
            return false;
        }

        int currentCount = countSymbol(normalizedText, balanceFallbackSymbol);
        if (currentCount <= 0) {
            return false;
        }

        if (currentCount > balanceFallbackBaseline) {
            long now = System.currentTimeMillis();
            if (now - lastBalanceFallbackReelTime >= BALANCE_FALLBACK_REEL_COOLDOWN_MS) {
                KeySim.clickRight(client);
                lastBalanceFallbackReelTime = now;
                balanceFallbackTriggered = true;
                statusText = "§a平衡条QTE(字符): '" + balanceFallbackSymbol + "' ×"
                        + balanceFallbackBaseline + " -> " + currentCount + "，收杆";
            }
            return true;
        }

        if (currentCount < balanceFallbackBaseline) {
            balanceFallbackBaseline = currentCount;
            statusText = "§e平衡条QTE(字符): 更新基准 '" + balanceFallbackSymbol + "' ×" + balanceFallbackBaseline;
            return true;
        }

        return false;
    }

    private void handleClickSpamQte(MinecraftClient client) {
        updateClickQteActionFromPrompt(client);
        qteTickCounter++;
        if (qteTickCounter >= CLICK_QTE_TICK_INTERVAL) {
            qteTickCounter = 0;
            if (clickQteAction == ClickQteAction.RIGHT) {
                KeySim.clickRightRapid(client);
            } else {
                KeySim.clickLeftRapid(client);
            }
        }
    }

    private void handleArrowSequenceQte(MinecraftClient client) {
        // 每 tick 递减跳跃/蹲下按键的持续时间，到时自动释放
        if (jumpKeyPressedTicks > 0) {
            jumpKeyPressedTicks--;
            if (jumpKeyPressedTicks == 0) {
                client.options.jumpKey.setPressed(false);
            }
        }
        if (sneakKeyPressedTicks > 0) {
            sneakKeyPressedTicks--;
            if (sneakKeyPressedTicks == 0) {
                client.options.sneakKey.setPressed(false);
            }
        }

        qteTickCounter++;
        if (qteTickCounter < ARROW_QTE_TICK_INTERVAL) {
            return;
        }
        qteTickCounter = 0;

        if (arrowQueue.isEmpty()) {
            return;
        }

        char arrow = arrowQueue.poll();
        if (arrow == LEFT_ARROW_CHAR) {
            doLeftClick(client);
        } else if (arrow == RIGHT_ARROW_CHAR) {
            doRightClick(client);
        } else if (arrow == UP_ARROW_CHAR) {
            doJump(client);
        } else if (arrow == DOWN_ARROW_CHAR) {
            doSneak(client);
        }

        statusText = "§e方向QTE剩余: " + arrowQueue.size();
    }

    private void doJump(MinecraftClient client) {
        if (client == null || client.player == null) return;
        client.options.jumpKey.setPressed(true);
        jumpKeyPressedTicks = JUMP_KEY_HOLD_TICKS;
    }

    private void doSneak(MinecraftClient client) {
        if (client == null || client.player == null) return;
        client.options.sneakKey.setPressed(true);
        sneakKeyPressedTicks = SNEAK_KEY_HOLD_TICKS;
    }

    private void releaseMovementKeys(MinecraftClient client) {
        if (client == null) return;
        if (jumpKeyPressedTicks > 0) {
            client.options.jumpKey.setPressed(false);
            jumpKeyPressedTicks = 0;
        }
        if (sneakKeyPressedTicks > 0) {
            client.options.sneakKey.setPressed(false);
            sneakKeyPressedTicks = 0;
        }
    }

    private void releaseAttackKey(MinecraftClient client) {
        KeySim.releaseLeft(client);
    }

    private boolean startBalanceCharacterFallbackIfPresent(String normalizedText) {
        String fallbackSymbol = findBalanceFallbackSymbol(normalizedText);
        if (fallbackSymbol.isEmpty()) {
            return false;
        }

        balanceFallbackActive = true;
        balanceFallbackTriggered = false;
        balanceFallbackSymbol = fallbackSymbol;
        balanceFallbackBaseline = countSymbol(normalizedText, fallbackSymbol);
        lastBalanceFallbackReelTime = 0L;
        return true;
    }

    private boolean didQteSucceed(MinecraftClient client, String text) {
        String normalized = normalizeQteText(mergeFreshHudText(text));
        if (normalized.isEmpty()) {
            return false;
        }

        switch (activeQteMode) {
            case VISUAL_BALANCE_BAR:
                return balanceFallbackTriggered || isQteSuccessText(normalized);
            case CLICK_SPAM:
                if (isQteSuccessText(normalized)) {
                    doRightClick(client);
                    return true;
                }
                return false;
            case ARROW_SEQUENCE:
                if (arrowQueue.isEmpty() && qteActive) {
                    doRightClick(client);
                    return true;
                }
                return false;
            case NONE:
            default:
                return false;
        }
    }

    private boolean didQteFail(String text) {
        if (!qteActive) {
            return false;
        }
        return isQteFailText(normalizeQteText(mergeFreshHudText(text)));
    }

    private boolean isAnyQtePrompt(String text) {
        if (text == null || text.isEmpty()) return false;
        String normalized = normalizeQteText(text);
        return hasArrowPrompt(normalized)
                || isVisualBalanceStartPrompt(normalized)
                || (hasFreshBalanceBar() && isVisualBalanceStartPrompt(getFreshHudText(capturedTitleText, capturedTitleAt)))
                || isClickQtePrompt(normalized);
    }

    private String normalizeQteText(String text) {
        if (text == null) return "";
        String normalized = MINECRAFT_FORMATTING_PATTERN.matcher(text).replaceAll("");
        normalized = normalized
                .replace('（', '(')
                .replace('）', ')')
                .replace('，', ',')
                .replace('：', ':')
                .replace('＋', '+')
                .replace('－', '-')
                .replace('　', ' ');
        return normalized.replaceAll("\\s+", "");
    }

    private boolean hasArrowPrompt(String text) {
        return text.indexOf(LEFT_ARROW_CHAR) >= 0 || text.indexOf(RIGHT_ARROW_CHAR) >= 0
                || text.indexOf(UP_ARROW_CHAR) >= 0 || text.indexOf(DOWN_ARROW_CHAR) >= 0;
    }

    private boolean isVisualBalanceStartPrompt(String text) {
        if (text == null || text.isEmpty()) return false;
        String normalized = normalizeQteText(text);
        String lower = normalized.toLowerCase();
        return (normalized.contains("点击") || normalized.contains("點擊") || lower.contains("click"))
                && (normalized.contains("开始") || normalized.contains("開始") || lower.contains("start"));
    }

    private String findBalanceFallbackSymbol(String text) {
        if (text == null || text.isEmpty()) return "";

        String bestSymbol = "";
        int bestCount = 0;
        for (String symbol : BALANCE_FALLBACK_SYMBOLS) {
            int count = countSymbol(text, symbol);
            if (count > bestCount) {
                bestSymbol = symbol;
                bestCount = count;
            }
        }
        return bestCount > 0 ? bestSymbol : "";
    }

    private int countSymbol(String text, String symbol) {
        if (text == null || text.isEmpty() || symbol == null || symbol.isEmpty()) return 0;

        int count = 0;
        int fromIndex = 0;
        while (fromIndex < text.length()) {
            int index = text.indexOf(symbol, fromIndex);
            if (index < 0) break;
            count++;
            fromIndex = index + symbol.length();
        }
        return count;
    }

    private boolean isClickQtePrompt(String text) {
        String normalized = normalizeQteText(text).toLowerCase();
        if (isVisualBalanceStartPrompt(normalized)) return false;
        return normalized.contains("需要点击次数")
                || normalized.contains("需点击次数")
                || normalized.contains("点击次数")
                || normalized.contains("連點")
                || normalized.contains("连点")
                || normalized.contains("點擊")
                || (normalized.contains("点击") && normalized.contains("次"))
                || (normalized.contains("click") && (normalized.contains("times") || normalized.contains("time")))
                || normalized.contains("spamclick")
                || normalized.contains("rapidclick");
    }

    private void updateClickQteActionFromPrompt(MinecraftClient client) {
        ClickQteAction action = detectClickQteAction(getFreshHudText(capturedSubtitleText, capturedSubtitleAt));
        if (action == ClickQteAction.UNKNOWN) {
            action = detectClickQteAction(getFreshHudText(capturedActionbarText, capturedActionbarAt));
        }
        if (action == ClickQteAction.UNKNOWN) {
            action = detectClickQteAction(getCurrentSubtitleText(client));
        }
        if (action == ClickQteAction.UNKNOWN) {
            action = detectClickQteAction(getCurrentTitleText(client));
        }
        if (action != ClickQteAction.UNKNOWN) {
            clickQteAction = action;
        }
    }

    private ClickQteAction detectClickQteAction(String text) {
        String normalized = normalizeQteText(text).toLowerCase();
        if (normalized.isEmpty()) {
            return ClickQteAction.UNKNOWN;
        }
        if (normalized.contains("右") || normalized.contains("右键") || normalized.contains("右鍵")
                || normalized.contains("使用") || normalized.contains("收杆") || normalized.contains("收竿")
                || normalized.contains("use") || normalized.contains("right") || normalized.contains("mouse2")
                || normalized.contains("button2") || normalized.contains("rmb")) {
            return ClickQteAction.RIGHT;
        }
        if (normalized.contains("左") || normalized.contains("左键") || normalized.contains("左鍵")
                || normalized.contains("攻击") || normalized.contains("攻擊") || normalized.contains("挥杆")
                || normalized.contains("attack") || normalized.contains("left") || normalized.contains("mouse1")
                || normalized.contains("button1") || normalized.contains("lmb")) {
            return ClickQteAction.LEFT;
        }
        return ClickQteAction.UNKNOWN;
    }

    private boolean isQteSuccessText(String text) {
        String normalized = normalizeQteText(text).toLowerCase();
        return normalized.contains("成功")
                || normalized.contains("完成")
                || normalized.contains("收杆")
                || normalized.contains("收竿")
                || normalized.contains("钓上")
                || normalized.contains("釣上")
                || normalized.contains("钓到")
                || normalized.contains("釣到")
                || normalized.contains("success")
                || normalized.contains("complete");
    }

    private boolean isQteFailText(String text) {
        String normalized = normalizeQteText(text).toLowerCase();
        return normalized.contains("再试")
                || normalized.contains("再試")
                || normalized.contains("遗憾")
                || normalized.contains("遺憾")
                || normalized.contains("失败")
                || normalized.contains("失敗")
                || normalized.contains("超时")
                || normalized.contains("超時")
                || normalized.contains("timeout")
                || normalized.contains("failed")
                || normalized.contains("fail");
    }
    private void resetQteModeState() {
        lastDiamondCount = 0;
        currentDiamondCount = 0;
        qteActive = false;
        activeQteMode = QteMode.NONE;
        qteTickCounter = 0;
        arrowQueue.clear();
        clickQteAction = ClickQteAction.LEFT;
        lastArrowPrompt = "";
        activeBalanceBar = BalanceBarSnapshot.EMPTY;
        balanceFallbackActive = false;
        balanceFallbackTriggered = false;
        balanceFallbackSymbol = "";
        balanceFallbackBaseline = 0;
        lastBalanceFallbackReelTime = 0L;
        releaseAttackKey(MinecraftClient.getInstance());
        releaseMovementKeys(MinecraftClient.getInstance());
    }

    private String getCurrentSubtitleText(MinecraftClient client) {
        String captured = getFreshHudText(capturedSubtitleText, capturedSubtitleAt);
        if (!captured.isEmpty()) return captured;
        if (client.inGameHud == null) return null;

        try {
            InGameHudAccessor accessor = (InGameHudAccessor) client.inGameHud;
            Text subtitle = accessor.getSubtitle();
            if (subtitle != null) {
                String subtitleStr = subtitle.getString();
                if (subtitleStr != null && !subtitleStr.isEmpty()) {
                    return subtitleStr;
                }
            }
        } catch (Exception e) {
            // 忽略
        }

        return null;
    }

    private String getCurrentTitleText(MinecraftClient client) {
        String captured = getBestFreshHudText();
        if (!captured.isEmpty()) return captured;
        if (client.inGameHud == null) return null;

        try {
            InGameHudAccessor accessor = (InGameHudAccessor) client.inGameHud;

            Text title = accessor.getTitle();
            if (title != null) {
                String titleStr = title.getString();
                if (titleStr != null && !titleStr.isEmpty()) {
                    return titleStr;
                }
            }

            Text subtitle = accessor.getSubtitle();
            if (subtitle != null) {
                String subtitleStr = subtitle.getString();
                if (subtitleStr != null && !subtitleStr.isEmpty()) {
                    return subtitleStr;
                }
            }
        } catch (Exception e) {
            // 忽略
        }

        return null;
    }

    private String getBestFreshHudText() {
        String title = getFreshHudText(capturedTitleText, capturedTitleAt);
        String actionbar = getFreshHudText(capturedActionbarText, capturedActionbarAt);
        String subtitle = getFreshHudText(capturedSubtitleText, capturedSubtitleAt);

        if (!title.isEmpty() && isAnyQtePrompt(title)) return title;
        if (!actionbar.isEmpty() && isAnyQtePrompt(actionbar)) return actionbar;
        if (!subtitle.isEmpty() && isAnyQtePrompt(subtitle)) return subtitle;
        if (!title.isEmpty()) return title;
        if (!actionbar.isEmpty()) return actionbar;
        return subtitle;
    }

    private String mergeFreshHudText(String text) {
        StringBuilder builder = new StringBuilder();
        appendFreshHudText(builder, text);
        appendFreshHudText(builder, getFreshHudText(capturedTitleText, capturedTitleAt));
        appendFreshHudText(builder, getFreshHudText(capturedSubtitleText, capturedSubtitleAt));
        appendFreshHudText(builder, getFreshHudText(capturedActionbarText, capturedActionbarAt));
        return builder.toString();
    }

    private void appendFreshHudText(StringBuilder builder, String text) {
        if (text == null || text.isEmpty()) return;
        if (builder.indexOf(text) >= 0) return;
        if (builder.length() > 0) builder.append(' ');
        builder.append(text);
    }

    private String getFreshHudText(String text, long capturedAt) {
        if (text == null || text.isEmpty()) return "";
        return System.currentTimeMillis() - capturedAt <= HUD_TEXT_FRESH_MS ? text : "";
    }

    private BalanceBarSnapshot getFreshBalanceBar() {
        return hasFreshBalanceBar() ? latestBalanceBar : BalanceBarSnapshot.EMPTY;
    }

    private boolean hasFreshBalanceBar() {
        return latestBalanceBar.valid()
                && System.currentTimeMillis() - latestBalanceBarAt <= BALANCE_BAR_FRESH_MS;
    }

    private boolean hasFreshFishingSound() {
        if (lastSoundId == null) return false;
        if (System.currentTimeMillis() - lastSoundAt > SOUND_FRESH_MS) return false;

        String sound = lastSoundId.toString().toLowerCase();
        return sound.contains("entity.fishing_bobber")
                || sound.contains("entity.generic.splash")
                || sound.contains("item.trident")
                || sound.contains("entity.lightning_bolt")
                || sound.contains("block.lava");
    }
    private int countDiamonds(String text) {
        if (text == null) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == DIAMOND_CHAR || c == '◈' || c == DIAMOND_SUIT_CHAR) {
                count++;
            }
        }
        return count;
    }
    private void resetDiamondState() {
        resetQteModeState();
    }

    // ===== 鱼竿状态检测 =====
    private boolean isHoldingFishingRod(MinecraftClient client) {
        if (client.player == null) return false;
        ItemStack mainHand = client.player.getMainHandStack();
        ItemStack offHand = client.player.getOffHandStack();
        return mainHand.getItem() instanceof FishingRodItem ||
                offHand.getItem() instanceof FishingRodItem;
    }

    private ItemStack getFishingRodStack(MinecraftClient client) {
        if (client.player == null) return ItemStack.EMPTY;
        ItemStack mainHand = client.player.getMainHandStack();
        if (mainHand.getItem() instanceof FishingRodItem) {
            return mainHand;
        }
        ItemStack offHand = client.player.getOffHandStack();
        if (offHand.getItem() instanceof FishingRodItem) {
            return offHand;
        }
        return ItemStack.EMPTY;
    }

    private int getHeldRodSlot(MinecraftClient client) {
        if (client.player == null) return -1;
        ItemStack mainHand = client.player.getMainHandStack();
        if (mainHand.getItem() instanceof FishingRodItem) {
            return client.player.getInventory().getSelectedSlot();
        }
        return -1;
    }

    private boolean isDurabilityEnough(MinecraftClient client) {
        int percent = getRodDurabilityPercent(client);
        return percent >= durabilityThreshold;
    }

    private int getRodDurabilityPercent(MinecraftClient client) {
        ItemStack rod = getFishingRodStack(client);
        if (rod.isEmpty() || rod.getMaxDamage() == 0) return 100;

        int maxDamage = rod.getMaxDamage();
        int currentDamage = rod.getDamage();
        int remaining = maxDamage - currentDamage;

        return (int) ((remaining * 100.0) / maxDamage);
    }

    private int getRodRemainingDurability(MinecraftClient client) {
        ItemStack rod = getFishingRodStack(client);
        if (rod.isEmpty()) return 0;
        return rod.getMaxDamage() - rod.getDamage();
    }

    private int getItemDurabilityPercent(ItemStack stack) {
        if (stack.isEmpty() || stack.getMaxDamage() == 0) return 100;
        int maxDamage = stack.getMaxDamage();
        int currentDamage = stack.getDamage();
        int remaining = maxDamage - currentDamage;
        return (int) ((remaining * 100.0) / maxDamage);
    }

    // ===== 鱼竿替换逻辑 =====
    private int findBestRodSlot(MinecraftClient client) {
        if (client.player == null) return -1;

        int bestSlot = -1;
        int bestDurability = durabilityThreshold;
        int currentHeldSlot = getHeldRodSlot(client);

        for (int i = 0; i < 36; i++) {
            if (i == currentHeldSlot) continue;

            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.getItem() instanceof FishingRodItem) {
                int durabilityPercent = getItemDurabilityPercent(stack);
                if (durabilityPercent > bestDurability) {
                    bestDurability = durabilityPercent;
                    bestSlot = i;
                }
            }
        }

        return bestSlot;
    }

    private int countAvailableRods(MinecraftClient client) {
        if (client.player == null) return 0;

        int count = 0;
        int currentHeldSlot = getHeldRodSlot(client);

        for (int i = 0; i < 36; i++) {
            if (i == currentHeldSlot) continue;

            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.getItem() instanceof FishingRodItem) {
                if (getItemDurabilityPercent(stack) >= durabilityThreshold) {
                    count++;
                }
            }
        }
        return count;
    }

    private void swapRodFromSlot(MinecraftClient client, int sourceSlot) {
        if (client.player == null || client.interactionManager == null) return;

        int currentSlot = getHeldRodSlot(client);
        if (currentSlot == -1) return;

        int screenSourceSlot;
        if (sourceSlot < 9) {
            screenSourceSlot = sourceSlot + 36;
        } else {
            screenSourceSlot = sourceSlot;
        }

        int screenDestSlot = currentSlot + 36;
        int syncId = client.player.playerScreenHandler.syncId;

        client.interactionManager.clickSlot(syncId, screenSourceSlot, 0, SlotActionType.PICKUP, client.player);
        client.interactionManager.clickSlot(syncId, screenDestSlot, 0, SlotActionType.PICKUP, client.player);
        client.interactionManager.clickSlot(syncId, screenSourceSlot, 0, SlotActionType.PICKUP, client.player);
    }

    private boolean canRodReplace() {
        long now = System.currentTimeMillis();
        if (now - lastRodReplaceTime >= ROD_REPLACE_COOLDOWN) {
            lastRodReplaceTime = now;
            return true;
        }
        return false;
    }

    private boolean isRodCast(MinecraftClient client) {
        if (client.player == null) return false;
        return client.player.fishHook != null;
    }

    private boolean canRodAction() {
        long now = System.currentTimeMillis();
        if (now - lastRodActionTime >= ROD_ACTION_COOLDOWN) {
            lastRodActionTime = now;
            return true;
        }
        return false;
    }

    private String getRodStateString(MinecraftClient client) {
        if (!isHoldingFishingRod(client)) {
            return "§c未持竿";
        } else if (isRodCast(client)) {
            return "§a已抛出";
        } else {
            return "§e待抛出";
        }
    }

    private String getDurabilityString(MinecraftClient client) {
        if (!isHoldingFishingRod(client)) {
            return "§7-";
        }
        int percent = getRodDurabilityPercent(client);
        int remaining = getRodRemainingDurability(client);

        String color;
        if (percent >= 50) {
            color = "§a";
        } else if (percent >= 25) {
            color = "§e";
        } else if (percent >= durabilityThreshold) {
            color = "§6";
        } else {
            color = "§c";
        }

        return color + percent + "% §7(" + remaining + ")";
    }

    // ===== 条件判断 =====
    private boolean canFish(MinecraftClient client) {
        if (client.world == null) return false;

        long timeOfDay = client.world.getTimeOfDay() % 24000;
        boolean isDay = timeOfDay < 13000;
        boolean isNight = timeOfDay >= 13000;
        boolean isRaining = client.world.isRaining();

        if (onlyDaytime && !isDay) return false;
        if (onlyNighttime && !isNight) return false;
        if (onlyRaining && !isRaining) return false;
        if (onlyNotRaining && isRaining) return false;

        return true;
    }

    // ===== 设置界面输入处理 =====
    private void handleSettingsInput(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (now - lastKeyPressTime < 150) return;

        var window = client.getWindow();

        if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_UP)) {
            selectedOption = (selectedOption - 1 + TOTAL_OPTIONS) % TOTAL_OPTIONS;
            lastKeyPressTime = now;
        }
        if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_DOWN)) {
            selectedOption = (selectedOption + 1) % TOTAL_OPTIONS;
            lastKeyPressTime = now;
        }

        if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_ENTER)) {
            switch (selectedOption) {
                case 0 -> {
                    onlyDaytime = !onlyDaytime;
                    if (onlyDaytime) onlyNighttime = false;
                }
                case 1 -> {
                    onlyNighttime = !onlyNighttime;
                    if (onlyNighttime) onlyDaytime = false;
                }
                case 2 -> {
                    onlyRaining = !onlyRaining;
                    if (onlyRaining) onlyNotRaining = false;
                }
                case 3 -> {
                    onlyNotRaining = !onlyNotRaining;
                    if (onlyNotRaining) onlyRaining = false;
                }
                case 4 -> checkDurability = !checkDurability;
                case 6 -> autoReplaceRod = !autoReplaceRod;
                case 7 -> autoUseLuckPotion = !autoUseLuckPotion;
                case 10 -> {
                    lavaFishingMode = !lavaFishingMode;
                    resetLavaBobberTracking();
                    resetLavaQteState();
                }
            }
            lastKeyPressTime = now;
        }

        if (selectedOption == 5) {
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT)) {
                durabilityThreshold = Math.max(1, durabilityThreshold - 5);
                lastKeyPressTime = now;
            }
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT)) {
                durabilityThreshold = Math.min(100, durabilityThreshold + 5);
                lastKeyPressTime = now;
            }
        }

        if (selectedOption == 8) {
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT)) {
                luckPotionThreshold = Math.max(5, luckPotionThreshold - 5);
                lastKeyPressTime = now;
            }
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT)) {
                luckPotionThreshold = Math.min(120, luckPotionThreshold + 5);
                lastKeyPressTime = now;
            }
        }

        if (selectedOption == 9) {
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT)) {
                itemSwitchDelay = Math.max(0, itemSwitchDelay - 1);
                lastKeyPressTime = now;
            }
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT)) {
                itemSwitchDelay = Math.min(20, itemSwitchDelay + 1);
                lastKeyPressTime = now;
            }
        }

        if (selectedOption == 11) {
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT)) {
                lavaSettleTime = Math.max(20, lavaSettleTime - 10);
                lastKeyPressTime = now;
            }
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT)) {
                lavaSettleTime = Math.min(200, lavaSettleTime + 10);
                lastKeyPressTime = now;
            }
        }

        if (selectedOption == 12) {
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT)) {
                lavaRecastDelay = Math.max(5, lavaRecastDelay - 5);
                lastKeyPressTime = now;
            }
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT)) {
                lavaRecastDelay = Math.min(100, lavaRecastDelay + 5);
                lastKeyPressTime = now;
            }
        }

        if (selectedOption == 13) {
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT)) {
                lavaBobberRiseThreshold = Math.max(0.001, lavaBobberRiseThreshold - 0.005);
                lastKeyPressTime = now;
            }
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT)) {
                lavaBobberRiseThreshold = Math.min(0.1, lavaBobberRiseThreshold + 0.005);
                lastKeyPressTime = now;
            }
        }

        if (selectedOption == 14) {
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT)) {
                lavaQteWaitTimeout = Math.max(20, lavaQteWaitTimeout - 10);
                lastKeyPressTime = now;
            }
            if (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT)) {
                lavaQteWaitTimeout = Math.min(200, lavaQteWaitTimeout + 10);
                lastKeyPressTime = now;
            }
        }
    }

    private String getConditionString(MinecraftClient client) {
        if (client.world == null) return "§7未知";

        long time = client.world.getTimeOfDay() % 24000;
        boolean isRaining = client.world.isRaining();

        String timeStr = time < 13000 ? "§e白天" : "§9夜晚";
        String weatherStr = isRaining ? "§b雨天" : "§a晴天";

        return timeStr + " " + weatherStr;
    }

    private void doRightClick(MinecraftClient client) {
        KeySim.clickRight(client);
    }

    private void doLeftClick(MinecraftClient client) {
        KeySim.clickLeft(client);
    }

    private boolean hasLuckEffect(MinecraftClient client) {
        if (client.player == null) return false;
        return client.player.hasStatusEffect(StatusEffects.LUCK);
    }

    private int getLuckEffectDuration(MinecraftClient client) {
        if (client.player == null) return 0;

        StatusEffectInstance effect = client.player.getStatusEffect(StatusEffects.LUCK);
        if (effect == null) return 0;

        return effect.getDuration() / 20;
    }

    private int getLuckEffectLevel(MinecraftClient client) {
        if (client.player == null) return 0;

        StatusEffectInstance effect = client.player.getStatusEffect(StatusEffects.LUCK);
        if (effect == null) return 0;

        return effect.getAmplifier() + 1;
    }

    private String getLuckEffectString(MinecraftClient client) {
        if (!hasLuckEffect(client)) {
            return "§c无";
        }

        int level = getLuckEffectLevel(client);
        int duration = getLuckEffectDuration(client);

        String color;
        if (duration > 60) {
            color = "§a";
        } else if (duration > 30) {
            color = "§e";
        } else {
            color = "§c";
        }

        String timeStr;
        if (duration >= 60) {
            int minutes = duration / 60;
            int seconds = duration % 60;
            timeStr = String.format("%d:%02d", minutes, seconds);
        } else {
            timeStr = duration + "s";
        }

        return color + "Lv." + level + " " + timeStr;
    }

    // ===== 简化的药水检测 =====

    private boolean isDrinkablePotion(ItemStack stack) {
        if (stack.isEmpty()) return false;

        if (!stack.isOf(Items.POTION)) {
            return false;
        }

        PotionContentsComponent potionContents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (potionContents != null) {
            if (potionContents.potion().isPresent()) {
                if (potionContents.potion().get() == Potions.WATER) {
                    return false;
                }
            }
        }

        return true;
    }

    private int findDrinkablePotionSlot(MinecraftClient client) {
        if (client.player == null) return -1;

        PlayerInventory inventory = client.player.getInventory();

        for (int i = 0; i < 9; i++) {
            if (isDrinkablePotion(inventory.getStack(i))) {
                return i;
            }
        }

        for (int i = 9; i < 36; i++) {
            if (isDrinkablePotion(inventory.getStack(i))) {
                return i;
            }
        }

        return -1;
    }

    private int countDrinkablePotions(MinecraftClient client) {
        if (client.player == null) return 0;

        PlayerInventory inventory = client.player.getInventory();
        int count = 0;

        for (int i = 0; i < 36; i++) {
            if (isDrinkablePotion(inventory.getStack(i))) {
                count++;
            }
        }

        return count;
    }

    private boolean shouldDrinkLuckPotion(MinecraftClient client) {
        if (!autoUseLuckPotion) return false;
        if (drinkingPhase != DrinkingPhase.IDLE) return false;
        if (waitingForHookRetract) return false;
        if (client.player == null) return false;

        int remainingTime = getLuckEffectDuration(client);
        return remainingTime < luckPotionThreshold;
    }

    private void startDrinkingLuckPotion(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;

        int potionSlot = findDrinkablePotionSlot(client);
        if (potionSlot == -1) {
            statusText = "§c没有可喝的药水了！";
            return;
        }

        originalRodSlot = getSelectedSlot(client);
        potionSourceSlot = potionSlot;
        potionWasInHotbar = potionSlot < 9;

        if (potionSlot >= 9) {
            swapSlots(client, potionSlot, originalRodSlot);
            drinkingPhase = DrinkingPhase.WAIT_AFTER_SWAP_TO_POTION;
            phaseTimer = itemSwitchDelay;
            statusText = "§e交换药水到手上，等待 " + itemSwitchDelay + " tick...";
        } else {
            switchToSlot(client, potionSlot);
            drinkingPhase = DrinkingPhase.WAIT_AFTER_SWAP_TO_POTION;
            phaseTimer = itemSwitchDelay;
            statusText = "§e切换到药水槽位，等待 " + itemSwitchDelay + " tick...";
        }
    }

    private void processDrinkingStateMachine(MinecraftClient client) {
        if (client.player == null) return;

        switch (drinkingPhase) {
            case WAIT_AFTER_SWAP_TO_POTION:
                if (phaseTimer > 0) {
                    phaseTimer--;
                    statusText = "§e等待切换完成: " + phaseTimer + " tick";
                } else {
                    ItemStack heldItem = client.player.getMainHandStack();
                    if (isDrinkablePotion(heldItem)) {
                        client.options.useKey.setPressed(true);
                        drinkingPhase = DrinkingPhase.DRINKING;
                        drinkingTickCounter = 0;
                        statusText = "§e开始喝药水...";
                    } else {
                        finishDrinkingPotion(client, false);
                        statusText = "§c喝药水失败：手上没有药水";
                    }
                }
                break;

            case DRINKING:
                drinkingTickCounter++;
                client.options.useKey.setPressed(true);

                if (drinkingTickCounter >= DRINKING_DURATION) {
                    client.options.useKey.setPressed(false);
                    luckPotionsUsedCount++;
                    client.player.sendMessage(Text.of("§a[自动喝药] 已使用药水 (" + luckPotionsUsedCount + " 瓶)"), true);

                    drinkingPhase = DrinkingPhase.WAIT_AFTER_DRINK;
                    phaseTimer = itemSwitchDelay;
                    statusText = "§a喝完药水，等待 " + itemSwitchDelay + " tick...";
                } else {
                    int remaining = (DRINKING_DURATION - drinkingTickCounter) / 20;
                    statusText = "§e正在喝药水... " + remaining + "s";
                }
                break;

            case WAIT_AFTER_DRINK:
                if (phaseTimer > 0) {
                    phaseTimer--;
                    statusText = "§e喝完等待: " + phaseTimer + " tick";
                } else {
                    if (!potionWasInHotbar) {
                        swapSlots(client, potionSourceSlot, originalRodSlot);
                    } else {
                        switchToSlot(client, originalRodSlot);
                    }
                    drinkingPhase = DrinkingPhase.WAIT_AFTER_SWAP_BACK;
                    phaseTimer = itemSwitchDelay;
                    statusText = "§e换回鱼竿，等待 " + itemSwitchDelay + " tick...";
                }
                break;

            case WAIT_AFTER_SWAP_BACK:
                if (phaseTimer > 0) {
                    phaseTimer--;
                    statusText = "§e恢复鱼竿等待: " + phaseTimer + " tick";
                } else {
                    resetDrinkingState();
                    statusText = "§a药水流程完成，继续钓鱼";
                }
                break;

            default:
                break;
        }
    }

    private void swapSlots(MinecraftClient client, int fromSlot, int toSlot) {
        if (client.interactionManager == null || client.player == null) return;

        int fromScreenSlot = fromSlot < 9 ? fromSlot + 36 : fromSlot;
        int toScreenSlot = toSlot < 9 ? toSlot + 36 : toSlot;

        int syncId = client.player.playerScreenHandler.syncId;

        client.interactionManager.clickSlot(
                syncId,
                fromScreenSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        client.interactionManager.clickSlot(
                syncId,
                toScreenSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );

        client.interactionManager.clickSlot(
                syncId,
                fromScreenSlot,
                0,
                SlotActionType.PICKUP,
                client.player
        );
    }

    private void finishDrinkingPotion(MinecraftClient client, boolean success) {
        client.options.useKey.setPressed(false);
        resetDrinkingState();
    }

    private void resetDrinkingState() {
        drinkingPhase = DrinkingPhase.IDLE;
        phaseTimer = 0;
        drinkingTickCounter = 0;
        originalRodSlot = -1;
        potionSourceSlot = -1;
        potionWasInHotbar = false;
        isDrinkingPotion = false;
    }

    private String getPotionString(MinecraftClient client) {
        if (!autoUseLuckPotion) {
            return "§7禁用";
        }

        int count = countDrinkablePotions(client);
        String countColor = count > 0 ? "§a" : "§c";

        return countColor + count + " 瓶 §7[阈值:" + luckPotionThreshold + "s] §6已用:" + luckPotionsUsedCount;
    }

    private void resetAllState() {
        resetDiamondState();
        resetDrinkingState();
        resetLavaBobberTracking();
        resetLavaQteState();
        actionState = ActionState.IDLE;
        actionTimer = 0;
        qteCount = 0;
        waitingForHookRetract = false;
        lavaWaitingToRecast = false;
        lavaRecastTimer = 0;
    }

    private void renderDebugOverlay(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        // 如果不在白名单，显示提示信息
        if (!isWhitelisted && whitelistChecked) {
            whitelistMessage = "§c您不在白名单中，无法使用此Mod";
            return;
        }

        if (showSettings) {
            renderSettingsPanel(context, client);
        }

        if (!showDebug) return;

        renderInfoPanel(context, client);
    }

    private void renderSettingsPanel(DrawContext context, MinecraftClient client) {
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int panelWidth = 320;
        int panelHeight = 380;
        int panelX = (screenWidth - panelWidth) / 2;
        int panelY = (screenHeight - panelHeight) / 2;
        int lineHeight = 18;
        int padding = 10;

        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xE0000000);

        context.fill(panelX, panelY, panelX + panelWidth, panelY + 2, 0xFF4488FF);
        context.fill(panelX, panelY + panelHeight - 2, panelX + panelWidth, panelY + panelHeight, 0xFF4488FF);
        context.fill(panelX, panelY, panelX + 2, panelY + panelHeight, 0xFF4488FF);
        context.fill(panelX + panelWidth - 2, panelY, panelX + panelWidth, panelY + panelHeight, 0xFF4488FF);

        context.drawText(client.textRenderer, "§b§l=== 钓鱼条件设置 ===",
                panelX + padding, panelY + padding, 0xFFFFFFFF, true);

        String[] options = {
                (selectedOption == 0 ? "§e> " : "  ") + "只在白天钓鱼: " + (onlyDaytime ? "§a开" : "§c关"),
                (selectedOption == 1 ? "§e> " : "  ") + "只在夜晚钓鱼: " + (onlyNighttime ? "§a开" : "§c关"),
                (selectedOption == 2 ? "§e> " : "  ") + "只在雨天钓鱼: " + (onlyRaining ? "§a开" : "§c关"),
                (selectedOption == 3 ? "§e> " : "  ") + "只在晴天钓鱼: " + (onlyNotRaining ? "§a开" : "§c关"),
                (selectedOption == 4 ? "§e> " : "  ") + "检查耐久度: " + (checkDurability ? "§a开" : "§c关"),
                (selectedOption == 5 ? "§e> " : "  ") + "耐久阈值: §6" + durabilityThreshold + "% §7(←→调整)",
                (selectedOption == 6 ? "§e> " : "  ") + "自动换竿: " + (autoReplaceRod ? "§a开" : "§c关"),
                (selectedOption == 7 ? "§e> " : "  ") + "自动喝药水: " + (autoUseLuckPotion ? "§a开" : "§c关"),
                (selectedOption == 8 ? "§e> " : "  ") + "药水阈值: §6" + luckPotionThreshold + "s §7(←→调整)",
                (selectedOption == 9 ? "§e> " : "  ") + "切换延迟: §6" + itemSwitchDelay + " tick §7(←→调整)",
                "§6--- 岩浆钓鱼设置 ---",
                (selectedOption == 10 ? "§e> " : "  ") + "岩浆钓鱼模式: " + (lavaFishingMode ? "§a开" : "§c关"),
                (selectedOption == 11 ? "§e> " : "  ") + "浮标稳定时间: §6" + String.format("%.1fs", lavaSettleTime / 20.0) + " §7(←→调整)",
                (selectedOption == 12 ? "§e> " : "  ") + "重新抛竿延迟: §6" + String.format("%.1fs", lavaRecastDelay / 20.0) + " §7(←→调整)",
                (selectedOption == 13 ? "§e> " : "  ") + "浮标上浮阈值: §6" + String.format("%.3f", lavaBobberRiseThreshold) + " §7(←→调整)",
                (selectedOption == 14 ? "§e> " : "  ") + "QTE检测超时: §6" + String.format("%.1fs", lavaQteWaitTimeout / 20.0) + " §7(←→调整)"
        };

        for (int i = 0; i < options.length; i++) {
            context.drawText(client.textRenderer, options[i],
                    panelX + padding, panelY + padding + 20 + i * lineHeight, 0xFFFFFFFF, true);
        }

        int availableRods = countAvailableRods(client);
        String rodCountText = "§7备用鱼竿: " + (availableRods > 0 ? "§a" : "§c") + availableRods + " 把";
        context.drawText(client.textRenderer, rodCountText,
                panelX + padding, panelY + panelHeight - 50, 0xFFAAAAAA, true);

        int potionCount = countDrinkablePotions(client);
        String potionCountText = "§7可用药水: " + (potionCount > 0 ? "§a" : "§c") + potionCount + " 瓶";
        context.drawText(client.textRenderer, potionCountText,
                panelX + padding, panelY + panelHeight - 34, 0xFFAAAAAA, true);

        context.drawText(client.textRenderer, "§7↑↓选择  回车切换  ←→调整",
                panelX + padding, panelY + panelHeight - 18, 0xFFAAAAAA, true);
    }

    private void renderInfoPanel(DrawContext context, MinecraftClient client) {
        int panelX = 10;
        int panelY = 10;
        int lineHeight = 11;
        int padding = 5;

        String durabilityInfo = getDurabilityString(client);
        if (checkDurability) {
            durabilityInfo += " §7[阈值:" + durabilityThreshold + "%]";
        }
        if (autoReplaceRod) {
            durabilityInfo += " §7[自动换竿]";
        }

        String diamondInfo = qteActive ?
                "§e◆×" + currentDiamondCount + " §7(上次:" + lastDiamondCount + ")" :
                "§7等待中";

        String drinkingPhaseStr = drinkingPhase != DrinkingPhase.IDLE ?
                "§e" + drinkingPhase.name() + " (" + phaseTimer + ")" : "§7空闲";

        // 岩浆钓鱼状态字符串
        String lavaStatusStr = "";
        if (lavaFishingMode) {
            switch (lavaQteState) {
                case IDLE:
                    if (lavaWaitingToRecast) {
                        lavaStatusStr = "§e等待抛竿 " + String.format("%.1fs", lavaRecastTimer / 20.0);
                    } else if (!bobberSettled) {
                        int remainingTicks = Math.max(0, lavaSettleTime - settleTimer);
                        lavaStatusStr = "§e稳定中 " + String.format("%.1fs", remainingTicks / 20.0);
                    } else {
                        lavaStatusStr = "§a监测咬钩";
                    }
                    break;
                case WAITING_FOR_QTE:
                    double remaining = (lavaQteWaitTimeout - lavaQteWaitTimer) / 20.0;
                    lavaStatusStr = "§6等待QTE " + String.format("%.1fs", remaining);
                    break;
                case QTE_ACTIVE:
                    lavaStatusStr = "§eQTE中 ◈×" + currentDiamondCount;
                    break;
            }
        }

        // 白名单状态
        String whitelistStatus = isWhitelisted ? "§a已验证" : (whitelistCheckInProgress ? "§e验证中..." : "§c未通过");
        String hudText = trimDebugText(normalizeQteText(getBestFreshHudText()), 32);
        String soundText = lastSoundId != null && System.currentTimeMillis() - lastSoundAt <= SOUND_FRESH_MS
                ? trimDebugText(lastSoundId.toString(), 36)
                : "§7无";
        BalanceBarSnapshot debugBar = getFreshBalanceBar();
        String balanceText = debugBar.valid()
                ? "§fG=" + debugBar.greenStart() + "-" + debugBar.greenEnd() + " P=" + debugBar.pointer()
                : "§7无";
        String balanceFallbackText = balanceFallbackActive
                ? "§f'" + balanceFallbackSymbol + "' ×" + balanceFallbackBaseline
                + (balanceFallbackTriggered ? " §a已收杆" : "")
                : "§7无";

        String[] lines = {
                "§b=== 自动钓鱼QTE ===",
                "白名单: " + whitelistStatus + " §7(" + playerName + ")",
                "状态: " + (isRunning ? "§a运行中" : "§c已停止"),
                "检测模式: " + (lavaFishingMode ? "§6岩浆钓鱼+QTE" : "§e◆ QTE"),
                "QTE模式: §e" + activeQteMode.name() + " §7" + diamondInfo,
                "HUD捕捉: §f" + (hudText.isEmpty() ? "§7无" : hudText),
                "最近音效: §f" + soundText,
                "平衡条: " + balanceText,
                "字符fallback: " + balanceFallbackText,
                lavaFishingMode ? "岩浆状态: " + lavaStatusStr : "",
                "鱼竿: " + getRodStateString(client),
                "耐久: " + durabilityInfo,
                "备用: §7" + countAvailableRods(client) + " 把  §6换竿: " + rodsReplacedCount + " 次",
                "幸运: " + getLuckEffectString(client),
                "药水: " + getPotionString(client),
                "喝药阶段: " + drinkingPhaseStr,
                "切换延迟: §6" + itemSwitchDelay + " tick",
                "环境: " + getConditionString(client),
                "条件: " + (canFish(client) ? "§a满足" : "§c不满足"),
                "本次QTE: §e" + qteCount + (lavaFishingMode ? "  §6岩浆: " + lavaFishCount : ""),
                "累计QTE: §6" + totalQteCount,
        };

        // 过滤空行
        java.util.List<String> filteredLines = new java.util.ArrayList<>();
        for (String line : lines) {
            if (!line.isEmpty()) {
                filteredLines.add(line);
            }
        }

        int panelHeight = filteredLines.size() * lineHeight + padding * 2;
        int panelWidth = 300;

        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xC0000000);

        for (int i = 0; i < filteredLines.size(); i++) {
            context.drawText(client.textRenderer, filteredLines.get(i),
                    panelX + padding, panelY + padding + i * lineHeight, 0xFFFFFFFF, true);
        }
    }

    private String trimDebugText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
