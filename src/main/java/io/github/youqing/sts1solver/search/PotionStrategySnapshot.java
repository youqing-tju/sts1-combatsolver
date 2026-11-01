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
 * 随时可能改策略。后台只读请求里带的这一份，就不会读到「改到一半」的全局状态。
 *
 * <p>三条语义（逐条对应蓝本 {@code PotionStrategySnapshot.cs:66-87}）：
 * <ul>
 *   <li>{@link SolverPotionDirective#SMART} 就是「没有覆盖」。{@code withDirective(SMART)}
 *       会**删掉**记录（蓝本 {@code SolverSettings.ApplyPotionDirective}：先按槽清空，
 *       非 Smart 才写回），避免换药之后旧语义残留；</li>
 *   <li>持久化键是 {@code (slot, potionId)}，两瓶同 ID 的药水互不影响；</li>
 *   <li>全局 {@link SolverPotionPolicy#DISABLED} <b>只决定「没有登记的槽」的默认值</b>，
 *       不是硬上限 —— 显式的 Force 仍然可以展开（蓝本 {@code AllowsExplicitUse}：
 *       先看显式指令，没有登记才看当前档位）。</li>
 * </ul>
 */
public final class PotionStrategySnapshot {

    /** 存储串里的一行：{@code directive=<slot>:<potionId>:<DIRECTIVE>}。 */
    private static final String KEY_POLICY = "policy=";
    private static final String KEY_DIRECTIVE = "directive=";

    private final SolverPotionPolicy globalPolicy;
    /** 键 = {@code slot + ":" + potionId}，按字符串排序保证序列化确定性。 */
    private final Map<String, SolverPotionDirective> directives;
    /**
     * 只允许**强制用药**（蓝本 {@code PotionStrategySnapshot._onlyForcedUses}）。
     *
     * <p>仅用于「强制用药基线」那一趟搜索（{@link #forForcedBaseline}）：基线里可以有用户
     * 明确要求喝的那几瓶，但不能有可选药水。这样「喝药路线比基线多省了多少血」衡量的
     * 才是**可选药水**的收益，而不是把强制的那几瓶也算进去。
     */
    private final boolean onlyForcedUses;

    private PotionStrategySnapshot(SolverPotionPolicy policy,
                                   Map<String, SolverPotionDirective> directives) {
        this(policy, directives, false);
    }

    private PotionStrategySnapshot(SolverPotionPolicy policy,
                                   Map<String, SolverPotionDirective> directives,
                                   boolean onlyForcedUses) {
        this.globalPolicy = policy;
        this.directives = Collections.unmodifiableMap(
                new java.util.TreeMap<String, SolverPotionDirective>(directives));
        this.onlyForcedUses = onlyForcedUses;
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

    /**
     * 强制用药基线：只有显式 Force 的药水可以喝（蓝本 {@code ForForcedBaseline}）。
     *
     * <p>没有 Force 时它等价于「一瓶都不许喝」，所以药水闸的基线搜索永远可以用它，
     * 不必分两种模式。
     */
    public PotionStrategySnapshot forForcedBaseline() {
        return new PotionStrategySnapshot(globalPolicy, directives, true);
    }

    /** 只放了 Force 的那几瓶能不能喝（供 {@link #forForcedBaseline} 的快照用）。 */
    public boolean allowsOnlyForcedUses() {
        return onlyForcedUses;
    }

    /** 这一格这一瓶能不能被**显式**使用（搜索候选展开与最终答案共用这一条判据）。 */
    public boolean allowsExplicitUse(int slot, String potionId) {
        return allowsExplicitUse(slot, potionId, globalPolicy, false);
    }

    /**
     * 蓝本 {@code AllowsExplicitUse}（{@code PotionStrategySnapshot.cs:66-77}）逐行翻译。
     *
     * <p>顺序不能换：
     * <ol>
     *   <li>{@code forceAllDisabled} 一票否决（这一局根本不能用药，例如战斗规则禁用）；</li>
     *   <li>强制基线模式下只看 Force；</li>
     *   <li>否则**显式指令优先**：登记的 Force/Disabled 说了算；没登记才看当前档位。</li>
     * </ol>
     *
     * @param effectivePolicy 这一趟搜索用的档位（可能是覆盖值，例如基线搜索传 Disabled）
     * @param forceAllDisabled 这一局是否完全不能用药
     */
    public boolean allowsExplicitUse(int slot, String potionId,
                                     SolverPotionPolicy effectivePolicy,
                                     boolean forceAllDisabled) {
        if (forceAllDisabled) {
            return false;
        }
        SolverPotionDirective explicit = directives.get(key(slot, potionId));
        if (onlyForcedUses) {
            return explicit == SolverPotionDirective.FORCE;
        }
        if (explicit != null) {
            return explicit != SolverPotionDirective.DISABLED;
        }
        return effectivePolicy != SolverPotionPolicy.DISABLED;
    }

    public boolean hasForcedDirectives() {
        return !forcedKeys().isEmpty();
    }

    /** 强制用药清单，形如 {@code Fire Potion@0, Block Potion@2}（蓝本 {@code DescribeForcedUses}）。 */
    public String describeForcedUses() {
        StringBuilder sb = new StringBuilder();
        for (String key : forcedKeys()) {
            String[] parts = splitKey(key);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(parts[1]).append('@').append(parts[0]);
        }
        return sb.toString();
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
     * 最终路线是否满足策略：每个 Force 都**真的用过** + 没喝被禁用的槽 + 至少一瓶（若要求）。
     *
     * <p>Force 校验的是「这条路线里出现过 {@code (slot, potionId)}」，
     * 不是「喝过同名牌」—— 两瓶同 ID 的药水换槽喝一瓶不算数。
     *
     * <p>⚠️ 全局 {@code Disabled} **不**在这里额外加一条「禁止任何用药」的违规：
     * 按蓝本它只决定未登记槽的默认值，显式 Force 照样允许。旧实现多加了那一条，
     * 于是「全局 Disabled + 单槽 Force」永远不可能满足 —— 那正是自创。
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
        if (effectivePolicy(forcedUsed, explicitUses - forcedUsed)
                == SolverPotionPolicy.REQUIRE_AT_LEAST_ONE && explicitUses == 0) {
            violations.add("policy require_at_least_one needs at least one explicit use");
        }
        return new FinalRouteVerdict(violations.isEmpty(), explicitUses, forced.size(),
                forcedUsed, violations);
    }

    /**
     * 这一趟解算该按哪个档位判「至少一瓶」。
     *
     * <p>蓝本 {@code FinalPlanOrdering.cs:60-66} 的 {@code effectivePotionPolicy}：
     * <ul>
     *   <li>{@code RequireAtLeastOne} 但已经有强制用药 → 降为 {@code Smart}
     *       （「至少一瓶」的要求已经被那瓶强制药满足了）；</li>
     *   <li>{@code Disabled} 但路线里还有**可选**用药 → 也按 {@code Smart} 判
     *       （这条路线本身就不合规，由 {@code allowsExplicitUse} 负责拦；这里只算档位）。</li>
     * </ul>
     */
    public SolverPotionPolicy effectivePolicy(int forcedUsed, int optionalUses) {
        if (globalPolicy == SolverPotionPolicy.REQUIRE_AT_LEAST_ONE && forcedUsed > 0) {
            return SolverPotionPolicy.SMART;
        }
        if (globalPolicy == SolverPotionPolicy.DISABLED && optionalUses > 0) {
            return SolverPotionPolicy.SMART;
        }
        return globalPolicy;
    }

    // ------------------------------------------------------------------ storage

    /**
     * 落盘串：{@code directive=0:Fire Potion:FORCE;…}（按键排序，确定性）。
     *
     * <p>⚠️ **不写全局档位**：蓝本 {@code SolverSettings.cs:94-96} 上 {@code PotionPolicy}
     * 带 {@code [JsonIgnore]}，每次新设置都回到 {@code Smart}，只有每槽指令持久化。
     * 旧版本写过 {@code policy=…}，{@link #parse} 会把它当历史残留忽略并记一条 problem。
     */
    public String toStorageString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, SolverPotionDirective> e : directives.entrySet()) {
            String[] parts = splitKey(e.getKey());
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(KEY_DIRECTIVE).append(parts[0]).append(':')
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
                // 全局档位不持久化（蓝本 [JsonIgnore]）：老配置里写过也一律忽略，
                // 每次新设置都从 Smart 开始。记一条 problem 便于排查「为什么我的档位没了」。
                report(problems, "policy is not persisted: " + entry);
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
