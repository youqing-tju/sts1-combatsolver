package io.github.youqing.sts1solver.search;

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
 * 「金标准局面库」的读取器：把真机 / 回放写出来的 {@code combat-*.json} 读回 {@link SimState}。
 *
 * <p>为什么需要它：用户抱怨「牌打得臭」时，我们只有日志里的文字，没有可复现的局面 ——
 * 于是任何改动都只能靠感觉判断好坏。有了这个读取器，真机拍到的局面可以直接变成测试用例：
 * 同一份局面、同一份配置，改前改后各跑一次，谁的第一手更好一目了然。
 *
 * <p>格式由 {@link SimState#toJson()} 定义（卡牌写成对象，带 cost / dmg / type；
 * 怪物带 intentDmg / intentAttack / pendingMoveId）。这里只做**忠实读回**，不做任何补全猜测；
 * 缺字段就报错，不许静默给默认值（否则库里的局面和真机不是同一个局面）。
 */
final class GoldenPositions {

    private GoldenPositions() {
    }

    /** 读一个局面文件。 */
    static SimState read(File file) throws Exception {
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Object root = new Parser(json).parseValue();
        if (!(root instanceof Map)) {
            throw new IllegalStateException("golden file is not a JSON object: " + file);
        }
        return toState(asMap(root), file.getName());
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

    private static Map<String, Integer> powersOf(Object o) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
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

    private static SimState toState(Map<String, Object> root, String name) {
        SimState s = new SimState();
        Map<String, Object> meta = asMap(root.get("meta"));
        s.act = intOf(meta, "act");
        s.floor = intOf(meta, "floor");
        s.ascension = intOf(meta, "ascension");

        Map<String, Object> p = asMap(root.get("player"));
        s.hp = intOf(p, "hp");
        s.maxHp = intOf(p, "maxHp");
        s.block = intOf(p, "block");
        s.energy = intOf(p, "energy");
        s.deathSaveUsed = boolOf(p, "deathSaveUsed");
        s.deathSaveUseCount = intOf(p, "deathSaveUseCount");
        s.deathSaveHpRestored = intOf(p, "deathSaveHpRestored");
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

        for (Object item : asList(root.get("monsters"))) {
            Map<String, Object> m = asMap(item);
            SimMonster monster = new SimMonster(strOf(m, "type"), intOf(m, "hp"),
                    intOf(m, "maxHp"), intOf(m, "block"), powersOf(m.get("powers")));
            monster.intentDmg = intOf(m, "intentDmg");
            monster.intentAttack = boolOf(m, "intentAttack");
            monster.pendingMoveId = intOf(m, "pendingMoveId");
            s.monsters.add(monster);
        }

        for (Map.Entry<String, Object> e : asMap(root.get("rng")).entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            Map<String, Object> stream = asMap(e.getValue());
            SimState.RngKind kind = SimState.RngKind.valueOf(e.getKey().toUpperCase());
            s.rng.put(kind, RngStream.from(
                    longOf(stream, "s0"), longOf(stream, "s1"), intOf(stream, "counter")));
        }
        if (s.maxHp <= 0 || s.hp <= 0) {
            throw new IllegalStateException("golden position has no player: " + name);
        }
        return s;
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
