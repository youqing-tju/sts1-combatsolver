package io.github.youqing.sts1solver.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 药水策略快照：全局档位 + 每槽指令 + 四预设。
 *
 * <p>这组测试盯的是「策略到底约束了谁」。以前只有一个 Smart 成本闸，UI 上写什么都只是装饰；
 * 现在 Force / Disabled / RequireAtLeastOne 必须真的能改掉最终答案，所以每一条都要能
 * 独立失败（改坏一处就红一条）。
 */
class PotionStrategySnapshotTest {

    private static PotionStrategySnapshot.SlotStatus slot(int slot, String id, boolean searchable) {
        return new PotionStrategySnapshot.SlotStatus(slot, id, searchable);
    }

    private static PotionStrategySnapshot empty() {
        return PotionStrategySnapshot.defaults();
    }

    private static RouteStep potionStep(int slot, String id) {
        return RouteStep.potion(id, slot, -1, -1);
    }

    private static RouteStep playStep() {
        return new RouteStep(false, "Strike_R", 0, -1);
    }

    private static RouteStep endTurn() {
        return new RouteStep(true, null, -1, -1);
    }

    // ------------------------------------------------------------------- tests

    @Test
    void globalDisabledBeatsForceOverride() {
        PotionStrategySnapshot snapshot = empty()
                .withGlobalPolicy(SolverPotionPolicy.DISABLED)
                .withDirective(0, "Fire Potion", SolverPotionDirective.FORCE);

        assertFalse(snapshot.allowsExplicitUse(0, "Fire Potion"),
                "全局 Disabled 是硬上限，Force 不能突破");
        assertEquals(SolverPotionDirective.FORCE, snapshot.directiveFor(0, "Fire Potion"),
                "指令本身仍然如实显示（用户要能看见自己设了什么）");

        PotionStrategySnapshot.FinalRouteVerdict verdict = snapshot.evaluateFinalRoute(
                Arrays.asList(playStep(), potionStep(0, "Fire Potion"), endTurn()));
        assertFalse(verdict.satisfied, "最终路线喝了药，与全局 Disabled 冲突");
        assertTrue(verdict.describe().contains("disabled"), "说清是哪条规则挡的：" + verdict.describe());
    }

    @Test
    void globalRequireAtLeastOneDoesNotForceEverySlot() {
        PotionStrategySnapshot snapshot = empty()
                .withGlobalPolicy(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE);

        assertTrue(snapshot.allowsExplicitUse(0, "Fire Potion"));
        assertTrue(snapshot.allowsExplicitUse(1, "Block Potion"));
        assertFalse(snapshot.hasForcedDirectives(),
                "RequireAtLeastOne 只要求「至少喝一瓶」，不指定哪一瓶");

        assertFalse(snapshot.evaluateFinalRoute(
                Arrays.asList(playStep(), endTurn())).satisfied, "一瓶都没喝 -> 不满足");
        assertTrue(snapshot.evaluateFinalRoute(
                Arrays.asList(potionStep(0, "Fire Potion"), endTurn())).satisfied,
                "喝一瓶就满足");
    }

    @Test
    void smartDirectiveRemovesPersistentOverride() {
        PotionStrategySnapshot forced = empty()
                .withDirective(0, "Fire Potion", SolverPotionDirective.FORCE);
        assertTrue(forced.hasForcedDirectives());
        assertEquals(1, forced.storageDirectiveCount());

        PotionStrategySnapshot back = forced.withDirective(0, "Fire Potion",
                SolverPotionDirective.SMART);
        assertFalse(back.hasForcedDirectives());
        assertEquals(0, back.storageDirectiveCount(),
                "切回 Smart 必须删掉覆盖项，而不是存一条 SMART —— 否则换药后旧槽会残留语义");
    }

    @Test
    void sameIdAtDifferentSlotHasIndependentDirective() {
        PotionStrategySnapshot snapshot = empty()
                .withDirective(0, "Fire Potion", SolverPotionDirective.DISABLED)
                .withDirective(1, "Fire Potion", SolverPotionDirective.FORCE);

        assertEquals(SolverPotionDirective.DISABLED, snapshot.directiveFor(0, "Fire Potion"));
        assertEquals(SolverPotionDirective.FORCE, snapshot.directiveFor(1, "Fire Potion"));
        assertFalse(snapshot.allowsExplicitUse(0, "Fire Potion"));
        assertTrue(snapshot.allowsExplicitUse(1, "Fire Potion"));

        // 只喝了 1 号槽那瓶：Force 满足，0 号槽的 Disabled 也没被违反
        assertTrue(snapshot.evaluateFinalRoute(
                Arrays.asList(potionStep(1, "Fire Potion"), endTurn())).satisfied);
    }

