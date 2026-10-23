package io.github.youqing.sts1solver.mirror;

import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

/**
 * 战斗中药水的语义 —— 对应蓝本 {@code SimulatedCombatState.Potions} +
 * {@code PotionChoiceMirrors} 的第一块（**镜像层**，不含估值策略）。
 *
 * <p>药水在 STS1 是**槽位制**：{@code AbstractPlayer.potions} 是一张固定长度的列表，
 * 用掉一格就空出来，捡到新的才填进去。药水可以被丢弃，所以「槽位空」本身也是状态。
 *
 * <h2>目前覆盖 14 张（直接效果类）</h2>
 *
 * <p>STS1 有 40+ 张药水，语义差别很大（生成类、选择类、被动类……）。
 * 这一批挑的是「**直接对战斗状态产生效果**」的常见药水：
 *
 * <ul>
 *   <li>伤害：{@link #FIRE_POTION} 火药水、{@link #EXPLOSIVE_POTION} 爆炸药水</li>
 *   <li>自身增益：{@link #BLOCK_POTION} 格挡、{@link #ENERGY_POTION} 能量、
 *       {@link #STRENGTH_POTION} 力量、{@link #DEXTERITY_POTION} 敏捷、
 *       {@link #ANCIENT_POTION} 神器、{@link #HEART_OF_IRON} 金属化、
 *       {@link #LIQUID_BRONZE} 荆棘</li>
 *   <li>施加给敌人：{@link #FEAR_POTION} 易伤、{@link #POISON_POTION} 中毒</li>
 *   <li>恢复/成长：{@link #BLOOD_POTION} 血药、{@link #FRUIT_JUICE} 果汁</li>
 *   <li>被动：{@link #FAIRY_POTION} 瓶中仙子（{@code canUse() == false}，死亡时自动触发，
 *       见 {@code DeathSaveRelic}）</li>
 * </ul>
 *
 * <p>剩下的是三类形态，各需要一套自己的机制，见类末 TODO：
 * 三选一（{@code Power/Attack/Skill/Colorless Potion}）、生成（{@code Distilled Chaos} /
 * {@code Duplication}）、特殊（{@code Gamblers Brew} / {@code Liquid Memories} / {@code Elixir}）。
 *
 * <p>语义全部按字节码核实（{@code com.megacrit.cardcrawl.potions.*}），见
 * {@code mechanics-reference} §25。
 */
public final class Potions {

    /** STS1 药水的运行时 ID（{@code AbstractPotion.POTION_ID}，javap 逐个核实）。
     *
     * <p>⚠️ 格式**不统一**：多数带空格，瓶中仙子<b>不带</b>。
     * 记错的话对拍会一直失配，而且看不出原因。
     */
    public static final String FIRE_POTION = "Fire Potion";
    public static final String BLOCK_POTION = "Block Potion";
    public static final String ENERGY_POTION = "Energy Potion";
    public static final String FAIRY_POTION = "FairyPotion";
    public static final String STRENGTH_POTION = "Strength Potion";
    public static final String DEXTERITY_POTION = "Dexterity Potion";
    public static final String FEAR_POTION = "Fear Potion";
    public static final String EXPLOSIVE_POTION = "Explosive Potion";
    public static final String BLOOD_POTION = "Blood Potion";
    public static final String ANCIENT_POTION = "Ancient Potion";
    public static final String POISON_POTION = "Poison Potion";
    public static final String FRUIT_JUICE = "Fruit Juice";
    public static final String HEART_OF_IRON = "Heart of Iron";
    public static final String LIQUID_BRONZE = "Liquid Bronze";
    /** 三选一发现（走 {@code DiscoveryAction}）。ID **都无空格**。 */
    public static final String POWER_POTION = "PowerPotion";
    public static final String ATTACK_POTION = "AttackPotion";
    public static final String SKILL_POTION = "SkillPotion";
    public static final String COLORLESS_POTION = "ColorlessPotion";
    /**
     * 选牌类（玩家自己挑）：弃手牌抽等量 / 消耗手牌 / 从弃牌堆拿回。
     *
     * <p>⚠️ 灵药的 ID 是 {@code "ElixirPotion"}（带 Potion 后缀），不是 {@code "Elixir"}。
     */
    public static final String GAMBLERS_BREW = "GamblersBrew";
    public static final String ELIXIR = "ElixirPotion";
    public static final String LIQUID_MEMORIES = "LiquidMemories";

