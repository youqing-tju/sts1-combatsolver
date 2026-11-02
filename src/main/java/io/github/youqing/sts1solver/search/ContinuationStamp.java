package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨回合续用路线用的**精确状态文本**（蓝本 {@code src/Runtime/ContinuationStamp.cs:14-135}）。
 *
 * <p>它不是哈希，也不是 {@link SimState#fingerprint()}：那个 64 位哈希刻意不含意图、怪物招式
 * 和 pendingMove 载荷（它是给剪枝去重用的，越粗越好）。续跑要反过来 —— **只有逐字段相等才许复用**，
 * 所以这里把「会改变推演结果」的字段全部展开成 {@code 路径=值} 的规范化文本。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>两侧各自一个入口</b>：{@link #captureLive}（真机）与 {@link #capturePredicted}（搜索）。
 *       蓝本两侧是两套对象模型（{@code CardModel}/{@code Creature} 对 {@code PredictedCard}/
 *       {@code SimCreatureState}），所以它必须写两份；我们的 {@code SimState.capture()} 直接产出
 *       纯数据，与搜索侧同一个类型，于是两侧共用同一套字段 writer —— 这是**翻译方式**不同，
 *       不是归一化口径不同。入口分开是为了让调用点无法含糊：谁在真机侧、谁在预测侧一目了然；</li>
 *   <li>**四个牌堆全部按列表原顺序**（含手牌）。蓝本 {@code AppendLivePile} / {@code AppendPredictedPile}
 *       都是 {@code foreach (card in pile.Cards)}，手牌顺序会改变后续能打出的连招与抽牌结果；</li>
 *   <li>每个字段一条独立路径，差异诊断才能指名到 {@code drawPile[3].upgraded} 这一级，
 *       而不是「整个牌堆不一样了」。</li>
 * </ol>
 *
 * <p>⚠️ {@code SimCard.equals()} 只比 {@code id/upgraded/cost}，本类刻意把
 * {@code baseDamage/type/costForTurn} 也拆成独立字段 —— 否则「同一张牌、不同卡面伤害」
 * 会被当成同一局面放行（那是静默算错，不是省事）。
 */
public final class ContinuationStamp {

    /**
     * 文本版本号。任何字段口径变化都必须改它，老 continuation 会因此全部失效（安全方向）。
     *
     * <p>v2：手牌改成**保序**（v1 是排序后的无序多重集），与蓝本 {@code AppendLivePile} 一致。
     * <p>v3：怪物多一个 {@code stolenGold}（抢錢怪手里还攥着多少金币）——
     * 它现在参与最终排序，必须逐字段比对。
     * <p>v4：成长/长期资源/Anger 复制数（最终排序第 6/7/11/12 键）——
     * 与 {@code stolenGold} 同一个理由：只差这些字段的两份状态会选出不同路线。
     * <p>v5：**去掉**累计掉血/累计回血。真机侧没有对应物（原版只有 `damagedThisCombat`
     * 这个「次数」计数），预测侧有值而真机侧恒 0 —— 带进戳就等于「永远不命中」。
     */
    public static final String VERSION = "sts1-cont-v5";

    private static final char FIELD_SEP = ';';
    private static final char PAIR_SEP = '=';
    private static final String NULL_TOKEN = "~";

    private final String canonicalText;
    /** 字段路径，按写入顺序；相等与差异诊断共用这一份顺序。 */
    private final List<String> paths;
    private final Map<String, String> values;
    private final int hashCode;

    private ContinuationStamp(LinkedHashMap<String, String> fields) {
        this.paths = new ArrayList<String>(fields.size());
        this.values = new LinkedHashMap<String, String>(fields.size() * 2);
        StringBuilder sb = new StringBuilder(fields.size() * 24);
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) {
                sb.append(FIELD_SEP);
            }
            sb.append(e.getKey()).append(PAIR_SEP).append(e.getValue());
            paths.add(e.getKey());
            values.put(e.getKey(), e.getValue());
        }
        this.canonicalText = sb.toString();
        this.hashCode = canonicalText.hashCode();
    }

    /**
     * 真机侧捕获（对应蓝本 {@code ContinuationStamp.CaptureLive}）。
     *
     * <p>入参必须是 {@code SimState.capture()} 直接从游戏里读出来的状态；回合开始比对
     * {@code ContinuationSource} 时就该用这一侧。
     */
    public static ContinuationStamp captureLive(SimState live) {
        return build(live);
    }

    /**
     * 预测侧捕获（对应蓝本 {@code ContinuationStamp.CapturePredicted}）。
     *
     * <p>入参必须是**回放最终路线时**的那一刻的模拟状态（{@code ContinuationPlan.Capture.observe}），
     * 不是节点链上的历史快照。
     */
    public static ContinuationStamp capturePredicted(SimState predicted) {
        return build(predicted);
    }

    /** 两侧共用的字段 writer（见类注释：同一个 {@code SimState} 类型，所以只有一套口径）。 */
    private static ContinuationStamp build(SimState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        Fields f = new Fields();
        f.add("v", VERSION);

        // ---- 回合与玩家资源（顺序固定，差异列表按它输出）------------------------
        f.num("turn", state.turn);
        f.num("hp", state.hp);
        f.num("maxHp", state.maxHp);
        f.num("block", state.block);
        f.num("energy", state.energy);
        f.num("energyPerTurn", state.energyPerTurn);
        f.num("hpLossCount", state.hpLossCount);
        f.add("deathSaveUsed", state.deathSaveUsed);
        f.num("deathSaveUseCount", state.deathSaveUseCount);
        f.num("deathSaveHpRestored", state.deathSaveHpRestored);
        f.add("turnForcedEnd", state.turnForcedEnd);
        f.num("potionSlots", state.potionSlots);
        f.num("act", state.act);
        f.num("floor", state.floor);
        f.num("ascension", state.ascension);
        f.add("boss", state.boss);
        f.num("gold", state.gold);
        f.num("feedGrowth", state.feedGrowth);
        f.num("handOfGreedGrowth", state.handOfGreedGrowth);
        f.num("longTermResourceValue", state.longTermResourceValue);
        f.num("angerCopiesGenerated", state.angerCopiesGenerated);

        // ---- 玩家能力 ----------------------------------------------------------
        // 保持**施加顺序**：原版 applyStartOfTurnPowers 是按列表顺序派发钩子的，
        // 排成字典序会把「先加力量再加虚弱」和「先虚弱再加力量」当成同一局面。
        f.num("playerPowers.size", state.playerPowers.size());
        int powerIndex = 0;
        for (Map.Entry<String, Integer> e : state.playerPowers.entrySet()) {
            f.add("playerPowers[" + powerIndex + "].id", e.getKey());
            f.num("playerPowers[" + powerIndex + "].amount", e.getValue().intValue());
            powerIndex++;
        }
        f.num("powerAux.size", state.powerAux.size());
        for (Map.Entry<String, Integer> e : state.powerAux.entrySet()) {
            f.num("powerAux." + e.getKey(), e.getValue().intValue());
        }

        // ---- 四个牌堆（全部保序；手牌顺序会改变连招与抽牌结果）------------------
        f.pile("hand", state.hand);
        f.pile("drawPile", state.draw);
        f.pile("discardPile", state.discard);
        f.pile("exhaustPile", state.exhaust);

        // ---- 遗物 --------------------------------------------------------------
        f.num("relics.size", state.relics.size());
        for (Map.Entry<String, Integer> e : state.relics.entrySet()) {
            f.num("relics." + e.getKey(), e.getValue().intValue());
        }

        // ---- 药水（按槽位；空槽是状态，位置决定捡到的新药水落哪）---------------
        f.num("potions.size", state.potions.size());
        for (int i = 0; i < state.potions.size(); i++) {
            f.add("potions[" + i + "]", state.potions.get(i));
        }

        // ---- 怪物 --------------------------------------------------------------
        f.num("monsters.size", state.monsters.size());
        for (int i = 0; i < state.monsters.size(); i++) {
            f.monster(i, state.monsters.get(i));
        }

        // ---- 五路 RNG（少一路就等于放弃精确复现）--------------------------------
        for (SimState.RngKind kind : SimState.RngKind.values()) {
            f.rng(rngName(kind), state.rng.get(kind));
        }
        return new ContinuationStamp(f.map);
    }

    public String canonicalText() {
        return canonicalText;
    }

    /** 首个字段差异（含字段路径与双方取值）；完全相同返回 {@code "none"}。 */
    public String describeFirstDifference(ContinuationStamp actual) {
        List<String> all = describeDifferences(actual, 1);
        return all.isEmpty() ? "none" : all.get(0);
    }

    /**
     * 逐字段差异，最多 {@code limit} 条。
     *
     * <p>比较的是**同一张字段表**（{@link #from} 写出来的那一份），所以不存在
     * 「相等判定和诊断口径不一致」这种经典坑。
     */
    public List<String> describeDifferences(ContinuationStamp actual, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (actual == null) {
            List<String> only = new ArrayList<String>(1);
            only.add("stamp: expected=" + abbreviate(canonicalText) + " actual=(missing)");
            return only;
        }
        List<String> out = new ArrayList<String>();
        for (String path : paths) {
            String expected = values.get(path);
            if (!actual.values.containsKey(path)) {
                out.add(path + ": expected=" + display(expected) + " actual=(missing)");
            } else {
                String got = actual.values.get(path);
                if (!expected.equals(got)) {
                    out.add(path + ": expected=" + display(expected)
                            + " actual=" + display(got));
                }
            }
            if (out.size() >= limit) {
                return out;
            }
        }
        for (String path : actual.paths) {
            if (values.containsKey(path)) {
                continue;
            }
            out.add(path + ": expected=(missing) actual=" + display(actual.values.get(path)));
            if (out.size() >= limit) {
                return out;
            }
        }
        return out;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ContinuationStamp)) {
            return false;
        }
        return canonicalText.equals(((ContinuationStamp) other).canonicalText);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return abbreviate(canonicalText);
    }

    // ------------------------------------------------------------------ encoding

    /** 字段表：路径唯一，写入顺序即差异输出顺序。 */
    private static final class Fields {
        final LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();

        void add(String path, String value) {
            map.put(path, value == null ? NULL_TOKEN : escape(value));
        }

        void add(String path, boolean value) {
            map.put(path, value ? "true" : "false");
        }

        void num(String path, int value) {
            map.put(path, Integer.toString(value));
        }

        void num(String path, long value) {
            map.put(path, Long.toString(value));
        }

        void pile(String name, List<SimCard> cards) {
            num(name + ".size", cards.size());
            for (int i = 0; i < cards.size(); i++) {
                SimCard c = cards.get(i);
                String prefix = name + "[" + i + "]";
                add(prefix + ".id", c.id);
                add(prefix + ".upgraded", c.upgraded);
                num(prefix + ".cost", c.cost);
                num(prefix + ".costForTurn", c.costForTurn);
                num(prefix + ".baseDamage", c.baseDamage);
                add(prefix + ".type", c.type);
            }
        }

        void monster(int index, SimMonster m) {
            String p = "monsters[" + index + "]";
            add(p + ".type", m.type);
            num(p + ".hp", m.hp);
            num(p + ".maxHp", m.maxHp);
            num(p + ".block", m.block);
            num(p + ".invincibleMax", m.invincibleMax);
            num(p + ".pendingMoveId", m.pendingMoveId);
            num(p + ".intentDmg", m.intentDmg);
            num(p + ".stolenGold", m.stolenGold);
            add(p + ".intentAttack", m.intentAttack);

            num(p + ".moveHistory.size", m.moveHistory.size());
            for (int i = 0; i < m.moveHistory.size(); i++) {
                num(p + ".moveHistory[" + i + "]", m.moveHistory.get(i).intValue());
            }
            num(p + ".damageBase.size", m.damageBase.size());
            for (int i = 0; i < m.damageBase.size(); i++) {
                num(p + ".damageBase[" + i + "]", m.damageBase.get(i).intValue());
            }
            num(p + ".powers.size", m.powers.size());
            for (Map.Entry<String, Integer> e : m.powers.entrySet()) {
                num(p + ".powers." + e.getKey(), e.getValue().intValue());
            }
            move(p + ".pendingMove", m.pendingMove);
        }

        /**
         * 招式载荷：比的是**语义字段**，不是对象身份。
         *
         * <p>实机与预测的 {@link MonsterMove} 是两份不同对象，但由同一套表生成；
         * 只要语义相同，这里就应当得到同一段文本。
         */
        void move(String path, MonsterMove move) {
            if (move == null) {
                map.put(path, NULL_TOKEN);
                return;
            }
            num(path + ".id", move.id);
            num(path + ".damage", move.damage);
            num(path + ".hits", move.hits);
            num(path + ".block", move.block);
            num(path + ".blockRandomAlly", move.blockRandomAlly);
            add(path + ".selfBuff", move.selfBuff);
            num(path + ".selfBuffAmount", move.selfBuffAmount);
            add(path + ".playerDebuff", move.playerDebuff);
            num(path + ".playerDebuffAmount", move.playerDebuffAmount);
            add(path + ".playerDebuff2", move.playerDebuff2);
            num(path + ".playerDebuff2Amount", move.playerDebuff2Amount);
            add(path + ".statusCard", move.statusCard);
            num(path + ".statusCount", move.statusCount);
            add(path + ".splitInto", move.splitInto);
            num(path + ".splitCount", move.splitCount);
            add(path + ".splitInto2", move.splitInto2);
            num(path + ".splitCount2", move.splitCount2);
            add(path + ".escape", move.escape);
            add(path + ".vampire", move.vampire);
            num(path + ".healAllies", move.healAllies);
            add(path + ".buffAlliesPower", move.buffAlliesPower);
            num(path + ".buffAlliesAmount", move.buffAlliesAmount);
            extra(path + ".extra", move.extra);
        }

        void extra(String path, MonsterMove.Extra extra) {
            if (extra == null) {
                map.put(path, NULL_TOKEN);
                return;
            }
            if (extra.summon == null) {
                map.put(path + ".summon", NULL_TOKEN);
            } else {
                num(path + ".summon.count", extra.summon.count);
                num(path + ".summon.cap", extra.summon.cap);
                add(path + ".summon.minion", extra.summon.minion);
                num(path + ".summon.pool.size", extra.summon.pool.length);
                for (int i = 0; i < extra.summon.pool.length; i++) {
                    MonsterMove.Spawn spawn = extra.summon.pool[i];
                    String sp = path + ".summon.pool[" + i + "]";
                    add(sp + ".type", spawn.type);
                    num(sp + ".hpMin", spawn.hpMin);
                    num(sp + ".hpMax", spawn.hpMax);
                }
            }
            add(path + ".blockAllyType", extra.blockAllyType);
            num(path + ".blockAllyTypeAmount", extra.blockAllyTypeAmount);
            num(path + ".blockAllies", extra.blockAllies);
            add(path + ".playerDebuff3", extra.playerDebuff3);
            num(path + ".playerDebuff3Amount", extra.playerDebuff3Amount);
            add(path + ".cleanseSelf", extra.cleanseSelf);
            add(path + ".statusDrawCard", extra.statusDrawCard);
            num(path + ".statusDrawCount", extra.statusDrawCount);
            add(path + ".selfDestruct", extra.selfDestruct);
            add(path + ".absentBuff", extra.absentBuff);
            num(path + ".absentBuffAmount", extra.absentBuffAmount);
            add(path + ".reincarnate", extra.reincarnate);
            add(path + ".blockFromDamage", extra.blockFromDamage);
            num(path + ".drawPileCards.size", extra.drawPileCards.length);
            for (int i = 0; i < extra.drawPileCards.length; i++) {
                add(path + ".drawPileCards[" + i + "]", extra.drawPileCards[i]);
            }
            add(path + ".extraSelfBuff", extra.extraSelfBuff);
            num(path + ".extraSelfBuffAmount", extra.extraSelfBuffAmount);
        }

        void rng(String name, RngStream stream) {
            if (stream == null) {
                map.put(name, NULL_TOKEN);
                return;
            }
            num(name + ".counter", stream.counter());
            num(name + ".state0", stream.state0());
            num(name + ".state1", stream.state1());
        }
    }

    private static int cmp(String a, String b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        return b == null ? 1 : a.compareTo(b);
    }

    /**
     * 值编码：{@code \} / {@code ;} / {@code ~} 一律转义，{@code null} 用 {@code ~}。
     *
     * <p>为什么要转义而不是「反正 ID 里不会有分号」：这张表是**相等判定的唯一依据**，
     * 一个没转义的分号就能把两个不同状态写成同一段文本 —— 那正是「静默复用错误世界线」。
     */
    private static String escape(String raw) {
        StringBuilder sb = null;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '\\' || ch == FIELD_SEP || ch == '~') {
                if (sb == null) {
                    sb = new StringBuilder(raw.length() + 8);
                    sb.append(raw, 0, i);
                }
                sb.append('\\').append(ch);
            } else if (sb != null) {
                sb.append(ch);
            }
        }
        return sb == null ? raw : sb.toString();
    }

    private static String display(String encoded) {
        if (encoded == null) {
            return "(missing)";
        }
        if (NULL_TOKEN.equals(encoded)) {
            return "null";
        }
        if (encoded.indexOf('\\') < 0) {
            return encoded;
        }
        StringBuilder sb = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char ch = encoded.charAt(i);
            if (ch == '\\' && i + 1 < encoded.length()) {
                sb.append(encoded.charAt(++i));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String abbreviate(String text) {
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }

    /** RNG 流名：与设计/日志里的叫法一致（{@code aiRng.counter}）。 */
    private static String rngName(SimState.RngKind kind) {
        switch (kind) {
            case CARD:
                return "cardRng";
            case MONSTER:
                return "monsterRng";
            case MONSTER_HP:
                return "monsterHpRng";
            case AI:
                return "aiRng";
            case SHUFFLE:
                return "shuffleRng";
            case MISC:
                return "miscRng";
            default:
                return kind.name().toLowerCase(java.util.Locale.ROOT) + "Rng";
        }
    }
}
