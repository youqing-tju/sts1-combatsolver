package io.github.youqing.sts1solver.mirror.hooks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.youqing.sts1solver.mirror.DamageCalc;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 钩子派发器。阶段顺序与派发顺序都照原版，见 {@link HookPhase}。
 *
 * <p>派发顺序 = {@code player.powers} 的**应用顺序**（原版是 {@code for (p : powers)}），
 * 所以影子里的 {@code playerPowers} 必须是保序 Map —— 用 TreeMap 会按 ID 排序，
 * 把「金属化先给格挡、再生后回血」这类顺序语义悄悄改掉。
 */
public final class Hooks {

    /** 减益白名单：只有这些状态被打到敌人身上时才会触发「残虐天性」。 */
    private static final Set<String> DEBUFFS = new HashSet<String>(Arrays.asList(
            "Vulnerable", "Weakened", "Frail", "Poison", "Entangled", "No Draw",
            "Confusion", "Constricted", "Hex", "Mark", "Shackled", "Flex",
            "DexLoss", "Slow", "Weak"));

    private Hooks() {
    }

    /** 跑完一个阶段的全部能力钩子。 */
    public static void firePhase(SimState state, HookPhase phase) {
        // 先快照：钩子可能新增 power（恶魔形态 +力量），原版那些是走 action 在循环之后生效的，
        // 直接遍历会被 ConcurrentModificationException 打断，也会让新加的 power 吃到本阶段。
        ArrayList<Map.Entry<String, Integer>> snapshot =
                new ArrayList<Map.Entry<String, Integer>>(state.playerPowers.entrySet());
        HookContext ctx = HookContext.none();
        for (Map.Entry<String, Integer> e : snapshot) {
            PowerHooks.Handler handler = PowerHooks.lookup(e.getKey(), phase);
            if (handler == null) {
                continue;
            }
            // 层数要用「当前」值：前一个钩子可能改写它（炸弹倒计时）
            Integer current = state.playerPowers.get(e.getKey());
            if (current == null) {
                continue;   // 前一个钩子把它移除了
            }
            handler.fire(state, current, ctx);
        }
    }

    /** 一张牌结算完（原版 UseCardAction 末尾）。 */
    public static void onCardUsed(SimState state, SimCard card, int targetIndex) {
        // Pain.triggerOnOtherCardPlayed（痛楚诅咒）：手里有 Pain 时，每打出**别的**一张牌
        // 就 LoseHPAction(player, player, 1)（HP_LOSS：无视图格挡）。
        // 打 Pain 自己时它已经从手牌里移除了，所以这里遍历不到它 —— 与原版一致。
        for (SimCard inHand : state.hand) {
            if ("Pain".equals(inHand.id)) {
                io.github.youqing.sts1solver.sim.CombatFlow.hitPlayer(state, 1, null,
                        io.github.youqing.sts1solver.sim.DamageKind.HP_LOSS);
                break;
            }
        }

        HookContext ctx = HookContext.none();
        ctx.card = card;
        ctx.targetIndex = targetIndex;
        for (Map.Entry<String, Integer> e
                : new ArrayList<Map.Entry<String, Integer>>(state.playerPowers.entrySet())) {
            PowerHooks.Handler handler = PowerHooks.lookup(e.getKey(), HookPhase.USE_CARD);
            Integer current = state.playerPowers.get(e.getKey());
            if (handler != null && current != null) {
                handler.fire(state, current, ctx);
            }
        }
        RelicHooks.onCardUsed(state, card);
        monsterOnCardUsed(state, card);
    }

    /**
     * 诅咒（{@code HexPower.onUseCard}，Chosen 的技能）：玩家**每打出一张非攻击牌**，
     * 就往抽牌堆随机位置塞 {@code amount} 张 Dazed（字节码：{@code card.type != ATTACK}
     * → {@code MakeTempCardInDrawPileAction(new Dazed(), amount, true, true)}）。
     *
     * <p>不建模的后果同样是方向性错误：搜索会以为「多打技能牌没代价」，
     * 在 Chosen 面前狂刷技能，然后每次都被塞牌拖垮后续回合。
     */
    private static void hex(SimState state, SimCard card) {
        if ("ATTACK".equals(card.type)) {
            return;
        }
        Integer amount = state.playerPowers.get("Hex");
        if (amount == null || amount <= 0) {
            return;
        }
        io.github.youqing.sts1solver.mirror.Effects
                .insertIntoDrawPileRandom("Dazed", amount.intValue())
                .applyTo(state, -1, null);
    }