    /** 药水用掉后要不要选牌，选几张。 */
    public enum ChoiceMode {
        /** 不选。 */
        NONE,
        /** 选 1 张（三选一发现 / 液态记忆）。 */
        SINGLE,
        /** 选任意数量（赌徒酿造 / 灵药）。 */
        MULTI
    }

    /**
     * 火药水伤害（{@code FirePotion.getPotency} → {@code bipush 20}）。
     * 不随升级变，也不吃玩家的力量。
     */
    private static final int FIRE_POTION_DAMAGE = 20;

    /** 格挡药水格挡量（{@code BlockPotion.getPotency} → {@code bipush 12}）。 */
    private static final int BLOCK_POTION_BLOCK = 12;

    /** 能量药水（{@code EnergyPotion.getPotency} → {@code iconst_2}）。 */
    private static final int ENERGY_POTION_ENERGY = 2;

    /** 瓶中仙子回血百分比（{@code FairyPotion.getPotency} → {@code bipush 30}）。 */
    private static final int FAIRY_POTION_PERCENT = 30;

    /** 力量药水（{@code StrengthPotion.getPotency} → {@code iconst_2}）。 */
    private static final int STRENGTH_POTION_AMOUNT = 2;

    /** 敏捷药水（{@code DexterityPotion.getPotency} → {@code iconst_2}）。 */
    private static final int DEXTERITY_POTION_AMOUNT = 2;

    /** 恐惧药水（{@code FearPotion.getPotency} → {@code iconst_3}）：施加 3 层易伤。 */
    private static final int FEAR_POTION_AMOUNT = 3;

    /** 爆炸药水（{@code ExplosivePotion.getPotency} → {@code bipush 10}）：对所有活着的怪。 */
    private static final int EXPLOSIVE_POTION_DAMAGE = 10;

    /** 血药（{@code BloodPotion.getPotency} → {@code bipush 20}）：回 20% 最大生命。 */
    private static final int BLOOD_POTION_PERCENT = 20;

    /** 古老药水（{@code AncientPotion.getPotency} → {@code iconst_1}）：1 层神器。 */
    private static final int ANCIENT_POTION_AMOUNT = 1;

    /** 毒药水（{@code PoisonPotion.getPotency} → {@code bipush 6}）：施加 6 层中毒。 */
    private static final int POISON_POTION_AMOUNT = 6;

    /** 果汁（{@code FruitJuice.getPotency} → {@code iconst_5}）：+5 最大生命。 */
    private static final int FRUIT_JUICE_AMOUNT = 5;

    /** 铁心（{@code HeartOfIron.getPotency} → {@code bipush 6}）：6 层金属化。 */
    private static final int HEART_OF_IRON_AMOUNT = 6;

    /** 液态青铜（{@code LiquidBronze.getPotency} → {@code iconst_3}）：3 层荆棘。 */
    private static final int LIQUID_BRONZE_AMOUNT = 3;

    /** 战斗中的默认药水槽上限（{@code PotionSlot}：铁甲战士 2 格）。 */
    public static final int DEFAULT_POTION_SLOTS = 2;

    private Potions() {
    }

    /**
     * 这张药水能不能被主动使用。
     *
     * <p>{@code FairyPotion.canUse()} 直接 {@code return false}：
     * 瓶中仙子**只能**在死亡分支里自动触发（见 {@code DeathSaveRelic}），
     * 面板上看到它但点不动。
     */
    public static boolean canUse(String potionId) {
        return !FAIRY_POTION.equals(potionId);
    }

    /** 这张药水要选目标吗（火药水 / 恐惧药水 / 毒药水）。 */
    public static boolean needsTarget(String potionId) {
        return FIRE_POTION.equals(potionId)
                || FEAR_POTION.equals(potionId)
                || POISON_POTION.equals(potionId);
    }

    /**
     * 这张药水用掉后要**开选牌**吗（原版 {@code DiscoveryAction} 会开三选一界面）。
     *
     * <p>对应蓝本 {@code PotionChoiceSupport.RequiresChoice} —— 药水引发的选牌
     * 是动作的一部分，搜索要为每个候选各展开一条分支。
     */
    public static boolean needsChoice(String potionId) {
        return choiceModeOf(potionId) != ChoiceMode.NONE;
    }