    @Test
    void changedPotionIdDoesNotInheritOldSlotOverride() {
        PotionStrategySnapshot snapshot = empty()
                .withDirective(0, "Fire Potion", SolverPotionDirective.DISABLED);

        // 同一格换成别的药水：旧覆盖必须不生效（键是 slot+potionId）
        assertEquals(SolverPotionDirective.SMART, snapshot.directiveFor(0, "Block Potion"));
        assertTrue(snapshot.allowsExplicitUse(0, "Block Potion"));
    }

    @Test
    void onlyForcedKeepsForcedAndDisablesOtherSearchablePotions() {
        PotionStrategySnapshot snapshot = empty()
                .withDirective(0, "Fire Potion", SolverPotionDirective.FORCE)
                .withPreset(SolverPotionPreset.ONLY_FORCED, Arrays.asList(
                        slot(0, "Fire Potion", true),
                        slot(1, "Block Potion", true)));

        assertEquals(SolverPotionDirective.FORCE, snapshot.directiveFor(0, "Fire Potion"));
        assertEquals(SolverPotionDirective.DISABLED, snapshot.directiveFor(1, "Block Potion"));
        assertTrue(snapshot.allowsExplicitUse(0, "Fire Potion"));
        assertFalse(snapshot.allowsExplicitUse(1, "Block Potion"));
        assertEquals(SolverPotionPolicy.SMART, snapshot.globalPolicy(),
                "OnlyForced 不是全局 RequireAtLeastOne —— 它只锁「哪些能喝」，不要求必须喝");
    }

    @Test
    void presetsSkipAbsentOrNonSearchablePotions() {
        List<PotionStrategySnapshot.SlotStatus> slots = Arrays.asList(
                slot(0, "Fire Potion", true),
                slot(1, null, false),
                slot(2, "Block Potion", false));

        PotionStrategySnapshot allForced = empty()
                .withPreset(SolverPotionPreset.ALL_FORCED, slots);
        assertEquals(SolverPotionDirective.FORCE, allForced.directiveFor(0, "Fire Potion"));
        assertEquals(SolverPotionDirective.SMART, allForced.directiveFor(2, "Block Potion"),
                "不可搜索的药水不能被设成 Force（设了也执行不了）");
        assertEquals(1, allForced.storageDirectiveCount(), "空槽与不可搜索槽不该落盘");

        PotionStrategySnapshot allProtected = empty()
                .withPreset(SolverPotionPreset.ALL_PROTECTED, slots);
        assertEquals(SolverPotionDirective.DISABLED, allProtected.directiveFor(0, "Fire Potion"));
        assertEquals(SolverPotionDirective.SMART, allProtected.directiveFor(2, "Block Potion"));

        PotionStrategySnapshot allSmart = allForced
                .withPreset(SolverPotionPreset.ALL_SMART, slots);
        assertEquals(0, allSmart.storageDirectiveCount(), "AllSmart = 清空所有覆盖");
    }

    @Test
    void finalRouteMustContainEveryForcedSlotAndPotionId() {
        PotionStrategySnapshot snapshot = empty()
                .withDirective(0, "Fire Potion", SolverPotionDirective.FORCE)
                .withDirective(1, "Block Potion", SolverPotionDirective.FORCE);

        PotionStrategySnapshot.FinalRouteVerdict partial = snapshot.evaluateFinalRoute(
                Arrays.asList(potionStep(0, "Fire Potion"), endTurn()));
        assertFalse(partial.satisfied, "两瓶都 Force，只喝一瓶不算满足");
        assertEquals(2, partial.forcedCount());
        assertEquals(1, partial.forcedUsed());
        assertTrue(partial.describe().contains("1:"), "要说清缺的是哪个槽：" + partial.describe());

        // 同 ID 但换槽位不算数
        PotionStrategySnapshot.FinalRouteVerdict wrongSlot = empty()
                .withDirective(1, "Fire Potion", SolverPotionDirective.FORCE)
                .evaluateFinalRoute(Arrays.asList(potionStep(0, "Fire Potion"), endTurn()));
        assertFalse(wrongSlot.satisfied, "Force 绑的是 (slot, potionId)，不是「喝过同名牌」");
    }

