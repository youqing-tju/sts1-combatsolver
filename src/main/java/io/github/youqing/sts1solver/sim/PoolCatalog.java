package io.github.youqing.sts1solver.sim;

import java.util.ArrayList;
import java.util.List;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

/**
 * 战斗内「随机生成卡牌」用的牌池快照。
 *
 * <p>原版怎么取牌（{@code AbstractDungeon.returnTrulyRandomCardInCombat}）：
 *
 * <pre>
 * list = []
 * for (pool : [srcCommonCardPool, srcUncommonCardPool, srcRareCardPool])
 *     for (card : pool.group)
 *         if (card.type == 指定类型 &amp;&amp; !card.hasTag(CardTags.HEALING)) list.add(card);
 * return list.get(cardRandomRng.random(list.size() - 1));
 * </pre>
 *
 * <p>三个关键点，任何一个错了取到的就是别的牌：
 * <ol>
 *   <li><b>顺序</b>是 common → uncommon → rare 依次拼接，池内保持原版排列
 *       （而原版排列来自 {@code CardLibrary.cards} 这个 HashMap 的迭代顺序，手算必错）。</li>
 *   <li><b>HEALING 过滤</b>：无色里是 Bandage Up / Bite，红卡里是 Feed / Reaper。</li>
 *   <li>{@code random(size - 1)} 是闭区间，且**即使候选只有 1 张也照样消耗一次**。</li>
 * </ol>
 *
 * <p>所以这里不猜顺序：直接在战斗开始时从实机 {@code src*CardPool} 抓一次快照
 * （蓝本同样是把 root 候选集缓存一次，见 {@code CombatCardGenerationExtensions}）。
 * 抓不到时退回 {@code ColorlessPool}/{@code RedPool} 这两张由字节码生成的静态表，仅供单测使用。
 */
public final class PoolCatalog {

    /** 一个候选：够重建一张 {@link SimCard}。 */
    public static final class Entry {
        public final String id;
        public final String type;
        public final String rarity;
        public final int cost;
        public final int damage;
        public final boolean healing;

        Entry(String id, String type, String rarity, int cost, int damage, boolean healing) {
            this.id = id;
            this.type = type;
            this.rarity = rarity;
            this.cost = cost;
            this.damage = damage;
            this.healing = healing;
        }

        public SimCard toCard(int costOverride) {
            // 🔴 必须带上 type：不带的话 isAttack() 永远是 false，
            // 「本次生成的牌」在估值（攻击总量/易伤窗口）和计数遗物（修女/手里剑/开信刀）里全部算错。
            return new SimCard(id, false, costOverride < 0 ? cost : costOverride, damage, type);
        }
    }

    private static volatile boolean captured;
    /** common ++ uncommon ++ rare（拼接顺序 = 原版取牌顺序）。 */
    private static Entry[] combatCards = new Entry[0];
    private static Entry[] colorlessCards = new Entry[0];

    private PoolCatalog() {
    }

    /**
     * 从实机抓一次快照。只在主线程、战斗内调用；抓过就不再抓
     * （{@code src*CardPool} 本场不会变，蓝本同样缓存 root 候选集）。
     */
    public static void capture() {
        if (captured) {
            return;
        }
        try {
            List<Entry> combat = new ArrayList<Entry>();
            append(combat, AbstractDungeon.srcCommonCardPool);
            append(combat, AbstractDungeon.srcUncommonCardPool);
            append(combat, AbstractDungeon.srcRareCardPool);

            List<Entry> colorless = new ArrayList<Entry>();
            append(colorless, AbstractDungeon.srcColorlessCardPool);

            if (!combat.isEmpty()) {
                combatCards = combat.toArray(new Entry[0]);
                colorlessCards = colorless.toArray(new Entry[0]);
                captured = true;
            }
        } catch (Throwable ignored) {
            // 非战斗 / 池子还没建好：保持未捕获状态，调用方会退回静态表
        }
    }

    public static void reset() {
        captured = false;
        combatCards = new Entry[0];
        colorlessCards = new Entry[0];
    }

    public static boolean isCaptured() {
        return captured;
    }

    private static void append(List<Entry> into, com.megacrit.cardcrawl.cards.CardGroup group) {
        if (group == null || group.group == null) {
            return;
        }
        for (AbstractCard c : group.group) {
            into.add(new Entry(c.cardID, c.type == null ? null : c.type.name(),
                    c.rarity == null ? null : c.rarity.name(),
                    c.cost, c.baseDamage,
                    c.hasTag(AbstractCard.CardTags.HEALING)));
        }
    }