    /** 这张药水的选牌形态（决定搜索展开单选还是多选分支）。 */
    public static ChoiceMode choiceModeOf(String potionId) {
        if (POWER_POTION.equals(potionId) || ATTACK_POTION.equals(potionId)
                || SKILL_POTION.equals(potionId) || COLORLESS_POTION.equals(potionId)
                || LIQUID_MEMORIES.equals(potionId)) {
            return ChoiceMode.SINGLE;
        }
        if (GAMBLERS_BREW.equals(potionId) || ELIXIR.equals(potionId)) {
            return ChoiceMode.MULTI;
        }
        return ChoiceMode.NONE;
    }

    /**
     * 选牌的候选来自哪个牌堆（供搜索展开分支时取下标）。
     *
     * <ul>
     *   <li>{@link #LIQUID_MEMORIES}：弃牌堆（原版 {@code BetterDiscardPileToHandAction}
     *       从 {@code discardPile} 取 → 加入 hand → 从弃牌堆移除）</li>
     *   <li>其余选牌类：手牌（三选一发现是**生成的候选**，不属任何牌堆，
     *       由 {@link #use} 自己算 3 张，所以下标固定 0..2）</li>
     * </ul>
     */
    public static boolean choiceFromDiscard(String potionId) {
        return LIQUID_MEMORIES.equals(potionId);
    }

    /** 不需要选牌的药水（绝大多数）。 */
    public static boolean use(SimState state, String potionId, int targetIndex) {
        return use(state, potionId, targetIndex, -1);
    }

    /**
     * 多选形态（赌徒酿造 / 灵药）：按一组下标结算。
     *
     * <p>原版分别走 {@code GamblingChipAction}（弃掉选中的手牌、再抽等量）与
     * {@code ExhaustAction(99, false, true, true)}（{@code anyNumber=true}、
     * {@code canPickZero=true}，从**手牌**选 0~99 张消耗）。
     *
     * @param choices 选中的手牌下标；空数组 = 一张不选（原版 {@code canPickZero}）
     */
    public static boolean useMulti(SimState state, String potionId, int[] choices) {
        if (GAMBLERS_BREW.equals(potionId)) {
            // GamblingChipAction：把选中的手牌移到弃牌堆，再抽等量
            int discarded = moveSelectedHandTo(state, choices, state.discard);
            io.github.youqing.sts1solver.sim.CombatFlow.drawAfterPotion(state, discarded);
            return true;
        }
        if (ELIXIR.equals(potionId)) {
            moveSelectedHandTo(state, choices, state.exhaust);
            return true;
        }
        if (choiceModeOf(potionId) == ChoiceMode.SINGLE) {
            // 单选走 choiceIndex 那条路（三选一发现 / 液态记忆）
            return use(state, potionId, -1, choices != null && choices.length > 0 ? choices[0] : -1);
        }
        throw new ActionRejected("Potion " + potionId + " -> no multi-choice mirror");
    }

    /**
     * 把选中的手牌移到目标牌堆（弃牌堆 / 消耗堆），返回实际移走的张数。
     *
     * <p>下标按**原始手牌顺序**解释，从大到小移除以免下标错位
     * （原版 {@code HandCardSelectScreen} 结算后一次性把选中的移到目标堆）。
     */
    private static int moveSelectedHandTo(SimState state, int[] choices,
                                          java.util.List<SimCard> destination) {
        if (choices == null || choices.length == 0) {
            return 0;
        }
        // 去重 + 排序（升序），再从后往前移
        int[] sorted = choices.clone();
        java.util.Arrays.sort(sorted);
        int moved = 0;
        for (int i = sorted.length - 1; i >= 0; i--) {
            int idx = sorted[i];
            if (i > 0 && idx == sorted[i - 1]) {
                continue;               // 重复下标只算一次
            }
            if (idx < 0 || idx >= state.hand.size()) {
                continue;
            }
            SimCard c = state.hand.remove(idx);
            destination.add(c);
            moved++;
        }
        return moved;
    }