    @Test
    void requireAtLeastOneRejectsZeroPotionFinalRoute() {
        PotionStrategySnapshot snapshot = empty()
                .withGlobalPolicy(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE)
                .withDirective(1, "Block Potion", SolverPotionDirective.DISABLED);

        PotionStrategySnapshot.FinalRouteVerdict none = snapshot.evaluateFinalRoute(
                Arrays.asList(playStep(), endTurn()));
        assertFalse(none.satisfied);
        assertEquals(0, none.explicitUses);
        assertTrue(none.describe().contains("require_at_least_one"),
                "原因要能读：" + none.describe());

        PotionStrategySnapshot.FinalRouteVerdict one = snapshot.evaluateFinalRoute(
                Arrays.asList(potionStep(0, "Fire Potion"), endTurn()));
        assertTrue(one.satisfied);
        assertEquals(1, one.explicitUses);
    }

    @Test
    void disabledSlotIsRejectedEvenWhenOtherPotionsAreAllowed() {
        PotionStrategySnapshot snapshot = empty()
                .withDirective(1, "Block Potion", SolverPotionDirective.DISABLED);

        PotionStrategySnapshot.FinalRouteVerdict verdict = snapshot.evaluateFinalRoute(
                Arrays.asList(potionStep(1, "Block Potion"), endTurn()));
        assertFalse(verdict.satisfied);
        assertTrue(verdict.describe().contains("1:Block Potion"),
                "要说清是哪一格哪瓶：" + verdict.describe());
    }

    @Test
    void legacyConfigDefaultsToSmartWithoutOverrides() {
        for (String raw : new String[]{null, "", "   ", "garbage"}) {
            PotionStrategySnapshot snapshot = PotionStrategySnapshot.parse(raw);
            assertEquals(SolverPotionPolicy.SMART, snapshot.globalPolicy(),
                    "旧配置没有这个键 -> 默认 Smart（输入：" + raw + "）");
            assertEquals(0, snapshot.storageDirectiveCount(), "旧配置没有任何覆盖");
            assertTrue(snapshot.allowsExplicitUse(0, "Fire Potion"));
        }
    }

    @Test
    void storageStringRoundTripsEveryDirective() {
        PotionStrategySnapshot original = empty()
                .withGlobalPolicy(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE)
                .withDirective(0, "Fire Potion", SolverPotionDirective.FORCE)
                .withDirective(2, "Liquid Memories", SolverPotionDirective.DISABLED);

        String stored = original.toStorageString();
        PotionStrategySnapshot parsed = PotionStrategySnapshot.parse(stored);
        assertEquals(original.globalPolicy(), parsed.globalPolicy());
        assertEquals(SolverPotionDirective.FORCE, parsed.directiveFor(0, "Fire Potion"));
        assertEquals(SolverPotionDirective.DISABLED, parsed.directiveFor(2, "Liquid Memories"));
        assertEquals(original, parsed, "往返必须稳定（相等按语义比，不按字符串拼写）");
        assertTrue(parsed.toStorageString().equals(stored), "序列化必须确定性");
    }

    @Test
    void unknownGarbageEntriesAreIgnoredNotSwallowedSilently() {
        List<String> problems = new ArrayList<String>();
        PotionStrategySnapshot snapshot = PotionStrategySnapshot.parse(
                "policy=SMART;directive=0:Fire Potion:NUKE;broken;directive=9::FORCE", problems);

        assertFalse(snapshot.hasForcedDirectives(), "解析不了的指令不能被当成 Force");
        assertEquals(SolverPotionDirective.SMART, snapshot.directiveFor(0, "Fire Potion"));
        assertEquals(3, problems.size(), "坏条目要报出来（不是静默丢），实际：" + problems);

        List<String> fresh = new ArrayList<String>();
        PotionStrategySnapshot clean = PotionStrategySnapshot.parse(null, fresh);
        assertTrue(fresh.isEmpty(), "空配置不是错误：" + fresh);
        assertEquals(0, clean.storageDirectiveCount());
        assertEquals(Collections.emptyList(), clean.directives());
    }
}