    /**
     * 尖刺外壳（{@code SharpHidePower.onUseCard}，TheGuardian 的防御姿态技能）：
     * 玩家**每打出一张攻击牌**就掉 {@code amount} 点血（{@code DamageInfo} 类型是 THORNS）。
     *
     * <p>注意它不是「被打时反伤」，而是「你出手就扣血」—— 跟荆棘（{@code ThornsPower}）的
     * 触发点完全不同，别混。
     */
    private static void sharpHide(SimState state, SimCard card) {
        if (!"ATTACK".equals(card.type)) {
            return;
        }
        for (SimMonster monster : state.monsters) {
            if (monster.hp <= 0) {
                continue;
            }
            Integer amount = monster.powers.get("Sharp Hide");
            if (amount != null && amount > 0) {
                io.github.youqing.sts1solver.sim.CombatFlow.hitPlayer(state, amount);
            }
        }
    }

    /**
     * 怪物侧的 {@code onUseCard}（目前只有**激怒 Enrage**）。
     *
     * <p>{@code AngerPower.onUseCard}（界面上叫 Enrage/激怒，**类名与 ID 都是 Anger**）：
     * 玩家每打出一张 **技能牌**，主人就 +amount 力量
     * （字节码直读：{@code card.type == SKILL → ApplyPowerAction(owner, owner, Strength(amount))}）。
     * 这是 GremlinNob 的核心机制 —— 不建模的话搜索会以为「多打技能牌没有代价」，
     * 在小鬼头目面前疯狂甩防御。
     *
     * <p>⚠️ 只看得到**能力表里现有的**激怒层数；捕获时 {@code SimState.capture} 会把
     * {@code AngerPower.POWER_ID}（"Anger"）带进来，所以真机打着打着也会准。
     * ⚠️ 2026-10-31 修正：以前这里写 "Enrage"，原版**没有**这个 ID —— 抓不到能力，
     * 大块头的力量涨到 +19 我们这边还是 0，于是「以为它只打 14 点」不铺格挡，实机被 33 点打穿。
     */
    private static void monsterOnCardUsed(SimState state, SimCard card) {
        sharpHide(state, card);
        hex(state, card);
        // SlowPower.onAfterUseCard：**每打一张牌**（不分类型）这只怪的 amount +1，
        // 受伤倍率随之提高（见 DamageCalc.attackDamageWithoutRelics），回合末清零。
        for (SimMonster slowMonster : state.monsters) {
            if (slowMonster.hp <= 0) {
                continue;
            }
            Integer slow = slowMonster.powers.get("Slow");
            if (slow != null) {
                slowMonster.powers.put("Slow", Integer.valueOf(slow.intValue() + 1));
            }
        }
        // BeatOfDeathPower.onAfterUseCard（CorruptHeart）：每打一张牌，玩家掉 amount 点血。
        // 字节码：new DamageAction(player, new DamageInfo(owner, amount, **THORNS**))
        // → THORNS 不是 HP_LOSS，所以**吃格挡**（AbstractCreature.damage 的判据）。
        for (SimMonster beatMonster : state.monsters) {
            if (beatMonster.hp <= 0) {
                continue;
            }
            Integer beat = beatMonster.powers.get("BeatOfDeath");
            if (beat != null && beat.intValue() > 0) {
                io.github.youqing.sts1solver.sim.CombatFlow
                        .hitPlayer(state, beat.intValue());
            }
        }
        // TimeWarpPower.onAfterUseCard（TimeEater）：**每打一张牌**计数 +1；
        // 到第 12 张时清零、`callEndTurnEarlySequence()`（当场结束玩家回合），
        // 并且给**每只怪** +2 力量。
        Integer timeWarp = state.playerPowers.get("Time Warp");
        if (timeWarp != null) {
            int next = timeWarp.intValue() + 1;
            if (next >= 12) {
                state.playerPowers.put("Time Warp", Integer.valueOf(0));
                state.turnForcedEnd = true;
                for (SimMonster monster : state.monsters) {
                    if (monster.hp <= 0) {
                        continue;
                    }
                    Integer strength = monster.powers.get("Strength");
                    monster.powers.put("Strength", (strength == null ? 0 : strength) + 2);
                }
            } else {
                state.playerPowers.put("Time Warp", Integer.valueOf(next));
            }
        }
        if (!"SKILL".equals(card.type)) {
            // CuriosityPower.onUseCard（AwakenedOne 一形态）：玩家打出 **POWER** 牌 →
            // 主人 +amount 力量（字节码判据就是 `card.type == CardType.POWER`）。
            // 不建的话搜索会以为「打能力牌没代价」，在觉醒者面前免费叠 buff。
            for (SimMonster curious : state.monsters) {
                if (curious.hp <= 0) {
                    continue;
                }
                Integer curiosity = curious.powers.get("Curiosity");
                if (curiosity == null || curiosity <= 0 || !"POWER".equals(card.type)) {
                    continue;
                }
                Integer str = curious.powers.get("Strength");
                curious.powers.put("Strength", (str == null ? 0 : str) + curiosity);
            }
            return;
        }
        for (SimMonster monster : state.monsters) {
            if (monster.hp <= 0) {
                continue;
            }
            Integer enrage = monster.powers.get("Anger");
            if (enrage == null || enrage <= 0) {
                continue;
            }
            Integer strength = monster.powers.get("Strength");
            monster.powers.put("Strength", (strength == null ? 0 : strength) + enrage);
        }
    }

