// AutoFishMinigame.js — 仅在小游戏完成后触发二次右键版
// JsMacros 2.0.0-beta.5
// ⚠️ 保存为 UTF-8 编码！

const CONFIG = {
    clickTickInterval: 2, // 连点游戏频率：每 2 个 Tick (约 100ms) 点击一次
    lrTickInterval: 4,    // 左右游戏频率：每 4 个 Tick (约 200ms) 点击一次
    castDelayMs: 250,     // 完成小游戏后自动抛杆的延迟 (ms)
    debug: true
};

// 全局小游戏状态
let gameState = null; 
let tickCounter = 0;

function log(...a) { if (CONFIG.debug) Chat.log("§e[FishBot]§r " + a.join(' ')); }

function doLeftClick() {
    try {
        const p = Player.getPlayer();
        if (p) p.attack();
    } catch (e) {}
}

function doRightClick() {
    try {
        const p = Player.getPlayer();
        if (p) p.interact();
    } catch (e) {}
}

// 强制停止并重置状态
// 只有当 isGameActive 为 true（即当前确实处于小游戏中）且 autoCast 为 true 时，才在结算后补右键
function endGame(reason = "正常结束", autoCast = true) {
    const wasInGame = gameState !== null; // 检查此前是否真正处于小游戏中
    const gameKind = gameState ? gameState.kind : "未知";

    if (wasInGame) {
        log(`🛑 结束小游戏 (${gameKind}) -> 原因: ${reason}`);
    }

    gameState = null;
    tickCounter = 0;

    // 只有小游戏过程中触发的结算，才执行二次右键抛杆
    if (wasInGame && autoCast) {
        log(`🎣 小游戏 [${gameKind}] 完成，${CONFIG.castDelayMs}ms 后自动【右键抛杆】...`);
        JsMacros.once("Tick", JavaWrapper.methodToJava(() => {
            Time.sleep(CONFIG.castDelayMs);
            doRightClick();
            log("-> 已自动触发【右键抛杆】");
        }));
    }
}

function plain(msg) { try { return msg.getString(); } catch (e) { return String(msg); } }

function countChar(str, char) {
    let count = 0;
    for (let i = 0; i < str.length; i++) {
        if (str[i] === char) count++;
    }
    return count;
}

function parseArrows(text) {
    const clean = text.replace(/\s/g, '');
    const m = clean.match(/[←→]/g);
    return m ? m : [];
}

// ==========================================
// 1. Title 事件监听
// ==========================================
JsMacros.on("Title", JavaWrapper.methodToJava((event, ctx) => {
    try {
        const type = event.type;
        const text = plain(event.message).trim();

        if (gameState) gameState.lastUpdate = Date.now();

        if (type === 'TITLE') {
            // 处于小游戏中时的结算词判定
            if (text === "" || /成功|胜|完成|收杆|钓上|钓到/.test(text)) { 
                if (gameState) endGame("小游戏检测到成功结算: " + (text || "空文本"), true); 
                return; 
            }

            if (/再试|遗憾|运气不好|失败|结束/.test(text)) {
                if (gameState) endGame("小游戏检测到失败结算: " + text, false);
                return;
            }

            // 模式 1: 鱼游对位
            if (text.includes('🐟')) {
                const symbolCount = countChar(text, '◈') + countChar(text, '♦');
                if (!gameState || gameState.kind !== 'fish') {
                    gameState = { kind: 'fish', initSymbols: symbolCount, fired: false, lastUpdate: Date.now() };
                    log(`开始：鱼游对位 (目标数: ${symbolCount})`);
                } else if (!gameState.fired && symbolCount < gameState.initSymbols) {
                    gameState.fired = true;
                    log(`🎯 目标减少，【右键收杆】！`);
                    doRightClick();
                }
                return;
            }

            // 模式 2: 连点小游戏
            if (/需点击次数/.test(text) || /点击.*次/.test(text)) {
                if (!gameState || gameState.kind !== 'click') {
                    gameState = { 
                        kind: 'click', 
                        startTime: Date.now(), 
                        lastUpdate: Date.now() 
                    };
                    log("开始：连点小游戏");
                }
                return;
            }

            // 模式 3: 方向序列 (0.2s 盲按)
            if (/[←→]/.test(text)) {
                if (gameState && gameState.kind === 'lr') return;

                const arrows = parseArrows(text);
                if (arrows.length > 0) {
                    gameState = { 
                        kind: 'lr', 
                        queue: arrows, 
                        lastUpdate: Date.now() 
                    };
                    tickCounter = CONFIG.lrTickInterval; // 触发后即刻准备输入
                    log(`开始：方向序列 (共 ${arrows.length} 个按键，固定 0.2s 推进)`);
                }
                return;
            }

        } else if (type === 'SUBTITLE') {
            if (/你用力收杆|钓上了/.test(text)) { 
                if (gameState) endGame("小游戏 Subtitle 提示成功: " + text, true); 
                return; 
            }
            if (/鱼逃走了|时间到|超时/.test(text)) {
                if (gameState) endGame("小游戏 Subtitle 提示失败: " + text, false);
                return;
            }
        }
    } catch (e) {
        log("Title 监听异常:", String(e));
    }
}));

// ==========================================
// 2. ClientTick 事件
// ==========================================
JsMacros.on("Tick", JavaWrapper.methodToJava((event, ctx) => {
    try {
        if (!gameState) return;

        const now = Date.now();

        // 🛡️ 心跳保底：2 秒无小游戏更新，自动结束并抛杆
        if (now - gameState.lastUpdate > 2000) {
            endGame("小游戏超时自动判定完成", true);
            return;
        }

        // --- A. 连点游戏逻辑 ---
        if (gameState.kind === 'click') {
            tickCounter++;
            if (tickCounter >= CONFIG.clickTickInterval) {
                tickCounter = 0;
                doLeftClick();
            }
            return;
        }

        // --- B. 左右方向序列逻辑 (0.2 秒盲按) ---
        if (gameState.kind === 'lr') {
            tickCounter++;
            if (tickCounter >= CONFIG.lrTickInterval) {
                tickCounter = 0;

                if (gameState.queue && gameState.queue.length > 0) {
                    const arrow = gameState.queue.shift();
                    if (arrow === '←') {
                        doLeftClick();
                        log(`[0.2s 序列] -> 左键 ← (剩余 ${gameState.queue.length} 个)`);
                    } else if (arrow === '→') {
                        doRightClick();
                        log(`[0.2s 序列] -> 右键 → (剩余 ${gameState.queue.length} 个)`);
                    }
                } else {
                    // 方向全部按完，触发 endGame 并抛杆
                    endGame("方向序列全部执行完毕", true);
                }
            }
        }
    } catch (e) {}
}));

log("钓鱼自动脚本已更新 (仅在真正进行小游戏后才自动抛杆)，挂机中...");