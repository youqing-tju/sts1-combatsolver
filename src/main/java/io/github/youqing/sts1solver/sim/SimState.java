package io.github.youqing.sts1solver.sim;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The shadow combat state: plain data, no game objects, safe to fork and to advance on a
 * background thread.
 *
 * <p>This is the single authority for "what a combat state is". Anything that needs to
 * compare states (the diff harness, the transposition table, the search) goes through
 * this class, so there is exactly one definition to keep honest.
 *
 * <p>{@link #capture()} reads the live game and must run on the <b>main thread</b>.
 * Everything else ({@link #copy()}, {@link #diff}, {@link #fingerprint()}) is pure.
 */
public final class SimState {

    /** The RNG streams that matter for combat. */
    public enum RngKind {
        CARD, MONSTER, MONSTER_HP, AI, SHUFFLE, MISC
    }

    public int act;
    public int floor;
    public int ascension;
    /**
     * 这场战斗是不是**幕末 Boss 房**（{@code AbstractRoom.RoomType.BOSS}）。
     *
     * <p>蓝本用它决定「按回合层分节点预算时给几层」：
     * {@code reservedTurnLayers = roomType == Boss ? BossEnemyStrengthSuppressionHorizon(8)
     *                                            : StandardEnemyStrengthSuppressionHorizon(4)}
     * （{@code CombatBeamSolver.Phases.cs:1428-1432}）。
     *
     * <p>注意蓝本还有另一个 boss 概念 {@code _isActEndingBoss}，那个来自
     * {@code BossHpRelief != None}，指的是「打完这场会回血（幕末 80% 回复）」——
     * **STS1 没有这个机制**，所以那一路分支（持续成长 cap 32 / 虚弱与力量削减 horizon 加倍）
     * 在 STS1 永远走标准档，我们没有实现。
     */
    public boolean boss;
    /** 玩家回合序号（对应 GameActionManager.turn），意图学习表按它索引。 */
    public int turn = 1;

    public int gold;
    public int hp;
    public int maxHp;
    public int block;
    /** 本场战斗损失 HP 的次数（浴血奋战的动态费用用）。 */
    public int hpLossCount;
    /**
     * 一次性救命遗物（蜥蜴尾巴）本场是否已经用掉。
     *
     * <p>蓝本 {@code DeathSaveUseCount}：用掉的次数在最终排序里是**越少越好**的键，
     * 并且用掉的那部分血要在打分里扣回来再加收 9 倍溢价（{@code DeathSavePremium}）。
     */
    public boolean deathSaveUsed;
    /**
     * 本条路线上一次性救命**用掉的次数**（蓝本 {@code DeathSaveUseCount}）。
     *
     * <p>目前最多 2 次：先瓶中仙子（可多瓶），再蜥蜴尾巴（一次）。
     * {@link #deathSaveUsed} 只表达「尾巴用没用」，计数单独记，供
     * {@code FinalPlanOrdering} 的第 3 键用。
     */
    public int deathSaveUseCount;
    /** 救命遗物这条路线上实际回的血（蓝本 {@code DeathSaveHpRestored}）。 */
    public int deathSaveHpRestored;
    public int energy;

    /**
     * 本回合被**外力提前结束**（{@code TimeWarpPower.onAfterUseCard}：玩家打出第 12 张牌
     * → {@code callEndTurnEarlySequence()}）。
     *
     * <p>语义是「这个局面已经不能再出牌了」：搜索侧看到它是真就必须立刻推过回合末，
     * 执行侧的回合也可能自己翻页。所以它同时进 {@link #copy()} 和 {@link #fingerprint()}。
     */
    public boolean turnForcedEnd;

    /**
     * 玩家身上的状态，**保持应用顺序**。
     *
     * <p>原版 {@code AbstractCreature.applyStartOfTurnPowers()} 是 {@code for (p : powers)}，
     * 也就是按施加顺序逐个调用钩子。用 TreeMap 会按 ID 字典序派发，把顺序语义悄悄改掉，
     * 所以这里必须是 LinkedHashMap。
     */
    public final Map<String, Integer> playerPowers = new java.util.LinkedHashMap<String, Integer>();

    /**
     * 层数之外的附属数值：{@code "TheBomb.damage" → 40}、{@code "Panache.damage" → 10}。
     *
     * <p>这两个 power 的 {@code amount} 存的是「还剩几回合 / 还能打几张」，
     * 真正的伤害在私有字段里。影子必须把那个值也捕获进来，否则算不出正确伤害，
     * 而对拍也应该比它 —— 它确实会随升级变化。
     */
    public final TreeMap<String, Integer> powerAux = new TreeMap<String, Integer>();

    public final List<SimCard> hand = new ArrayList<SimCard>();
    public final List<SimCard> draw = new ArrayList<SimCard>();
    public final List<SimCard> discard = new ArrayList<SimCard>();
    public final List<SimCard> exhaust = new ArrayList<SimCard>();
    public final TreeMap<String, Integer> relics = new TreeMap<String, Integer>();

    /**
     * 药水槽，**按槽位顺序**存药水 ID；{@code null} 表示这一格空着。
     *
     * <p>原版 {@code AbstractPlayer.potions} 就是一张固定长度的列表（铁甲战士 2 格），
     * 用掉一格就空出、捡到新的才填进去。空格本身也是状态 —— 捡到的药水没地方放。
     *
     * <p>语义见 {@code mirror/Potions}；只做了 4 张（火/格挡/能量/瓶中仙子），
     * 其余药水进 {@code use()} 会显式 {@code ActionRejected}，不静默算错。
     */
    public final List<String> potions = new java.util.ArrayList<String>();

    /** 药水槽上限（原版 {@code PotionSlot}）。 */
    public int potionSlots = io.github.youqing.sts1solver.mirror.Potions.DEFAULT_POTION_SLOTS;

    public final List<SimMonster> monsters = new ArrayList<SimMonster>();
    public final EnumMap<RngKind, RngStream> rng = new EnumMap<RngKind, RngStream>(RngKind.class);

    // ----------------------------------------------------------------- capture

    /** Main thread only: reads the live combat. */
    public static SimState capture() {
        AbstractPlayer p = AbstractDungeon.player;

        SimState s = new SimState();
        s.act = AbstractDungeon.actNum;
        s.floor = AbstractDungeon.floorNum;
        s.ascension = AbstractDungeon.ascensionLevel;
        try {
            // STS1 的 AbstractRoom 没有 roomType 字段，房间类型就是具体类；
            // 幕末 Boss 房 = MonsterRoomBoss（等价于 AbstractRoom.RoomType.BOSS 的语义）
            s.boss = AbstractDungeon.getCurrRoom()
                    instanceof com.megacrit.cardcrawl.rooms.MonsterRoomBoss;
        } catch (Throwable ignored) {
            s.boss = false;
        }
        try {
            s.turn = Math.max(1, AbstractDungeon.actionManager.turn);
        } catch (Throwable ignored) {
            s.turn = 1;
        }

        s.gold = p.gold;
        s.hp = p.currentHealth;
        s.maxHp = p.maxHealth;
        s.block = p.currentBlock;
        s.energy = EnergyPanel.totalCount;
        capturePowers(p.powers, s.playerPowers, s.powerAux);

        addCards(p.hand, s.hand);
        addCards(p.drawPile, s.draw);
        addCards(p.discardPile, s.discard);
        addCards(p.exhaustPile, s.exhaust);

        if (p.relics != null) {
            for (AbstractRelic r : p.relics) {
                s.relics.put(r.relicId, r.counter);
                // 红骷髅的「+3 力量加过了没有」是私有 isActive 布尔，快照拿不到 ——
                // 按当时的血线初始化（半血及以下 = atBattleStart 已经把 +3 加进力量里了）。
                if (io.github.youqing.sts1solver.mirror.hooks.RelicHooks.RED_SKULL
                        .equals(r.relicId)) {
                    s.relics.put(r.relicId, Integer.valueOf(
                            p.currentHealth * 2 <= p.maxHealth ? 1 : 0));
                }
                // 死灵之书的 activated 同样是私有布尔：按「已就绪」初始化
                // （atTurnStart 每回合都会置 true，所以绝大多数捕获点都该是就绪的；
                //   代价是「本回合已经用过它之后才接入」会多给一次重放）。
                if (io.github.youqing.sts1solver.mirror.hooks.RelicHooks.NECRONOMICON
                        .equals(r.relicId)) {
                    s.relics.put(r.relicId, Integer.valueOf(1));
                }
                // 战争艺术的 gainEnergyNext 同理（atTurnStart 每回合都会把它置 true）
                if (io.github.youqing.sts1solver.mirror.hooks.RelicHooks.ART_OF_WAR
                        .equals(r.relicId)) {
                    s.relics.put(r.relicId, Integer.valueOf(1));
                }
                // 蜥蜴尾巴本场是否已用：原版 AbstractPlayer.damage() 的判据是
                // `hasRelic("Lizard Tail") && getRelic("Lizard Tail").counter == -1`
                // （javap 核实，字节码 1059-1083），onTrigger() 末尾 setCounter(-2) 把它变成 -2。
                // 不读这个字段的话，每回合重新 capture 都会把「已用」重置成「还能用」，
                // 仿真会允许一场战斗里复活第二次。
                if (io.github.youqing.sts1solver.mirror.DeathSaveRelic.LIZARD_TAIL
                        .equals(r.relicId)) {
                    s.deathSaveUsed = r.counter != -1;
                    if (s.deathSaveUsed) {
                        s.deathSaveUseCount = 1;
                    }
                }
            }
        }

        // 药水槽：p.potions 是固定长度列表，空格是 null（原版 destroyPotion 会置 null）。
        // 只认 ID，不记 potency —— 4 张里的 potency 全是常量，记了反而是重复信息。
        try {
            java.util.ArrayList<com.megacrit.cardcrawl.potions.AbstractPotion> potions = p.potions;
            if (potions != null) {
                s.potionSlots = potions.size();
                for (com.megacrit.cardcrawl.potions.AbstractPotion potion : potions) {
                    s.potions.add(potion == null ? null : potion.ID);
                }
            }
        } catch (Throwable ignored) {
            // 不在战斗中/字段不可见时保持默认空槽，搜索会把「药水」当没有
        }

        MonsterGroup group = AbstractDungeon.getMonsters();
        if (group != null && group.monsters != null) {
            for (AbstractMonster m : group.monsters) {
                SimMonster monster = new SimMonster(
                        m.getClass().getSimpleName(),
                        m.currentHealth,
                        m.maxHealth,
                        m.currentBlock,
                        powersOf(m.powers));
                try {
                    monster.intentDmg = Math.max(0, m.getIntentDmg());
                } catch (Throwable ignored) {
                    monster.intentDmg = 0;
                }
                try {
                    com.megacrit.cardcrawl.monsters.AbstractMonster.Intent intent = m.intent;
                    monster.intentAttack = intent == com.megacrit.cardcrawl.monsters.AbstractMonster.Intent.ATTACK
                            || intent == com.megacrit.cardcrawl.monsters.AbstractMonster.Intent.ATTACK_BUFF
                            || intent == com.megacrit.cardcrawl.monsters.AbstractMonster.Intent.ATTACK_DEBUFF
                            || intent == com.megacrit.cardcrawl.monsters.AbstractMonster.Intent.ATTACK_DEFEND;
                } catch (Throwable ignored) {
                    monster.intentAttack = false;
                }
                // 招式历史 + 本回合的招式字节：镜像表要靠它们判断分支（原版
                // AbstractMonster.moveHistory / nextMove 都是 public 字段）
                try {
                    if (m.moveHistory != null) {
                        for (java.util.Iterator<Byte> it = m.moveHistory.iterator(); it.hasNext(); ) {
                            monster.moveHistory.add(Integer.valueOf(it.next().byteValue()));
                        }
                    }
                    monster.pendingMoveId = m.nextMove;
                } catch (Throwable ignored) {
                    // 字段不可见就保持空历史：没镜像的怪走 IntentLearning，镜像的怪按「第一招」算
                }
                // 卡面伤害（原版 damage 列表的 base）：不少怪的伤害是开战随机抽的，
                // 影子复现不了那个随机，直接读真机的值（AbstractMonster.damage 是 public）
                try {
                    if (m.damage != null) {
                        for (com.megacrit.cardcrawl.cards.DamageInfo info : m.damage) {
                            monster.damageBase.add(Integer.valueOf(info == null ? 0 : info.base));
                        }
                    }
                } catch (Throwable ignored) {
                    // 拿不到就留空：机器会退回字节码里的默认档
                }
                // InvinciblePower.maxAmt 是私有字段（powers 表只装当前剩余量）→ 反射读一份上限
                try {
                    for (com.megacrit.cardcrawl.powers.AbstractPower power : m.powers) {
                        if (power == null
                                || !"Invincible".equals(power.ID)) {
                            continue;
                        }
                        java.lang.reflect.Field maxAmt =
                                power.getClass().getDeclaredField("maxAmt");
                        maxAmt.setAccessible(true);
                        monster.invincibleMax = maxAmt.getInt(power);
                    }
                } catch (Throwable ignored) {
                    Integer current = monster.powers.get("Invincible");
                    monster.invincibleMax = current == null ? 0 : current.intValue();
                }
                s.monsters.add(monster);
            }
        }

        s.rng.put(RngKind.CARD, rngOf(AbstractDungeon.cardRandomRng));
        s.rng.put(RngKind.MONSTER, rngOf(AbstractDungeon.monsterRng));
        // 怪物血量流：`AbstractMonster.setHp(min,max)` 就是
        // `monsterHpRng.random(min,max)`（字节码直读），**召唤出来的怪**（小鬼 / TorchHead /
        // BronzeOrb）全靠它抽血。不捕获的话只能取区间中值，等于把「同 seed 可复现」丢掉。
        s.rng.put(RngKind.MONSTER_HP, rngOf(AbstractDungeon.monsterHpRng));
        s.rng.put(RngKind.AI, rngOf(AbstractDungeon.aiRng));
        s.rng.put(RngKind.SHUFFLE, rngOf(AbstractDungeon.shuffleRng));
        s.rng.put(RngKind.MISC, rngOf(AbstractDungeon.miscRng));
        // 随机生成类卡牌（结茧/发现/变形/磁力…）要按原版池子顺序取牌；
        // 顺序来自 CardLibrary 内部 HashMap 的迭代顺序，手算必错 —— 直接抓一次实机快照。
        PoolCatalog.capture();
        return s;
    }

    private static RngStream rngOf(com.megacrit.cardcrawl.random.Random rng) {
        if (rng == null) {
            return null;
        }
        return RngStream.from(rng.random.getState(0), rng.random.getState(1), rng.counter);
    }

    private static void addCards(CardGroup group, List<SimCard> into) {
        if (group == null || group.group == null) {
            return;
        }
        for (AbstractCard c : group.group) {
            // 原版把「本回合费用」放在 costForTurn 里，捕获时要一起带上，
            // 否则影子会把一张临时 0 费的牌当成永久 0 费（或反过来）
            int costForTurn = c.costForTurn != c.cost ? c.costForTurn : -1;
            into.add(new SimCard(c.cardID, c.upgraded, c.cost, c.baseDamage,
                    c.type == null ? "" : c.type.name(), costForTurn));
        }
    }

    /** 层数之外还需要额外捕获的私有字段（power ID → 字段名）。 */
    private static final java.util.Map<String, String[]> AUX_FIELDS =
            new java.util.HashMap<String, String[]>();

    static {
        // TheBombPower(owner, turns, damage) / PanachePower(owner, damage)
        AUX_FIELDS.put("TheBomb", new String[]{"damage"});
        AUX_FIELDS.put("Panache", new String[]{"damage"});
    }

    private static void capturePowers(java.util.ArrayList<AbstractPower> list,
                                      Map<String, Integer> into,
                                      Map<String, Integer> aux) {
        if (list == null) {
            return;
        }
        for (AbstractPower pw : list) {
            into.put(pw.ID, pw.amount);
            String[] fields = AUX_FIELDS.get(pw.ID);
            if (fields == null) {
                continue;
            }
            for (String field : fields) {
                Integer v = readIntField(pw, field);
                if (v != null) {
                    aux.put(pw.ID + "." + field, v);
                }
            }
        }
    }

    private static Integer readIntField(Object target, String name) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(target);
        } catch (Throwable t) {
            io.github.youqing.sts1solver.diag.Trace.warn(
                    "power aux read failed: " + target.getClass().getSimpleName() + "." + name
                            + " (" + t + ")");
            return null;
        }
    }

    private static Map<String, Integer> powersOf(java.util.ArrayList<AbstractPower> list) {
        TreeMap<String, Integer> map = new TreeMap<String, Integer>();
        if (list != null) {
            for (AbstractPower pw : list) {
                map.put(pw.ID, pw.amount);
            }
        }
        return map;
    }

    // -------------------------------------------------------------------- fork

    /** Deep copy: mutating the copy can never touch the original. */
    public SimState copy() {
        SimState c = new SimState();
        c.act = act;
        c.floor = floor;
        c.ascension = ascension;
        c.boss = boss;
        c.turn = turn;
        c.gold = gold;
        c.hp = hp;
        c.maxHp = maxHp;
        c.block = block;
        c.hpLossCount = hpLossCount;
        c.deathSaveUsed = deathSaveUsed;
        c.deathSaveUseCount = deathSaveUseCount;
        c.deathSaveHpRestored = deathSaveHpRestored;
        c.energy = energy;
        c.turnForcedEnd = turnForcedEnd;

        c.playerPowers.putAll(playerPowers);
        c.powerAux.putAll(powerAux);
        c.hand.addAll(hand);        // SimCard is immutable, sharing is safe
        c.draw.addAll(draw);
        c.discard.addAll(discard);
        c.exhaust.addAll(exhaust);
        c.relics.putAll(relics);
        c.potions.addAll(potions);
        c.potionSlots = potionSlots;

        for (SimMonster m : monsters) {
            c.monsters.add(m.copy());
        }
        for (Map.Entry<RngKind, RngStream> e : rng.entrySet()) {
            c.rng.put(e.getKey(), e.getValue() == null ? null : e.getValue().fork());
        }
        return c;
    }

    // -------------------------------------------------------------------- diff
    /**
     * Field-by-field comparison. Empty result means the two states are identical.
     * This is what the A1/A2 acceptance gates are built on.
     */
    public List<String> diff(SimState o) {
        List<String> out = new ArrayList<String>();
        cmp(out, "act", act, o.act);
        cmp(out, "floor", floor, o.floor);
        cmp(out, "ascension", ascension, o.ascension);
        cmp(out, "boss", boss, o.boss);
        cmp(out, "deathSaveUsed", deathSaveUsed, o.deathSaveUsed);
        cmp(out, "deathSaveUseCount", deathSaveUseCount, o.deathSaveUseCount);
        cmp(out, "deathSaveHpRestored", deathSaveHpRestored, o.deathSaveHpRestored);
        cmp(out, "hp", hp, o.hp);
        cmp(out, "maxHp", maxHp, o.maxHp);
        cmp(out, "block", block, o.block);
        cmp(out, "energy", energy, o.energy);

        diffMap(out, "playerPowers", playerPowers, o.playerPowers);
        diffMap(out, "powerAux", powerAux, o.powerAux);
        // 手牌顺序对策略没有意义（同名牌完全等价），按多重集比较，避免位置噪声淹没真问题
        diffHand(out, "hand", hand, o.hand);
        diffCards(out, "draw", draw, o.draw);
        diffCards(out, "discard", discard, o.discard);
        diffCards(out, "exhaust", exhaust, o.exhaust);
        diffMap(out, "relics", relics, o.relics);
        // 药水槽按槽位比（顺序有意义：空格位置决定捡到的新药水落到哪）
        if (potions.size() != o.potions.size() || potionSlots != o.potionSlots) {
            out.add("potions: " + potions + "@" + potionSlots
                    + " vs " + o.potions + "@" + o.potionSlots);
        } else {
            for (int i = 0; i < potions.size(); i++) {
                String a = potions.get(i);
                String b = o.potions.get(i);
                if (a == null ? b != null : !a.equals(b)) {
                    out.add("potions[" + i + "]: " + a + " vs " + b);
                }
            }
        }

        if (monsters.size() != o.monsters.size()) {
            out.add("monsters.size: " + monsters.size() + " vs " + o.monsters.size());
        } else {
            for (int i = 0; i < monsters.size(); i++) {
                SimMonster a = monsters.get(i);
                SimMonster b = o.monsters.get(i);
                String p = "monsters[" + i + "].";
                if (!a.type.equals(b.type)) {
                    out.add(p + "type: " + a.type + " vs " + b.type);
                }
                cmp(out, p + "hp", a.hp, b.hp);
                cmp(out, p + "maxHp", a.maxHp, b.maxHp);

                // 🔴 以前这里有一条「怪物身上有 power 就跳过 block/powers 比较」的豁免，
                // 理由是 M2 时怪物被动还没建模。M3 之后它变成了**假 MATCH 的源头**：
                // 小鬼头目（Enrage → 每张技能牌 +2 力量）这种整场靠 power 变化的怪，
                // 它的力量从 +2 涨到 +20 都不会被对拍发现，「MATCH」只说明血量对得上。
                // 2026-10-30 打开：怪物的 block 与 powers 一律参与比较。
                cmp(out, p + "block", a.block, b.block);
                diffMap(out, p + "powers", a.powers, b.powers);
            }
        }

        for (RngKind kind : RngKind.values()) {
            RngStream a = rng.get(kind);
            RngStream b = o.rng.get(kind);
            if (a == null || b == null) {
                if (a != b) {
                    out.add("rng." + kind + ": null mismatch");
                }
                continue;
            }
            if (a.state0() != b.state0() || a.state1() != b.state1() || a.counter() != b.counter()) {
                out.add("rng." + kind + ": " + a.snapshot() + " vs " + b.snapshot());
            }
        }
        return out;
    }

    private static void cmp(List<String> out, String path, int a, int b) {
        if (a != b) {
            out.add(path + ": " + a + " vs " + b);
        }
    }

    private static void cmp(List<String> out, String path, boolean a, boolean b) {
        if (a != b) {
            out.add(path + ": " + a + " vs " + b);
        }
    }

    private static void diffMap(List<String> out, String path, Map<String, Integer> a, Map<String, Integer> b) {
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            Integer other = b.get(e.getKey());
            if (other == null) {
                out.add(path + "." + e.getKey() + ": " + e.getValue() + " vs (missing)");
            } else if (!other.equals(e.getValue())) {
                out.add(path + "." + e.getKey() + ": " + e.getValue() + " vs " + other);
            }
        }
        for (String key : b.keySet()) {
            if (!a.containsKey(key)) {
                out.add(path + "." + key + ": (missing) vs " + b.get(key));
            }
        }
    }

    private static void diffCards(List<String> out, String path, List<SimCard> a, List<SimCard> b) {
        if (a.size() != b.size()) {
            out.add(path + ".size: " + a.size() + " vs " + b.size());
        }
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            if (!a.get(i).equals(b.get(i))) {
                out.add(path + "[" + i + "]: " + a.get(i) + " vs " + b.get(i));
            }
        }
    }

    /** Hand order carries no strategic meaning, so it is compared as a multiset. */
    private static void diffHand(List<String> out, String path, List<SimCard> a, List<SimCard> b) {
        List<SimCard> x = new ArrayList<SimCard>(a);
        List<SimCard> y = new ArrayList<SimCard>(b);
        java.util.Collections.sort(x, BY_KEY);
        java.util.Collections.sort(y, BY_KEY);
        diffCards(out, path + "(unordered)", x, y);
    }

    private static final java.util.Comparator<SimCard> BY_KEY = new java.util.Comparator<SimCard>() {
        @Override
        public int compare(SimCard left, SimCard right) {
            return left.key().compareTo(right.key());
        }
    };

    /**
     * Removes monsters at 0 HP. The game keeps them during the death animation, so any
     * comparison must normalize both sides first — otherwise the list length differs for
     * reasons that have nothing to do with the simulated card.
     */
    public SimState dropDeadMonsters() {
        for (int i = monsters.size() - 1; i >= 0; i--) {
            if (monsters.get(i).hp <= 0) {
                monsters.remove(i);
            }
        }
        return this;
    }

    // ------------------------------------------------------------------- output

    /** 四个牌堆的总张数。用来发现「某张牌在两侧都没了」这类丢卡问题。 */
    public int totalCards() {
        return hand.size() + draw.size() + discard.size() + exhaust.size();
    }

    /** Stable one-line summary for the overlay. */
    public String shortLine() {
        return "A" + act + " F" + floor + "  hp " + hp + "/" + maxHp
                + "  e" + energy + "  hand " + hand.size() + "  foes " + monsters.size();
    }

    /**
     * 状态指纹（64 位）—— 搜索剪枝去重 / 转置表 / 日志都用它。
     *
     * <p>🔴 2026-10-24 换成**免拼字符串**的直接哈希。原来是
     * {@code fingerprintOf(toJson())}：先拼一份完整 JSON（几百到几千字符），再转 UTF-8 字节
     * 做 FNV —— 实测 **2103 ns/次**，而剪枝对每个候选都要算一次，
     * 30 万子状态就是 0.65 s，占掉整趟搜索四成。现在直接把这些字段喂进混洗器，不再分配字符串。
     *
     * <p>**参与哈希的字段与 {@link #toJson()} 逐个对应**（口径不变）：
     * <ul>
     *   <li>{@code meta}：act / floor / ascension；</li>
     *   <li>{@code player}：hp / maxHp / block / energy / deathSaveUsed / deathSaveUseCount /
     *       deathSaveHpRestored / playerPowers / powerAux；</li>
     *   <li>四个牌堆：**只看牌名 {@code key()}**（原版 JSON 也只写 key）；</li>
     *   <li>relics / potions（空格占位）/ monsters（type/hp/maxHp/block/powers，**意图不参与**）/ rng。</li>
     * </ul>
     * 迭代顺序也与 JSON 一致（{@code powerAux} / {@code relics} 是 TreeMap 有序，
     * {@code playerPowers} 是 LinkedHashMap 的施加顺序）。
     *
     * <p>为什么能换算法：指纹只用于「这两条路线是不是同一个局面」，不与任何外部数据比对。
     * 所以只要**等价类不变**就等价于没改行为 —— 由 {@code SimStateFingerprintTest} 对拍：
     * 任意两个状态，老口径 {@code fingerprintOf(toJson())} 相等 ⟺ 新口径相等。
     *
     * <p>⚠️ 2026-10-24 补了三个字段（`turn` / `hpLossCount` / `potionSlots`）：原口径
     * （`toJson`）里没有它们，于是「同血量、挨打次数不同」的两条路线会被去重掉一条 ——
     * `hpLossCount` 决定浴血奋战的动态费用，这是**静默算错**，属于必须补的缺口。
     * 补的代价是等价类变细、去重变少、节点数略增（搜索结果可能与补之前不同）。
     * `turn` 同理（回合前进必然抽牌，实际很少撞，但撞上就是把不同回合当成同一局面）。
     */
    public String fingerprint() {
        Mix hash = new Mix();
        hash.add(act).add(floor).add(ascension);
        hash.add(turn).add(hpLossCount).add(potionSlots);
        hash.add(hp).add(maxHp).add(block).add(energy).add(turnForcedEnd ? 1 : 0);
        hash.add(deathSaveUsed ? 1 : 0).add(deathSaveUseCount).add(deathSaveHpRestored);
        hash.textMap(playerPowers);
        hash.textMap(powerAux);
        hash.cards(hand);
        hash.cards(draw);
        hash.cards(discard);
        hash.cards(exhaust);
        hash.textMap(relics);
        hash.idList(potions);
        hash.add(monsters.size());
        for (int i = 0; i < monsters.size(); i++) {
            SimMonster m = monsters.get(i);
            hash.text(m.type).add(m.hp).add(m.maxHp).add(m.block).textMap(m.powers);
        }
        for (RngKind kind : RngKind.values()) {
            RngStream stream = rng.get(kind);
            if (stream == null) {
                hash.add(-1L);
            } else {
                hash.add(1L).add(stream.counter()).add(stream.state0()).add(stream.state1());
            }
        }
        return Long.toHexString(hash.value());
    }

    /**
     * 指纹混洗器：黄金比例步进 + 两轮 xor-shift-multiply（murmur 的收官函数）。
     *
     * <p>不用裸 FNV 乘法是因为它在「值域小、结构相似」的输入上容易放大出结构性碰撞；
     * 这里每个字段先按 64 位混洗再折进 {@code state}，实测没有额外分配的代价。
     * 字符串单独走一遍 FNV（逐字符），只把结果折进来 —— 一次 {@code add} 就够。
     */
    private static final class Mix {
        private long state = 0xcbf29ce484222325L;

        long value() {
            return state;
        }

        Mix add(long value) {
            state += value * 0x9E3779B97F4A7C15L;
            state ^= state >>> 33;
            state *= 0xff51afd7ed558ccdL;
            state ^= state >>> 33;
            return this;
        }

        Mix text(String text) {
            if (text == null) {
                return add(-2L);            // 与空串区分：药水空格、null 名字都要占位
            }
            long local = 0xcbf29ce484222325L;
            for (int i = 0; i < text.length(); i++) {
                local ^= text.charAt(i);
                local *= 0x100000001b3L;
            }
            return add(local ^ (((long) text.length()) << 40));
        }

        Mix textMap(Map<String, Integer> map) {
            add(map.size());
            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                text(entry.getKey());
                add(entry.getValue().intValue());
            }
            return this;
        }

        Mix idList(List<String> ids) {
            add(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                text(ids.get(i));           // 空格（null）由 text 自己占位
            }
            return this;
        }

        Mix cards(List<SimCard> cards) {
            add(cards.size());
            for (int i = 0; i < cards.size(); i++) {
                SimCard card = cards.get(i);
                // 等价于原来的 key() = id + (upgraded ? "+" : "")：
                // 牌 ID 里不会出现 "+"（镜像表就是靠拼 "+" 查升级态的），所以 (id, upgraded) 一一对应
                text(card.id);
                add(card.upgraded ? 1 : 0);
            }
            return this;
        }
    }

    public static String fingerprintOf(String payload) {
        long hash = 0xcbf29ce484222325L;
        byte[] bytes;
        try {
            bytes = payload.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            bytes = payload.getBytes();
        }
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return Long.toHexString(hash);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n  \"meta\":{\"act\":").append(act)
          .append(",\"floor\":").append(floor)
          .append(",\"ascension\":").append(ascension).append("},\n");
        sb.append("  \"player\":{\"hp\":").append(hp)
          .append(",\"maxHp\":").append(maxHp)
          .append(",\"block\":").append(block)
          .append(",\"energy\":").append(energy)
          .append(",\"deathSaveUsed\":").append(deathSaveUsed)
          .append(",\"deathSaveUseCount\":").append(deathSaveUseCount)
          .append(",\"deathSaveHpRestored\":").append(deathSaveHpRestored)
          .append(",\"powers\":").append(mapJson(playerPowers))
          .append(",\"powerAux\":").append(mapJson(powerAux))
          .append("},\n");
        sb.append("  \"hand\":").append(cardsJson(hand)).append(",\n");
        sb.append("  \"draw\":").append(cardsJson(draw)).append(",\n");
        sb.append("  \"discard\":").append(cardsJson(discard)).append(",\n");
        sb.append("  \"exhaust\":").append(cardsJson(exhaust)).append(",\n");
        sb.append("  \"relics\":").append(mapJson(relics)).append(",\n");
        sb.append("  \"potions\":").append(listJson(potions)).append(",\n");
        sb.append("  \"monsters\":").append(monstersJson()).append(",\n");
        sb.append("  \"rng\":").append(rngJson()).append("\n}");
        return sb.toString();
    }

    private static String cardsJson(List<SimCard> cards) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SimCard card = cards.get(i);
            // 只写 id 会丢掉 cost / baseDamage / type，读回来只能猜 —— 金标准局面库要能原样重放，
            // 所以这里写成对象（老格式是纯字符串，没有别的地方解析它，改了不影响）。
            sb.append("{\"id\":\"").append(card.id)
              .append("\",\"up\":").append(card.upgraded)
              .append(",\"cost\":").append(card.cost)
              .append(",\"costForTurn\":").append(card.costForTurn)
              .append(",\"dmg\":").append(card.baseDamage)
              .append(",\"type\":\"").append(card.type == null ? "" : card.type)
              .append("\"}");
        }
        return sb.append(']').toString();
    }

    /** 药水槽：空格是 {@code null}，JSON 里也是 {@code null}。 */
    private static String listJson(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String id = items.get(i);
            if (id == null) {
                sb.append("null");
            } else {
                sb.append('"').append(id).append('"');
            }
        }
        return sb.append(']').toString();
    }

    private static String mapJson(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private String monstersJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < monsters.size(); i++) {
            SimMonster m = monsters.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"type\":\"").append(m.type)
              .append("\",\"hp\":").append(m.hp)
              .append(",\"maxHp\":").append(m.maxHp)
              .append(",\"block\":").append(m.block)
              .append(",\"intentDmg\":").append(m.intentDmg)
              .append(",\"intentAttack\":").append(m.intentAttack)
              .append(",\"pendingMoveId\":").append(m.pendingMoveId)
              .append(",\"powers\":").append(mapJson(m.powers)).append('}');
        }
        return sb.append(']').toString();
    }

    private String rngJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (RngKind kind : RngKind.values()) {
            RngStream s = rng.get(kind);
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(kind.name().toLowerCase()).append("\":");
            if (s == null) {
                sb.append("null");
            } else {
                sb.append("{\"counter\":").append(s.counter())
                  .append(",\"s0\":").append(s.state0())
                  .append(",\"s1\":").append(s.state1()).append('}');
            }
        }
        return sb.append('}').toString();
    }

    /** Writes the JSON next to the logs; returns the absolute path (or an error note). */
    public String save() {
        return saveAs("combat-" + System.currentTimeMillis() + ".json");
    }

    /**
     * 同 {@link #save()}，但用指定文件名（相对 {@code sts1solver.snapshotDir}）。
     *
     * <p>用途：**每个回合开头落一份**（{@code turn-A1F6T3.json}）。真机打臭一把之后，
     * 每个决策点都能离线重放 —— 不用再靠日志里的「PLAN steps」猜当时手里有什么牌、
     * 怪物多少血、意图多少伤害。同名文件直接覆盖（同一层同一回合的旧局面没有保留价值）。
     */
    public String saveAs(String name) {
        try {
            String dirPath = System.getProperty("sts1solver.snapshotDir", "solver-snapshots");
            File dir = new File(dirPath);
            if (!dir.exists() && !dir.mkdirs()) {
                return "save failed: cannot create " + dir.getAbsolutePath();
            }
            File f = new File(dir, name);
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try {
                w.write(toJson());
            } finally {
                w.close();
            }
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return "save failed: " + t;
        }
    }
}
