package io.github.youqing.sts1solver.mirror;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * Coverage probe (the seed of A6). Reports which cards in the current combat have no
 * mirror yet — those cards make an action illegal, so knowing them up front tells us
 * exactly how much of the deck the solver can already reason about.
 */
public final class Coverage {

    private Coverage() {
    }

    public static List<String> unsupportedCards(SimState state) {
        Set<String> missing = new LinkedHashSet<String>();
        collect(state.hand, missing);
        collect(state.draw, missing);
        collect(state.discard, missing);
        collect(state.exhaust, missing);
        return new ArrayList<String>(missing);
    }

    public static int distinctCards(SimState state) {
        Set<String> keys = new LinkedHashSet<String>();
        add(state.hand, keys);
        add(state.draw, keys);
        add(state.discard, keys);
        add(state.exhaust, keys);
        return keys.size();
    }

    /**
     * **不再算噪声**的遗物：要么战斗内没有触发，要么已经被 {@code RelicHooks} / 根状态覆盖。
     * 其余遗物都算「未镜像」。
     */
    private static final java.util.Set<String> INERT_RELICS =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "Ectoplasm",          // 只影响金币
                    "Burning Blood",      // 战斗结束才回血
                    "Anchor",             // 开局格挡，已被根状态捕获
                    "Bag of Marbles",     // 开局易伤，已被根状态捕获
                    "Vajra",              // 开局力量，已被根状态捕获
                    "Bag of Preparation", // 开局多抽两张，已被根状态捕获
                    // 计数类（RelicHooks.onCardUsed 已镜像）
                    "Nunchaku", "Ornamental Fan", "Shuriken", "Pen Nib",
                    "Letter Opener", "InkBottle",
                    // 第二批被动 / 回合钩子（RelicHooks，2026-10-26）
                    "Paper Frog", "Mercury Hourglass", "HornCleat", "CaptainsWheel",
                    "Orichalcum", "Torii",
                    // 能量侧（2026-10-26）
                    "Ice Cream", "Chemical X",
                    // 受伤侧（2026-10-26）
                    "TungstenRod", "Runic Cube", "Centennial Puzzle", "Self Forming Clay",
                    "Red Skull",
                    // 手牌保留（2026-10-26）
                    "Runic Pyramid",
                    // 蛇眼：+2 手牌走 gameHandSize（捕获时已含），费用随机的 Confusion 走抽牌
                    "Snecko Eye",
                    // 第四批（2026-10-26）：消耗 / 洗牌 / 击杀 / 回血 / 格挡衰减 / 药水
                    "Charon's Ashes", "Sundial", "Gremlin Horn", "Magic Flower",
                    "Calipers", "Toy Ornithopter",
                    // 第五批（2026-10-26）：回合计数 / 能力回血 / 减益免疫
                    "Kunai", "Happy Flower", "Bird Faced Urn", "Ginger", "Turnip",
                    // 第六批（2026-10-26）：回合结构
                    "Incense Burner", "Velvet Choker", "Pocketwatch", "Necronomicon",
                    // 死亡豁免（DeathSaveRelic）
                    "Lizard Tail"
            ));

    /**
     * 遗物触发是逐卡核对的头号噪声源：例如 Shuriken 每 3 次攻击给 1 力量，
     * 会让「影子算的伤害」和实机从第三张牌起全部对不上。
     * 目前还镜像不了遗物，所以把未镜像的列出来，让读数的人知道噪声从哪来。
     */
    public static List<String> unmirroredRelics(SimState state) {
        List<String> out = new ArrayList<String>();
        for (String id : state.relics.keySet()) {
            if (!INERT_RELICS.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static void collect(List<SimCard> cards, Set<String> missing) {
        for (SimCard c : cards) {
            if (!MirrorRegistry.supports(c.key())) {
                missing.add(c.key());
            }
        }
    }

    private static void add(List<SimCard> cards, Set<String> keys) {
        for (SimCard c : cards) {
            keys.add(c.key());
        }
    }
}
