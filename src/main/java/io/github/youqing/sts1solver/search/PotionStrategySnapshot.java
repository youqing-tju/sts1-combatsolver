package io.github.youqing.sts1solver.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变的药水策略快照（蓝本 {@code PotionStrategySnapshot.cs:20-101}）。
 *
 * <p>为什么必须「快照」而不是直接读 {@code SolverConfig}：搜索跑在后台线程，而用户在战斗中
 * 随时可能改策略。后台只读请求里带的这一份，就不会读到「改到一半」的全局状态；
 * 主线程用 {@code policyRevision} 判别「这份结果是不是旧策略算的」。
 *
 * <p>三条语义：
 * <ul>
 *   <li>{@link SolverPotionDirective#SMART} 就是「没有覆盖」。{@code withDirective(SMART)}
 *       会**删掉**记录，避免换药之后旧语义残留；</li>
 *   <li>持久化键是 {@code (slot, potionId)}，两瓶同 ID 的药水互不影响；</li>
 *   <li>全局 {@link SolverPotionPolicy#DISABLED} 是硬上限，Force 也压不过。</li>
 * </ul>
 */
public final class PotionStrategySnapshot {

    /** 存储串里的一行：{@code directive=<slot>:<potionId>:<DIRECTIVE>}。 */
    private static final String KEY_POLICY = "policy=";
    private static final String KEY_DIRECTIVE = "directive=";

    private final SolverPotionPolicy globalPolicy;
    /** 键 = {@code slot + ":" + potionId}，按字符串排序保证序列化确定性。 */
    private final Map<String, SolverPotionDirective> directives;

    private PotionStrategySnapshot(SolverPotionPolicy policy,
                                   Map<String, SolverPotionDirective> directives) {
        this.globalPolicy = policy;
        this.directives = Collections.unmodifiableMap(
                new java.util.TreeMap<String, SolverPotionDirective>(directives));
    }

    public static PotionStrategySnapshot defaults() {
        return new PotionStrategySnapshot(SolverPotionPolicy.SMART,
                Collections.<String, SolverPotionDirective>emptyMap());
    }

    // ------------------------------------------------------------------ queries

    public SolverPotionPolicy globalPolicy() {
        return globalPolicy;
    }

    /**
     * 这一格这一瓶该按什么处理。
     *
     * <p>没有覆盖时：全局 Disabled → Disabled，其余 → Smart（蓝本 {@code Resolve} 同口径）。
     */
    public SolverPotionDirective directiveFor(int slot, String potionId) {
        SolverPotionDirective override = directives.get(key(slot, potionId));
        if (override != null) {
            return override;
        }
        return globalPolicy == SolverPotionPolicy.DISABLED
                ? SolverPotionDirective.DISABLED : SolverPotionDirective.SMART;
    }

    /** 这一格这一瓶能不能被**显式**使用（搜索候选展开与最终答案共用这一条判据）。 */
    public boolean allowsExplicitUse(int slot, String potionId) {
        if (globalPolicy == SolverPotionPolicy.DISABLED) {
            return false;               // 硬上限，Force 也不能突破
        }
        return directiveFor(slot, potionId) != SolverPotionDirective.DISABLED;
    }

    public boolean hasForcedDirectives() {
        return !forcedKeys().isEmpty();
    }

    /** 落盘的覆盖条数（{@code SMART} 不算覆盖，所以不计入）。 */
    public int storageDirectiveCount() {
        return directives.size();
    }

    /** 只读的覆盖列表，按槽位排序。 */
    public List<SlotDirective> directives() {
        List<SlotDirective> out = new ArrayList<SlotDirective>(directives.size());
        for (Map.Entry<String, SolverPotionDirective> e : directives.entrySet()) {
            String[] parts = splitKey(e.getKey());
            out.add(new SlotDirective(Integer.parseInt(parts[0]), parts[1], e.getValue()));
        }
        Collections.sort(out, new java.util.Comparator<SlotDirective>() {
            @Override
            public int compare(SlotDirective a, SlotDirective b) {
                int c = Integer.compare(a.slot, b.slot);
                return c != 0 ? c : a.potionId.compareTo(b.potionId);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ mutators

    public PotionStrategySnapshot withGlobalPolicy(SolverPotionPolicy policy) {
        return new PotionStrategySnapshot(policy == null ? SolverPotionPolicy.SMART : policy,
                directives);
    }

    public PotionStrategySnapshot withDirective(int slot, String potionId,
                                                SolverPotionDirective directive) {
        if (potionId == null || potionId.isEmpty()) {
            return this;
        }
        Map<String, SolverPotionDirective> next =
                new LinkedHashMap<String, SolverPotionDirective>(directives);
        if (directive == null || directive == SolverPotionDirective.SMART) {
            next.remove(key(slot, potionId));       // Smart = 没有覆盖
        } else {
            next.put(key(slot, potionId), directive);
        }
        return new PotionStrategySnapshot(globalPolicy, next);
    }

    /**
     * 套一个预设。
     *
     * <p>只作用于**当前存在且可搜索**的药水：不可搜索的槽设成 Force 只会让最终答案永远
     * 满足不了约束（那是死锁，不是保护）。
     */
    public PotionStrategySnapshot withPreset(SolverPotionPreset preset, List<SlotStatus> potions) {
        if (preset == null || potions == null) {
            return this;
        }
        PotionStrategySnapshot out = this;
        for (SlotStatus status : potions) {
            if (status == null || status.potionId == null || !status.searchable) {
                continue;
            }
            SolverPotionDirective target;
            switch (preset) {
                case ALL_SMART:
                    target = SolverPotionDirective.SMART;
                    break;
                case ALL_PROTECTED:
                    target = SolverPotionDirective.DISABLED;
                    break;
                case ALL_FORCED:
                    target = SolverPotionDirective.FORCE;
                    break;
                case ONLY_FORCED:
                    target = out.directiveFor(status.slot, status.potionId)
                            == SolverPotionDirective.FORCE
                            ? SolverPotionDirective.FORCE : SolverPotionDirective.DISABLED;
                    break;
                default:
                    continue;
            }
            out = out.withDirective(status.slot, status.potionId, target);
        }
        return out;
    }

    // -------------------------------------------------------------- final answer

    /**
     * 最终路线是否满足策略：全局档位 + 每槽 Disabled + 每个 Force 都**真的用过**。
     *
     * <p>Force 校验的是「这条路线里出现过 {@code (slot, potionId)}」，
     * 不是「喝过同名牌」—— 两瓶同 ID 的药水换槽喝一瓶不算数。
     */
    public FinalRouteVerdict evaluateFinalRoute(List<RouteStep> steps) {
        List<String> violations = new ArrayList<String>();
        int explicitUses = 0;
        java.util.Set<String> used = new java.util.HashSet<String>();
        if (steps != null) {
            for (RouteStep step : steps) {
                if (step == null || !step.potion) {
                    continue;
                }
                explicitUses++;
                used.add(key(step.potionSlot, step.cardKey));
                if (!allowsExplicitUse(step.potionSlot, step.cardKey)) {
                    violations.add("slot " + step.potionSlot + ":" + step.cardKey
                            + " is not allowed by the strategy");
                }
            }
        }
        if (globalPolicy == SolverPotionPolicy.DISABLED && explicitUses > 0) {
            violations.add(0, "policy disabled forbids explicit potion use");
        }
        List<String> forced = forcedKeys();
        int forcedUsed = 0;
        for (String key : forced) {
            if (used.contains(key)) {
                forcedUsed++;
            } else {
                String[] parts = splitKey(key);
                violations.add("missing forced use " + parts[0] + ":" + parts[1]);
            }
        }
        if (globalPolicy == SolverPotionPolicy.REQUIRE_AT_LEAST_ONE && explicitUses == 0) {
            violations.add("policy require_at_least_one needs at least one explicit use");
        }
        return new FinalRouteVerdict(violations.isEmpty(), explicitUses, forced.size(),
                forcedUsed, violations);
    }

    // ------------------------------------------------------------------ storage

    /** 落盘串：{@code policy=SMART;directive=0:Fire Potion:FORCE;…}（按键排序，确定性）。 */
    public String toStorageString() {
        StringBuilder sb = new StringBuilder("policy=").append(globalPolicy.name());
        for (Map.Entry<String, SolverPotionDirective> e : directives.entrySet()) {
            String[] parts = splitKey(e.getKey());
            sb.append(';').append(KEY_DIRECTIVE).append(parts[0]).append(':')
                    .append(parts[1]).append(':').append(e.getValue().name());
        }
        return sb.toString();
    }

    public static PotionStrategySnapshot parse(String raw) {
        return parse(raw, null);
    }

    /**
     * 解析落盘串；坏条目记进 {@code problems}（传 {@code null} 表示不收集）。
     *
     * <p>坏条目一律**跳过**并退回默认，绝不猜一个语义 —— 猜错会让玩家莫名其妙地被禁用药水。
     * 空串/缺键等价于「全 Smart、无覆盖」，这是旧配置的兼容路径。
     */
    public static PotionStrategySnapshot parse(String raw, List<String> problems) {
        SolverPotionPolicy policy = SolverPotionPolicy.SMART;
        Map<String, SolverPotionDirective> directives =
                new LinkedHashMap<String, SolverPotionDirective>();
        if (raw == null || raw.trim().isEmpty()) {
            return new PotionStrategySnapshot(policy, directives);
        }
        for (String part : raw.split(";")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.startsWith(KEY_POLICY)) {
                String value = entry.substring(KEY_POLICY.length()).trim();
                SolverPotionPolicy parsed = SolverPotionPolicy.parse(value);
                if (parsed == null) {
                    report(problems, "unknown policy: " + value);
                } else {
                    policy = parsed;
                }
                continue;
            }
            if (entry.startsWith(KEY_DIRECTIVE)) {
                String body = entry.substring(KEY_DIRECTIVE.length());
                String[] parts = body.split(":", 3);
                if (parts.length != 3) {
                    report(problems, "malformed directive: " + entry);
                    continue;
                }
                int slot;
                try {
                    slot = Integer.parseInt(parts[0].trim());
                } catch (NumberFormatException e) {
                    report(problems, "malformed slot: " + entry);
                    continue;
                }
                String potionId = parts[1].trim();
                SolverPotionDirective directive = SolverPotionDirective.parse(parts[2]);
                if (slot < 0 || potionId.isEmpty() || directive == null) {
                    report(problems, "malformed directive: " + entry);
                    continue;
                }
                if (directive == SolverPotionDirective.SMART) {
                    continue;                   // Smart 不是覆盖
                }
                directives.put(key(slot, potionId), directive);
                continue;
            }
            report(problems, "unknown entry: " + entry);
        }
        return new PotionStrategySnapshot(policy, directives);
    }

    private static void report(List<String> problems, String message) {
        if (problems != null) {
            problems.add(message);
        }
    }

    // -------------------------------------------------------------------- types

    /** 一个槽的现状（由主线程从真机快照填，搜索层不依赖 UI 类型）。 */
    public static final class SlotStatus {
        public final int slot;
        public final String potionId;
        public final boolean searchable;

        public SlotStatus(int slot, String potionId, boolean searchable) {
            this.slot = slot;
            this.potionId = potionId;
            this.searchable = searchable;
        }
    }

    /** 一条落盘的覆盖。 */
    public static final class SlotDirective {
        public final int slot;
        public final String potionId;
        public final SolverPotionDirective directive;

        SlotDirective(int slot, String potionId, SolverPotionDirective directive) {
            this.slot = slot;
            this.potionId = potionId;
            this.directive = directive;
        }

        @Override
        public String toString() {
            return slot + ":" + potionId + "=" + directive;
        }
    }

    /** 最终路线是否满足策略。 */
    public static final class FinalRouteVerdict {
        public final boolean satisfied;
        public final int explicitUses;
        public final int forcedCount;
        public final int forcedUsed;
        private final List<String> violations;

        FinalRouteVerdict(boolean satisfied, int explicitUses, int forcedCount, int forcedUsed,
                          List<String> violations) {
            this.satisfied = satisfied;
            this.explicitUses = explicitUses;
            this.forcedCount = forcedCount;
            this.forcedUsed = forcedUsed;
            this.violations = Collections.unmodifiableList(violations);
        }

        public int forcedCount() {
            return forcedCount;
        }

        public int forcedUsed() {
            return forcedUsed;
        }

        public List<String> violations() {
            return violations;
        }

        public String describe() {
            return violations.isEmpty() ? "ok" : String.join(" | ", violations);
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<String> forcedKeys() {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, SolverPotionDirective> e : directives.entrySet()) {
            if (e.getValue() == SolverPotionDirective.FORCE) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private static String key(int slot, String potionId) {
        return slot + ":" + (potionId == null ? "" : potionId);
    }

    private static String[] splitKey(String key) {
        int at = key.indexOf(':');
        return new String[]{key.substring(0, at), key.substring(at + 1)};
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PotionStrategySnapshot)) {
            return false;
        }
        PotionStrategySnapshot o = (PotionStrategySnapshot) other;
        return globalPolicy == o.globalPolicy && directives.equals(o.directives);
    }

    @Override
    public int hashCode() {
        return globalPolicy.hashCode() * 31 + directives.hashCode();
    }

    @Override
    public String toString() {
        return toStorageString();
    }
}
