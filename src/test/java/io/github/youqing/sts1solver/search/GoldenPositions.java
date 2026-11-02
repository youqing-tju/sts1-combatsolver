package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.sim.MonsterMove;
import io.github.youqing.sts1solver.sim.RngStream;
import io.github.youqing.sts1solver.sim.SimCard;
import io.github.youqing.sts1solver.sim.SimMonster;
import io.github.youqing.sts1solver.sim.SimState;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「金标准局面库」的读取器：把真机 / 回放写出来的 {@code combat-*.json} 读回 {@link SimState}，
 * 以及把 {@code mismatch/*.json} 的三态读成 {@link MismatchCase}。
 *
 * <p>为什么需要它：用户抱怨「牌打得臭」时，我们只有日志里的文字，没有可复现的局面 ——
 * 于是任何改动都只能靠感觉判断好坏。有了这个读取器，真机拍到的局面可以直接变成测试用例：
 * 同一份局面、同一份配置，改前改后各跑一次，谁的第一手更好一目了然。
 *
 * <p>格式由 {@link SimState#toJson()} 定义。
 *
 * <p>🔴 **两档严格度，用途不同，绝不能互相冒充**：
 * <ul>
 *   <li>{@link #read}（legacy）：老快照缺 v5 字段时给「蓝本空账本」默认值。
 *       只服务路线质量 A/B —— 那 173 个文件是 11-02 早些时候拍的，本来就没有 turn/意图载荷；</li>
 *   <li>{@link #readStrict}（严格）：缺任何 {@link ContinuationStamp} 会比较的字段直接失败。
 *       续跑配对与失配回放走这一档 —— 用默认值补齐等于伪造一个「看起来一样」的局面，
 *       那正是「命中率永远 0 却查不出为什么」的来源。</li>
 * </ul>
 *
 * <p>本类**只有一个 JSON 解析器**（{@link Parser}）：快照、失配用例、旧格式都走它，
 * 不复制第二套。
 */
final class GoldenPositions {

    private GoldenPositions() {
    }

    /** 老快照读取：缺字段按蓝本空账本默认，**不得**用于续跑配对。 */
    static SimState read(File file) throws Exception {
        return read(file, false);
    }

    /** 严格读取：缺 {@code ContinuationStamp} 所需任一字段即失败。 */
    static SimState readStrict(File file) throws Exception {
        return read(file, true);
    }

    private static SimState read(File file, boolean strict) throws Exception {
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Map<String, Object> root = rootObject(json, file);
        return toState(root, file.getName(), strict);
    }

    /**
     * 读一份 MISMATCH DUMP：外壳（打的是哪张牌 / 目标 / 选择 / 差异）+ 三态。
     *
     * <p>三态都是**严格**读取：失配回放要断言「从 before 重放后等于 live」，
     * 任何一侧补默认值都会让结论变成假的。
     *
     * <p>旧 dump（本轮之前）没有 {@code forced} 字段，也没有 v5 字段 ——
     * 这时 {@link #toState} 会直接抛，调用方据此把它们单列为 {@code incomplete_schema}，
     * 不能算进「修复后 diff=0」的分母。
     */
    static MismatchCase readMismatch(File file) throws Exception {
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Map<String, Object> root = rootObject(json, file);
        String name = file.getName();

        MismatchCase out = new MismatchCase();
        out.file = file;
        out.card = strOf(root, "card");
        out.target = root.containsKey("target") ? intOf(root, "target") : -1;
        out.forced = root.containsKey("forced") && boolOf(root, "forced");
        Object choices = root.get("choices");
        if (choices != null) {
            for (Object item : asList(choices)) {
                if (item instanceof String) {
                    // 旧格式：只有牌名，下标与来源牌堆都丢了 —— 只能表达「选了这张」。
                    out.choices.add(new Choice((String) item, -1, null));
                } else if (item instanceof Map) {
                    Map<String, Object> c = asMap(item);
                    String key = c.containsKey("key") && c.get("key") != null
                            ? strOf(c, "key") : strOf(c, "id");
                    out.choices.add(new Choice(key,
                            c.containsKey("index") ? intOf(c, "index") : -1,
                            c.containsKey("pile") && c.get("pile") != null
                                    ? strOf(c, "pile") : null));
                }
            }
        }
        Object diffs = root.get("diffs");
        if (diffs != null) {
            for (Object item : asList(diffs)) {
                out.diffs.add(String.valueOf(item));
            }
        }
        out.before = toState(asMap(root.get("before")), name + "#before", true);
        out.sim = toState(asMap(root.get("sim")), name + "#sim", true);
        out.live = toState(asMap(root.get("live")), name + "#live", true);
        return out;
    }

    /** 一次失配的完整证据。 */
    static final class MismatchCase {
        public File file;
        public String card;
        public int target;
        public boolean forced;
        public final List<Choice> choices = new ArrayList<Choice>();
        public final List<String> diffs = new ArrayList<String>();
        public SimState before;
        public SimState sim;
        public SimState live;

        @Override
        public String toString() {
            return file.getName() + " card=" + card + " target=" + target
                    + " forced=" + forced + " choices=" + choices.size()
                    + " diffs=" + diffs;
        }
    }

    /**
     * 一次选牌：牌名 + 当时下标 + 来源牌堆。
     *
     * <p>{@code pile} 是原版选择来源（{@code HAND} / {@code DRAW} / {@code DISCARD} /
     * {@code EXHAUST}）；旧 dump 没有这个信息，只能是 {@code null} + {@code index=-1}。
     * 这不是「默认手牌」，而是「不知道」—— 回放时必须显式处理，不许猜。
     */
    static final class Choice {
        public final String key;
        public final int index;
        public final String pile;

        Choice(String key, int index, String pile) {
            this.key = key;
            this.index = index;
            this.pile = pile;
        }

        @Override
        public String toString() {
            return (pile == null ? "?" : pile) + "[" + index + "]=" + key;
        }
    }

    /** 生成一份可读的一行摘要（测试报告用）。 */
    static String describe(SimState s) {
        StringBuilder sb = new StringBuilder();
        sb.append("A").append(s.act).append(" F").append(s.floor)
          .append("  hp ").append(s.hp).append('/').append(s.maxHp)
          .append("  e").append(s.energy)
          .append("  hand ").append(s.hand.size())
          .append("  foes ").append(s.monsters.size());
        return sb.toString();
    }

    // ------------------------------------------------------------------ JSON → SimState

    private static Map<String, Object> rootObject(String json, File file) {
        Object root = new Parser(json).parseValue();
        if (!(root instanceof Map)) {
            throw new IllegalStateException("golden file is not a JSON object: " + file);
        }
        return asMap(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    private static int intOf(Map<String, Object> m, String key) {
        return (int) longOf(m, key);
    }

    /** 整数按 {@code Long} 解析（RNG 的 s0/s1 是 64 位，走 double 会丢精度）。 */
    private static long longOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Long) {
            return ((Long) v).longValue();
        }
        if (v instanceof Double) {
            return ((Double) v).longValue();
        }
        throw new IllegalStateException("golden field missing or not a number: " + key);
    }

    /**
     * 取一个整数字段。
     *
     * <p>{@code strict} 时缺失直接抛（并指名路径）；legacy 时回 {@code legacyDefault}。
     * 默认值只能表达「蓝本里的空账本」，**不许**用它猜一个真机观测值。
     */
    private static int intField(Map<String, Object> m, String key, int legacyDefault,
                                boolean strict, String path) {
        if (m == null || !m.containsKey(key) || m.get(key) == null) {
            if (strict) {
                throw new IllegalStateException("strict snapshot missing field: " + path + "." + key);
            }
            return legacyDefault;
        }
        Object v = m.get(key);
        if (v instanceof Long) {
            return ((Long) v).intValue();
        }
        if (v instanceof Double) {
            return ((Double) v).intValue();
        }
        throw new IllegalStateException("golden field not a number: " + path + "." + key);
    }

    private static boolean boolField(Map<String, Object> m, String key, boolean legacyDefault,
                                     boolean strict, String path) {
        if (m == null || !m.containsKey(key) || m.get(key) == null) {
            if (strict) {
                throw new IllegalStateException("strict snapshot missing field: " + path + "." + key);
            }
            return legacyDefault;
        }
        Object v = m.get(key);
        if (!(v instanceof Boolean)) {
            throw new IllegalStateException("golden field not a bool: " + path + "." + key);
        }
        return ((Boolean) v).booleanValue();
    }

    private static boolean boolOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Boolean)) {
            throw new IllegalStateException("golden field missing or not a bool: " + key);
        }
        return ((Boolean) v).booleanValue();
    }

    private static String strOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String)) {
            throw new IllegalStateException("golden field missing or not a string: " + key);
        }
        return (String) v;
    }

    /** 可空字符串：JSON {@code null} 读成 null（招式里大部分加成为 null）。 */
    private static String strOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String)) {
            throw new IllegalStateException("golden field not a string: " + key);
        }
        return (String) v;
    }

    private static Map<String, Integer> powersOf(Object o) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        if (o == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : asMap(o).entrySet()) {
            Object v = e.getValue();
            out.put(e.getKey(), Integer.valueOf(v instanceof Long
                    ? ((Long) v).intValue() : ((Double) v).intValue()));
        }
        return out;
    }

    private static List<SimCard> cardsOf(Object o) {
        List<SimCard> out = new ArrayList<SimCard>();
        for (Object item : asList(o)) {
            Map<String, Object> c = asMap(item);
            out.add(new SimCard(strOf(c, "id"), boolOf(c, "up"), intOf(c, "cost"),
                    intOf(c, "dmg"), strOf(c, "type"), intOf(c, "costForTurn")));
        }
        return out;
    }

    private static List<Integer> intsOf(Object o) {
        List<Integer> out = new ArrayList<Integer>();
        if (o == null) {
            return out;
        }
        for (Object item : asList(o)) {
            out.add(Integer.valueOf(item instanceof Long
                    ? ((Long) item).intValue() : ((Double) item).intValue()));
        }
        return out;
    }

    private static SimState toState(Map<String, Object> root, String name, boolean strict) {
        SimState s = new SimState();
        Map<String, Object> meta = asMap(root.get("meta"));
        s.act = intField(meta, "act", 0, strict, "meta");
        s.floor = intField(meta, "floor", 0, strict, "meta");
        s.ascension = intField(meta, "ascension", 0, strict, "meta");
        // 老快照没有这个字段（读到 false = 按普通房处理，与当时的行为一致）
        s.boss = boolField(meta, "boss", false, strict, "meta");

        Map<String, Object> p = asMap(root.get("player"));
        s.hp = intField(p, "hp", 0, strict, "player");
        s.maxHp = intField(p, "maxHp", 0, strict, "player");
        s.block = intField(p, "block", 0, strict, "player");
        s.energy = intField(p, "energy", 0, strict, "player");
        // 老快照没有这个字段（读到 0 = 没捕获到，endTurn 会退回 energy）
        s.energyPerTurn = intField(p, "energyPerTurn", 0, strict, "player");
        s.turn = intField(p, "turn", 1, strict, "player");
        s.hpLossCount = intField(p, "hpLossCount", 0, strict, "player");
        s.turnForcedEnd = boolField(p, "turnForcedEnd", false, strict, "player");
        s.deathSaveUsed = boolField(p, "deathSaveUsed", false, strict, "player");
        s.deathSaveUseCount = intField(p, "deathSaveUseCount", 0, strict, "player");
        s.deathSaveHpRestored = intField(p, "deathSaveHpRestored", 0, strict, "player");
        // 老快照没有这几个字段：默认值只能是蓝本的**空账本**（0），不许猜。
        s.gold = intField(p, "gold", 0, strict, "player");
        s.feedGrowth = intField(p, "feedGrowth", 0, strict, "player");
        s.handOfGreedGrowth = intField(p, "handOfGreedGrowth", 0, strict, "player");
        s.longTermResourceValue = intField(p, "longTermResourceValue", 0, strict, "player");
        s.angerCopiesGenerated = intField(p, "angerCopiesGenerated", 0, strict, "player");
        s.cumulativeHpLost = intField(p, "cumulativeHpLost", 0, strict, "player");
        s.recoveredPlayerHp = intField(p, "recoveredPlayerHp", 0, strict, "player");
        s.playerPowers.putAll(powersOf(p.get("powers")));
        s.powerAux.putAll(powersOf(p.get("powerAux")));

        s.hand.addAll(cardsOf(root.get("hand")));
        s.draw.addAll(cardsOf(root.get("draw")));
        s.discard.addAll(cardsOf(root.get("discard")));
        s.exhaust.addAll(cardsOf(root.get("exhaust")));
        s.relics.putAll(powersOf(root.get("relics")));

        for (Object item : asList(root.get("potions"))) {
            s.potions.add(item == null ? null : (String) item);
        }
        s.potionSlots = intField(root, "potionSlots", s.potions.size(), strict, "$");

        for (Object item : asList(root.get("monsters"))) {
            Map<String, Object> m = asMap(item);
            SimMonster monster = new SimMonster(strOf(m, "type"),
                    intField(m, "hp", 0, strict, "monster"),
                    intField(m, "maxHp", 0, strict, "monster"),
                    intField(m, "block", 0, strict, "monster"),
                    powersOf(m.get("powers")));
            monster.intentDmg = intField(m, "intentDmg", 0, strict, "monster");
            monster.intentAttack = boolField(m, "intentAttack", false, strict, "monster");
            monster.pendingMoveId = intField(m, "pendingMoveId", -1, strict, "monster");
            monster.invincibleMax = intField(m, "invincibleMax", 0, strict, "monster");
            monster.stolenGold = intField(m, "stolenGold", 0, strict, "monster");
            monster.damageBase.addAll(intsOf(m.get("damageBase")));
            monster.moveHistory.addAll(intsOf(m.get("moveHistory")));
            if (m.containsKey("pendingMove")) {
                monster.pendingMove = moveOf(m.get("pendingMove"));
            } else if (strict) {
                throw new IllegalStateException(
                        "strict snapshot missing field: monster.pendingMove");
            }
            s.monsters.add(monster);
        }

        Map<String, Object> rng = asMap(root.get("rng"));
        for (SimState.RngKind kind : SimState.RngKind.values()) {
            String key = kind.name().toLowerCase(java.util.Locale.ROOT);
            if (!rng.containsKey(key)) {
                if (strict) {
                    throw new IllegalStateException("strict snapshot missing RNG stream: " + key);
                }
                continue;
            }
            Object value = rng.get(key);
            if (value == null) {
                continue;
            }
            Map<String, Object> stream = asMap(value);
            s.rng.put(kind, RngStream.from(
                    longOf(stream, "s0"), longOf(stream, "s1"), intOf(stream, "counter")));
        }
        if (s.maxHp <= 0 || s.hp <= 0) {
            throw new IllegalStateException("golden position has no player: " + name);
        }
        return s;
    }

    /** 招式载荷。缺字段只在旧 dump 上发生；严格模式的三态会先在 {@link #intField} 上失败。 */
    private static MonsterMove moveOf(Object o) {
        if (o == null) {
            return null;
        }
        Map<String, Object> m = asMap(o);
        return new MonsterMove(
                intOf(m, "id"),
                intOf(m, "damage"),
                intOf(m, "hits"),
                intOf(m, "block"),
                intOf(m, "blockRandomAlly"),
                strOrNull(m, "selfBuff"), intOf(m, "selfBuffAmount"),
                strOrNull(m, "playerDebuff"), intOf(m, "playerDebuffAmount"),
                strOrNull(m, "playerDebuff2"), intOf(m, "playerDebuff2Amount"),
                strOrNull(m, "statusCard"), intOf(m, "statusCount"),
                strOrNull(m, "splitInto"), intOf(m, "splitCount"),
                strOrNull(m, "splitInto2"), intOf(m, "splitCount2"),
                boolOf(m, "escape"), boolOf(m, "vampire"),
                intOf(m, "healAllies"),
                strOrNull(m, "buffAlliesPower"), intOf(m, "buffAlliesAmount"),
                extraOf(m.get("extra")));
    }

    private static MonsterMove.Extra extraOf(Object o) {
        if (o == null) {
            return null;
        }
        Map<String, Object> m = asMap(o);
        MonsterMove.Summon summon = null;
        Object sum = m.get("summon");
        if (sum != null) {
            Map<String, Object> sm = asMap(sum);
            List<Object> pool = asList(sm.get("pool"));
            MonsterMove.Spawn[] spawns = new MonsterMove.Spawn[pool.size()];
            for (int i = 0; i < pool.size(); i++) {
                Map<String, Object> sp = asMap(pool.get(i));
                spawns[i] = new MonsterMove.Spawn(strOf(sp, "type"),
                        intOf(sp, "hpMin"), intOf(sp, "hpMax"));
            }
            summon = new MonsterMove.Summon(spawns, intOf(sm, "count"), intOf(sm, "cap"),
                    boolOf(sm, "minion"));
        }
        List<String> cards = new ArrayList<String>();
        if (m.get("drawPileCards") != null) {
            for (Object item : asList(m.get("drawPileCards"))) {
                cards.add((String) item);
            }
        }
        return MonsterMove.Extra.restore(
                summon,
                strOrNull(m, "blockAllyType"), intOf(m, "blockAllyTypeAmount"),
                intOf(m, "blockAllies"),
                strOrNull(m, "playerDebuff3"), intOf(m, "playerDebuff3Amount"),
                boolOf(m, "cleanseSelf"),
                strOrNull(m, "statusDrawCard"), intOf(m, "statusDrawCount"),
                boolOf(m, "selfDestruct"),
                strOrNull(m, "absentBuff"), intOf(m, "absentBuffAmount"),
                boolOf(m, "reincarnate"), boolOf(m, "blockFromDamage"),
                cards.toArray(new String[0]),
                strOrNull(m, "extraSelfBuff"), intOf(m, "extraSelfBuffAmount"));
    }

    /** 只够读这个格式的最小解析器（对象 / 数组 / 数字 / 字符串 / 布尔 / null）。 */
    private static final class Parser {
        private final String text;
        private int at;

        Parser(String text) {
            this.text = text;
        }

        Object parseValue() {
            skipSpace();
            char c = text.charAt(at);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            at++;                       // {
            skipSpace();
            if (text.charAt(at) == '}') {
                at++;
                return out;
            }
            while (true) {
                skipSpace();
                String key = parseString();
                skipSpace();
                if (text.charAt(at) != ':') {
                    throw new IllegalStateException("expected ':' at " + at);
                }
                at++;
                out.put(key, parseValue());
                skipSpace();
                char c = text.charAt(at++);
                if (c == '}') {
                    return out;
                }
                if (c != ',') {
                    throw new IllegalStateException("expected '}' or ',' at " + at);
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> out = new ArrayList<Object>();
            at++;                       // [
            skipSpace();
            if (text.charAt(at) == ']') {
                at++;
                return out;
            }
            while (true) {
                out.add(parseValue());
                skipSpace();
                char c = text.charAt(at++);
                if (c == ']') {
                    return out;
                }
                if (c != ',') {
                    throw new IllegalStateException("expected ']' or ',' at " + at);
                }
            }
        }

        private String parseString() {
            if (text.charAt(at) != '"') {
                throw new IllegalStateException("expected string at " + at);
            }
            at++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char esc = text.charAt(at++);
                    switch (esc) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 'u':
                            sb.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                            at += 4;
                            break;
                        default:
                            sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        /**
         * 整数回 {@code Long}、带小数点/指数的回 {@code Double}。
         *
         * <p>必须区分：RNG 的 {@code s0/s1} 是 64 位随机状态，按 double 读会丢低位，
         * 读回来的局面就和真机不是同一个局面了（指纹对不上，搜索也只是"看起来像"）。
         */
        private Object parseNumber() {
            int start = at;
            boolean fractional = false;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                char c = text.charAt(at);
                if (c == '.' || c == 'e' || c == 'E') {
                    fractional = true;
                }
                at++;
            }
            String token = text.substring(start, at);
            return fractional ? (Object) Double.valueOf(Double.parseDouble(token))
                    : (Object) Long.valueOf(Long.parseLong(token));
        }

        private void expect(String word) {
            if (!text.startsWith(word, at)) {
                throw new IllegalStateException("expected " + word + " at " + at);
            }
            at += word.length();
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
    }
}