    /**
     * 打出一张主动药水。返回 {@code true} 表示成功消耗。
     *
     * <p>顺序照原版 {@code AbstractPotion.use(target)}：
     * 先算效果，再 {@code TopPanel.destroyPotion(slot)} 清槽位。
     * 这里由调用方负责清槽（{@code BeamSolver.potionNode} 在动作里做）。
     *
     * @param choiceIndex 三选一药水（{@link #needsChoice}）选第几张候选，其余传 {@code -1}
     *
     * <h2>伤害管线（火药水 / 爆炸药水）</h2>
     *
     * <p>原版是 {@code new DamageInfo(player, potency, DamageType.THORNS)
     * + applyEnemyPowersOnly(target)}：
     * 只应用**受击方**能力（易伤 ×1.5，向下取整），**不吃玩家的力量**，
     * 也不被玩家的虚弱打折（虚弱挂在攻击者身上，这里不走攻击流程）。
     *
     * <h2>「仅战斗中」的门</h2>
     *
     * <p>力量/古老/铁心/液态青铜/血药的 {@code use()} 开头都有
     * {@code if (getCurrRoom().phase == RoomPhase.COMBAT)} —— 非战斗用是空效果。
     * 我们只在战斗里调这里，所以恒过，但**这一条要记住**：将来接非战斗路径时不能漏。
     */
    public static boolean use(SimState state, String potionId, int targetIndex, int choiceIndex) {
        if (FAIRY_POTION.equals(potionId)) {
            return false;                       // 被动药水，不能主动用
        }
        if (FIRE_POTION.equals(potionId)) {
            SimMonster target = monsterAt(state, targetIndex);
            if (target == null || target.hp <= 0) {
                return false;
            }
            DamageCalc.hit(state, target, damageWithVulnerable(target, FIRE_POTION_DAMAGE));
            return true;
        }
        if (BLOCK_POTION.equals(potionId)) {
            state.block += DamageCalc.blockAmount(state, BLOCK_POTION_BLOCK);
            return true;
        }
        if (ENERGY_POTION.equals(potionId)) {
            state.energy += ENERGY_POTION_ENERGY;
            return true;
        }
        if (STRENGTH_POTION.equals(potionId)) {
            // ApplyPowerAction(player, player, StrengthPower, 2)
            state.playerPowers.put("Strength",
                    amount(state.playerPowers.get("Strength")) + STRENGTH_POTION_AMOUNT);
            return true;
        }
        if (DEXTERITY_POTION.equals(potionId)) {
            state.playerPowers.put("Dexterity",
                    amount(state.playerPowers.get("Dexterity")) + DEXTERITY_POTION_AMOUNT);
            return true;
        }
        if (FEAR_POTION.equals(potionId)) {
            // ApplyPowerAction(player, target, VulnerablePower, 3) —— 过神器
            SimMonster target = monsterAt(state, targetIndex);
            if (target == null || target.hp <= 0) {
                return false;
            }
            Effects.applyDebuff(state, target, "Vulnerable", FEAR_POTION_AMOUNT);
            return true;
        }
        if (EXPLOSIVE_POTION.equals(potionId)) {
            // 对所有活着的怪各打 potency 点（DamageType.THORNS + applyEnemyPowersOnly）
            for (SimMonster m : state.monsters) {
                if (m.hp > 0) {
                    DamageCalc.hit(state, m, damageWithVulnerable(m, EXPLOSIVE_POTION_DAMAGE));
                }
            }
            return true;
        }
        if (BLOOD_POTION.equals(potionId)) {
            // (int)(maxHp * (20/100.0f))，向零截断
            int healAmt = (int) (state.maxHp * (BLOOD_POTION_PERCENT / 100.0f));
            state.hp = Math.min(state.maxHp, state.hp + Math.max(0, healAmt));
            return true;
        }
        if (ANCIENT_POTION.equals(potionId)) {
            state.playerPowers.put("Artifact",
                    amount(state.playerPowers.get("Artifact")) + ANCIENT_POTION_AMOUNT);
            return true;
        }
        if (POISON_POTION.equals(potionId)) {
            // ApplyPowerAction(player, target, PoisonPower, 6) —— 过神器
            SimMonster target = monsterAt(state, targetIndex);
            if (target == null || target.hp <= 0) {
                return false;
            }
            Effects.applyDebuff(state, target, "Poison", POISON_POTION_AMOUNT);
            return true;
        }
        if (FRUIT_JUICE.equals(potionId)) {
            // player.increaseMaxHp(5, true) —— 永久成长，战斗内也生效
            state.maxHp += FRUIT_JUICE_AMOUNT;
            state.hp += FRUIT_JUICE_AMOUNT;
            return true;
        }
        if (HEART_OF_IRON.equals(potionId)) {
            // ApplyPowerAction(player, player, MetallicizePower, 6)
            state.playerPowers.put("Metallicize",
                    amount(state.playerPowers.get("Metallicize")) + HEART_OF_IRON_AMOUNT);
            return true;
        }
        if (LIQUID_BRONZE.equals(potionId)) {
            // ApplyPowerAction(player, player, ThornsPower, 3)
            state.playerPowers.put("Thorns",
                    amount(state.playerPowers.get("Thorns")) + LIQUID_BRONZE_AMOUNT);
            return true;
        }
        // 三选一发现：原版 new DiscoveryAction(CardType, 1)（无色那张是 (true, 1) 的重载）。
        // 效果 = 生成 3 张互不相同的该类型牌，选 1 张进手牌、本回合 0 费
        // —— 与「发现」卡同一套逻辑，只是类型过滤不同。
        String discoveryType = discoveryTypeOf(potionId);
        if (discoveryType != null) {
            Effects.discovery(discoveryType).applyWithChoice(state, targetIndex, null, choiceIndex);
            return true;
        }
        // 液态记忆：从**弃牌堆**选 1 张拿回手牌（原版 BetterDiscardPileToHandAction(potency=1, 0)
        // 从 discardPile 取 → addToHand → 从弃牌堆移除）。
        if (LIQUID_MEMORIES.equals(potionId)) {
            int idx = choiceIndex;
            if (idx < 0 || idx >= state.discard.size()) {
                return false;                  // 弃牌堆空 / 没选中：原版 do-while 也走不到
            }
            SimCard c = state.discard.remove(idx);
            state.hand.add(c);
            return true;
        }
        // 未知药水：显式拒绝，不静默算错（D2 风险点）
        throw new ActionRejected("Potion " + potionId + " -> no mirror");
    }