    /**
     * 等价于 {@code returnTrulyRandomCardInCombat(type)}；{@code type == null} 表示不限类型。
     * 每次都重建候选列表 —— 原版也是每次重建，长度参与 RNG 取值。
     */
    public static SimCard randomCombatCard(SimState state, String type) {
        List<Entry> candidates = new ArrayList<Entry>();
        for (Entry e : combatSource()) {
            if (e.healing) {
                continue;
            }
            if (type != null && !type.equals(e.type)) {
                continue;
            }
            candidates.add(e);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        RngStream rng = state.rng.get(SimState.RngKind.CARD);
        int pick = rng == null ? 0 : rng.random(candidates.size() - 1);
        if (pick >= candidates.size()) {
            pick = candidates.size() - 1;
        }
        Entry chosen = candidates.get(pick);
        // 🔴 必须带 type：随机生成的牌（发现/三选一药水/变形等）如果 type 为空，
        // 估值里的能力牌计数、攻击总量、牌库杂质全会算错。
        return new SimCard(chosen.id, false, chosen.cost, chosen.damage, chosen.type);
    }

    /** 等价于 {@code returnTrulyRandomColorlessCardInCombat()}。 */
    public static SimCard randomColorlessCard(SimState state, int costOverride) {
        List<Entry> candidates = new ArrayList<Entry>();
        for (Entry e : colorlessSource()) {
            if (!e.healing) {
                candidates.add(e);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        RngStream rng = state.rng.get(SimState.RngKind.CARD);
        int pick = rng == null ? 0 : rng.random(candidates.size() - 1);
        if (pick >= candidates.size()) {
            pick = candidates.size() - 1;
        }
        Entry chosen = candidates.get(pick);
        return chosen.toCard(costOverride);
    }

    // ------------------------------------------------------------ 数据来源

    private static Entry[] combatSource() {
        if (captured) {
            return combatCards;
        }
        return fallbackCombat();
    }

    private static Entry[] colorlessSource() {
        if (captured) {
            return colorlessCards;
        }
        return fallbackColorless();
    }

    private static volatile Entry[] fallbackCombatCache;
    private static volatile Entry[] fallbackColorlessCache;

    private static Entry[] fallbackCombat() {
        Entry[] cached = fallbackCombatCache;
        if (cached != null) {
            return cached;
        }
        List<Entry> list = new ArrayList<Entry>();
        String[] order = {"COMMON", "UNCOMMON", "RARE"};
        for (String rarity : order) {
            for (int i = 0; i < io.github.youqing.sts1solver.mirror.ColorlessPool.size(); i++) {
                if (rarity.equals(io.github.youqing.sts1solver.mirror.ColorlessPool.rarityAt(i))) {
                    list.add(entryOfColorless(i));
                }
            }
            for (int i = 0; i < io.github.youqing.sts1solver.mirror.RedPool.size(); i++) {
                if (rarity.equals(io.github.youqing.sts1solver.mirror.RedPool.rarityAt(i))) {
                    list.add(entryOfRed(i));
                }
            }
        }
        cached = list.toArray(new Entry[0]);
        fallbackCombatCache = cached;
        return cached;
    }

    private static Entry[] fallbackColorless() {
        Entry[] cached = fallbackColorlessCache;
        if (cached != null) {
            return cached;
        }
        List<Entry> list = new ArrayList<Entry>();
        for (int i = 0; i < io.github.youqing.sts1solver.mirror.ColorlessPool.size(); i++) {
            list.add(entryOfColorless(i));
        }
        cached = list.toArray(new Entry[0]);
        fallbackColorlessCache = cached;
        return cached;
    }

    private static Entry entryOfColorless(int i) {
        return new Entry(io.github.youqing.sts1solver.mirror.ColorlessPool.idAt(i),
                io.github.youqing.sts1solver.mirror.ColorlessPool.typeAt(i),
                io.github.youqing.sts1solver.mirror.ColorlessPool.rarityAt(i),
                io.github.youqing.sts1solver.mirror.ColorlessPool.costAt(i),
                io.github.youqing.sts1solver.mirror.ColorlessPool.damageAt(i),
                false);
    }

    private static Entry entryOfRed(int i) {
        return new Entry(io.github.youqing.sts1solver.mirror.RedPool.idAt(i),
                io.github.youqing.sts1solver.mirror.RedPool.typeAt(i),
                io.github.youqing.sts1solver.mirror.RedPool.rarityAt(i),
                io.github.youqing.sts1solver.mirror.RedPool.costAt(i),
                io.github.youqing.sts1solver.mirror.RedPool.damageAt(i),
                false);
    }
}
