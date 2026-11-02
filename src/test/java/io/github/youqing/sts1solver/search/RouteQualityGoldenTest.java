package io.github.youqing.sts1solver.search;

import io.github.youqing.sts1solver.mirror.PostCombatRelicHeal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四条端到端 golden：同一局面、同一条胜利路线，只让**一个**蓝本维度不同，看最终选择器怎么判。
 *
 * <p>为什么需要这一层：逐键单测（{@code FinalPlanOrderingTest}）证明「键本身对」，但证明不了
 * 「这四条真实收益/代价真的被接进了选择器」。这里每个用例都同时断言两件事：
 * <ol>
 *   <li><b>旧实现会选错</b>——{@link #firstDifferingKey} 给出的分歧位置**不在**旧实现的 9 键集合里，
 *       也就是旧实现把这两条看成完全等价、只能靠输入顺序决定；</li>
 *   <li><b>新实现按蓝本选</b>——分歧位置正好是预期的那个键，且胜者是预期那条。</li>
 * </ol>
 *
 * <p>{@link #firstDifferingKey} 是**独立重写一遍**的 18 键顺序（不调用生产比较器）。
 * 两边都改错才会同时骗过它，比单看结果字符串结实。
 */
class RouteQualityGoldenTest {

    /** 旧实现（本轮之前）实际比较的键；下标与 {@link #firstDifferingKey} 一致。 */
    private static final int[] OLD_KEYS = {0, 1, 2, 3, 4, 7, 15, 16, 17};

    // ---------------------------------------------------------------- 1. 低战损

    /**
     * 两条路线**收尾血量、战损、成长全都一样**，只有一条是「卖血换来的」（可避免的掉血）。
     *
     * <p>第 15 键 {@code StrategicSold} 升序。蓝本的口径是「同组里最低可达掉血之外多掉的那部分」，
     * 不是总掉血：所以两条都掉了同样多血时，**本来可以不掉**的那条更差。
     * 旧实现没有这一键，两条等价、只能按输入顺序。
     */
    @Test
    void avoidableSoldHpLosesToAPlanThatNeverSellsHp() {
        Spec churned = spec("churned");
        churned.strategicSold = 3;
        Spec clean = spec("clean");

        assertEquals(14, firstDifferingKey(churned, clean),
                "分歧必须在可避免卖血（第 15 键）；收尾血量与战略战损都相同");
        assertFalse(isOldKey(14), "旧实现没有这一键：这两条在旧实现里等价，会按输入顺序选 churned");
        assertEquals("clean", pick(churned, clean), "不卖血的那条必须赢");
        assertEquals("clean", pick(clean, churned), "顺序无关");
    }

    // ---------------------------------------------------------------- 2. Feed

    /**
     * 都赢、战损相同，只差「多吃了一口」。
     *
     * <p>第 7 键 {@code GrowthRewardCount} 降序：免费成长也照样优先。旧实现没有这个键，
     * 两条完全等价。
     */
    @Test
    void freeGrowthWinsWhenEverythingElseTies() {
        Spec plain = spec("plain");
        Spec fed = spec("fed");
        fed.growthRewardCount = 1;

        assertEquals(6, firstDifferingKey(plain, fed), "分歧在第 7 键（成长次数）");
        assertFalse(isOldKey(6), "旧实现没有成长键：这两条在旧实现里等价，会按输入顺序选 plain");
        assertEquals("fed", pick(plain, fed), "多吃一口的那条必须赢");
        assertEquals("fed", pick(fed, plain), "顺序无关，赢家不因输入顺序改变");
    }

    // ---------------------------------------------------------------- 3. 贪婪之手

    /**
     * 都赢、战损与成长次数相同，只差「到手的金币」。
     *
     * <p>第 11 键 {@code LongTermResourceValue} 降序。金币是**长期资源**，进最终键也进 Beam 分，
     * 否则打钱路线在剪枝阶段就没了。
     */
    @Test
    void realizedGoldWinsAsALongTermResource() {
        Spec broke = spec("broke");
        Spec paid = spec("paid");
        paid.longTermResourceValue = 25;

        assertEquals(10, firstDifferingKey(broke, paid), "分歧在第 11 键（长期资源）");
        assertFalse(isOldKey(10), "旧实现完全没看金币");
        assertEquals("paid", pick(broke, paid));
    }

    // ---------------------------------------------------------------- 4. Anger

    /**
     * 都赢、战损/成长/金币相同，只差「生成了几张 Anger」。
     *
     * <p>第 12 键 {@code AngerCopiesGenerated} **升序**：同样的结果，少往牌堆里塞垃圾的那条更优。
     * 这是蓝本唯一一处「复制牌要扣分」的显式表达，方向不能写反。
     */
    @Test
    void fewerAngerCopiesWinOnTheAscendingKey() {
        Spec spammy = spec("spammy");
        spammy.angerCopiesGenerated = 2;
        Spec tidy = spec("tidy");

        assertEquals(11, firstDifferingKey(spammy, tidy), "分歧在第 12 键（Anger 复制数）");
        assertFalse(isOldKey(11), "旧实现没有这个键");
        assertEquals("tidy", pick(spammy, tidy), "少造复制牌的那条赢");
        assertEquals("tidy", pick(tidy, spammy), "顺序无关");
    }

    // ---------------------------------------------------------------- 夹具

    /** 一条「已经打赢的低战损」路线；每个用例只改自己的那一个维度。 */
    private static Spec spec(String label) {
        Spec s = new Spec();
        s.label = label;
        return s;
    }

    /**
     * 一份候选的可变描述。默认值是**同一场战斗里最好的那种**：赢、零战损、零死亡豁免、
     * 零被偷、零成长、零用药、1 回合结束、敌人清零。
     */
    private static final class Spec {
        String label;
        boolean won = true;
        boolean survives = true;
        int deathSaveUseCount;
        int outstandingStolenResource;
        int cumulativeHpLost;
        int recoveredPlayerHp;
        int deathSaveHpRestored;
        int playerHp = 80;
        int playerMaxHp = 80;
        int growthRewardCount;
        int growthHpCredit;
        Integer combatEndedTurn = Integer.valueOf(1);
        int policyHpDeficit;
        int healthResourceCost;
        int longTermResourceValue;
        int angerCopiesGenerated;
        SearchBoundaryReason boundaryReason = SearchBoundaryReason.NONE;
        int optionalPotionCount;
        int strategicSold;
        int enemyHp;
        double score = 1_000d;
        int actionCount = 3;

        FinalPlanOrdering.Candidate toCandidate() {
            SearchFeatures features = SearchFeatures.builder()
                    .allEnemiesDead(won)
                    .projectedPlayerHp(playerHp)
                    .playerHp(playerHp)
                    .playerMaxHp(playerMaxHp)
                    .cumulativePlayerHpLost(cumulativeHpLost)
                    .recoveredPlayerHp(recoveredPlayerHp)
                    .deathSaveHpRestored(deathSaveHpRestored)
                    .longTermResourceValue(longTermResourceValue)
                    .angerCopiesGenerated(angerCopiesGenerated)
                    .enemyHp(enemyHp)
                    .outstandingStolenResource(outstandingStolenResource)
                    .potionCount(optionalPotionCount)
                    .futureSoldHp(strategicSold)
                    .actionCount(actionCount)
                    .score(score)
                    .boundaryReason(boundaryReason)
                    .build();
            RouteSnapshot snapshot = RouteSnapshot.builder()
                    .playerDead(!survives)
                    .deathSaveUseCount(deathSaveUseCount)
                    .strategyGoalHpCredit(growthHpCredit)
                    .strategyGoalCount(growthRewardCount)
                    .combatEndedTurn(combatEndedTurn)
                    .build();
            return new FinalPlanOrdering.Candidate(label, features, snapshot, 0, true);
        }
    }

    private static FinalPlanOrdering.Params params() {
        // 起点与 Spec 的默认值一致：满血 80 / maxHp 80，没有任何药水策略与战斗已承诺卖血。
        return new FinalPlanOrdering.Params(80, 80, 0, 0, SolverPotionPolicy.SMART, true,
                FinalPlanOrdering.TheftPolicy.PRESERVE_RESOURCES, 0,
                PostCombatRelicHeal.none(), null);
    }

    private static String pick(Spec first, Spec second) {
        List<FinalPlanOrdering.Candidate> candidates = new ArrayList<FinalPlanOrdering.Candidate>();
        candidates.add(first.toCandidate());
        candidates.add(second.toCandidate());
        return FinalPlanOrdering.select(candidates, params()).selected().label;
    }

    private static boolean isOldKey(int key) {
        for (int old : OLD_KEYS) {
            if (old == key) {
                return true;
            }
        }
        return false;
    }

    /**
     * 独立重写一遍 18 键顺序，返回**第一个**不同的键下标；全同返回 -1。
     *
     * <p>下标：0 Won / 1 !Won&amp;!Survives / 2 DeathSave / 3 Stolen / 4 StrategicHpDeficit /
     * 5 GrowthHpCredit / 6 GrowthRewardCount / 7 CombatEndedTurn / 8 PolicyHpDeficit /
     * 9 HealthResourceCost / 10 LongTermResourceValue / 11 AngerCopies / 12 BoundaryRank /
     * 13 OptionalPotionCount / 14 StrategicSold / 15 EnemyHp / 16 Score / 17 ActionCount。
     */
    private static int firstDifferingKey(Spec left, Spec right) {
        FinalPlanOrdering.CandidateResult a =
                FinalPlanOrdering.evaluate(left.toCandidate(), params());
        FinalPlanOrdering.CandidateResult b =
                FinalPlanOrdering.evaluate(right.toCandidate(), params());
        if (a.quality.won != b.quality.won) {
            return 0;
        }
        if ((!a.quality.won && !a.quality.survives) != (!b.quality.won && !b.quality.survives)) {
            return 1;
        }
        if (a.quality.deathSaveUseCount != b.quality.deathSaveUseCount) {
            return 2;
        }
        if (a.quality.outstandingStolenResource != b.quality.outstandingStolenResource) {
            return 3;
        }
        if (a.strategicHpDeficit != b.strategicHpDeficit) {
            return 4;
        }
        if (a.quality.growthHpCredit != b.quality.growthHpCredit) {
            return 5;
        }
        if (a.quality.growthRewardCount != b.quality.growthRewardCount) {
            return 6;
        }
        if (orMax(a.quality.combatEndedTurn) != orMax(b.quality.combatEndedTurn)) {
            return 7;
        }
        if (a.policyHpDeficit != b.policyHpDeficit) {
            return 8;
        }
        if (a.healthResourceCost != b.healthResourceCost) {
            return 9;
        }
        if (a.features.longTermResourceValue != b.features.longTermResourceValue) {
            return 10;
        }
        if (a.features.angerCopiesGenerated != b.features.angerCopiesGenerated) {
            return 11;
        }
        if (FinalPlanOrdering.policyBoundaryRank(a.features.boundaryReason)
                != FinalPlanOrdering.policyBoundaryRank(b.features.boundaryReason)) {
            return 12;
        }
        if (a.optionalPotionCount != b.optionalPotionCount) {
            return 13;
        }
        if (a.strategicSold != b.strategicSold) {
            return 14;
        }
        if (a.features.enemyHp != b.features.enemyHp) {
            return 15;
        }
        if (Double.compare(a.features.score, b.features.score) != 0) {
            return 16;
        }
        if (a.features.actionCount != b.features.actionCount) {
            return 17;
        }
        return -1;
    }

    private static int orMax(Integer value) {
        return value == null ? Integer.MAX_VALUE : value.intValue();
    }
}