    /** 三选一药水对应的牌类型；不是三选一返回 {@code null}。 */
    private static String discoveryTypeOf(String potionId) {
        if (POWER_POTION.equals(potionId)) {
            return "POWER";
        }
        if (ATTACK_POTION.equals(potionId)) {
            return "ATTACK";
        }
        if (SKILL_POTION.equals(potionId)) {
            return "SKILL";
        }
        if (COLORLESS_POTION.equals(potionId)) {
            return "COLORLESS";
        }
        return null;
    }

    /**
     * 药水伤害的易伤修正：原版 {@code applyEnemyPowersOnly} 从 {@code output = base} 开始，
     * 只遍历受击方的 power 改写输出，所以**只有易伤生效**（×1.5，向下取整）。
     */
    private static int damageWithVulnerable(SimMonster target, int base) {
        Integer vuln = target.powers.get(DamageCalc.VULNERABLE);
        return (vuln != null && vuln > 0) ? (int) Math.floor(base * 1.5f) : base;
    }

    private static void addPower(java.util.Map<String, Integer> powers, String id, int delta) {
        Integer cur = powers.get(id);
        powers.put(id, (cur == null ? 0 : cur) + delta);
    }

    private static int amount(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 瓶中仙子的回血量（{@code FairyPotion.use}）：
     * {@code max(1, (int)(maxHp * 30 / 100.0f))} —— <b>向零截断</b>，不是四舍五入。
     *
     * <p>调用时机是死亡分支里 {@code currentHealth = 0} **之后**（见 {@code DeathSaveRelic}），
     * 所以从 0 起算，实际血量就是这个值。
     */
    public static int fairyHealAmount(int maxHp) {
        return Math.max(1, (int) (maxHp * FAIRY_POTION_PERCENT / 100.0f));
    }

    private static SimMonster monsterAt(SimState state, int targetIndex) {
        if (targetIndex < 0 || targetIndex >= state.monsters.size()) {
            return null;
        }
        return state.monsters.get(targetIndex);
    }
}
