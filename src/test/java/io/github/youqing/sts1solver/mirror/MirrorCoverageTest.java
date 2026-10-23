package io.github.youqing.sts1solver.mirror;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 覆盖闸门：牌池里的**每一张牌、连同它的升级态**都必须有镜像。
 *
 * <p>为什么这条必须钉死：镜像是「牌名 → 效果」的查表，查不到不是"猜一猜"，
 * 而是 {@code CardPlayer.play} 抛 {@code ActionRejected}、搜索分支被**静默跳过** ——
 * 在 AI 眼里那张牌等于不存在。表现就是「手里明明有牌，却结束回合」。
 *
 * <p>2026-10-23 真机事故：{@code Body Slam+} / {@code Dark Embrace+} / {@code Entrench+}
 * 三个升级态没注册，AI 拿着 0 费 {@code Body Slam+} 直接点结束回合。
 * 当时是靠翻日志一句一句对出来的，所以补上这道闸。
 *
 * <p>已知例外必须写在这里，并且**每次加例外都要说明原因**。
 */
class MirrorCoverageTest {

    /** 已知不支持的牌（写清楚为什么，不要随便加）。 */
    private static final Set<String> KNOWN_UNSUPPORTED = new HashSet<String>(Arrays.asList(
            // 转化+：生成的是**已升级**的无色牌，PoolCatalog 还没有「生成升级态」这条路径。
            // 基础版已支持；升级版打不出会走 ActionRejected（不会算错）。
            "Transmutation+"
    ));

    @Test
    void everyCardInTheRedPoolIsMirroredInBothUpgradeStates() {
        assertPoolCovered("RedPool", ids(RedPool.CARDS));
    }

    @Test
    void everyCardInTheColorlessPoolIsMirroredInBothUpgradeStates() {
        assertPoolCovered("ColorlessPool", ids(ColorlessPool.CARDS));
    }

    private static List<String> ids(Object[][] cards) {
        List<String> out = new ArrayList<String>();
        for (Object[] row : cards) {
            out.add((String) row[0]);
        }
        return out;
    }

    private static void assertPoolCovered(String poolName, List<String> ids) {
        List<String> missing = new ArrayList<String>();
        for (String id : ids) {
            for (String key : new String[]{id, id + "+"}) {
                if (!MirrorRegistry.supports(key) && !KNOWN_UNSUPPORTED.contains(key)) {
                    missing.add(key);
                }
            }
        }
        if (!missing.isEmpty()) {
            fail(poolName + " 有 " + missing.size() + " 张牌没有镜像（搜索会把它们当成打不出）："
                    + missing);
        }
        assertTrue(true);
    }

    /** 例外表本身也要收敛：列进去的牌必须真的还不支持，否则就是忘了删。 */
    @Test
    void knownUnsupportedEntriesAreStillUnsupported() {
        for (String key : KNOWN_UNSUPPORTED) {
            assertTrue(!MirrorRegistry.supports(key),
                    key + " 已经支持了，应该从 KNOWN_UNSUPPORTED 里删掉");
        }
    }
}