    /**
     * 一个状态被施加到怪物身上（原版 ApplyPowerAction → onApplyPower）。
     * 只有减益才触发；未知 ID 一律当增益处理，不猜。
     */
    public static void onPowerApplied(SimState state, String powerId, int amount,
                                      SimMonster target) {
        if (target == null || amount == 0 || !isDebuff(powerId, amount)) {
            return;
        }
        HookContext ctx = HookContext.none();
        ctx.appliedPower = powerId;
        ctx.appliedAmount = amount;
        ctx.appliedTo = target;
        for (Map.Entry<String, Integer> e
                : new ArrayList<Map.Entry<String, Integer>>(state.playerPowers.entrySet())) {
            PowerHooks.Handler handler = PowerHooks.lookup(e.getKey(), HookPhase.APPLY_POWER);
            Integer current = state.playerPowers.get(e.getKey());
            if (handler != null && current != null) {
                handler.fire(state, current, ctx);
            }
        }
    }

    /**
     * 获得格挡的唯一入口：加完立即触发 onGainedBlock（尖刺外壳）。
     * 任何绕过这里的格挡都不会触发尖刺外壳，所以所有加格挡的路径都要走它。
     */
    public static void gainBlock(SimState state, int amount) {
        if (amount <= 0) {
            return;
        }
        state.block += amount;
        HookContext ctx = HookContext.none();
        ctx.gainedBlock = amount;
        for (Map.Entry<String, Integer> e
                : new ArrayList<Map.Entry<String, Integer>>(state.playerPowers.entrySet())) {
            PowerHooks.Handler handler = PowerHooks.lookup(e.getKey(), HookPhase.GAINED_BLOCK);
            Integer current = state.playerPowers.get(e.getKey());
            if (handler != null && current != null) {
                handler.fire(state, current, ctx);
            }
        }
    }

    /**
     * 这个「施加能力」算不算减益（{@code AbstractPower.PowerType.DEBUFF}）。
     *
     * <p>判定口径与原版 {@code ApplyPowerAction} 一样：力量/敏捷的**负增量**按减益算
     * （缴械那种），其余查白名单。
     */
    public static boolean isDebuff(String id, int amount) {
        if (DamageCalc.STRENGTH.equals(id) || DamageCalc.DEXTERITY.equals(id)) {
            return amount < 0;      // 缴械那种「力量 -2」按减益算
        }
        return DEBUFFS.contains(id);
    }
}
