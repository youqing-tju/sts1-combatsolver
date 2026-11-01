package io.github.youqing.sts1solver.verify;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 蓝本没有的**本地路线控制模式**必须真的不存在，而不是「默认关掉、还留着开关」。
 *
 * <p>两条被移除的机制各自都是自创：
 * <ol>
 *   <li><b>有界 AUTO（点 Tn 打到 Tn 末）</b> —— 蓝本的路线行 {@code MouseFilter = Ignore}，
 *       根本不可点（{@code UI/SolverOverlay.cs}）；它控制部署的是 `FullAutoEnabled` 与
 *       `RouteFrozen` 两个独立开关，没有「打到第几回合」这种东西。</li>
 *   <li><b>伪 Adopt / 伪 Freeze</b> —— 蓝本的 {@code AdoptRoute}（{@code SolverController.cs:128-158}）
 *       是搜索接管，{@code RouteFrozen} 是另一套 UI 开关（默认 false、设 true 会关 FullAuto
 *       并取消 deferred search）。我们两个都没完整复刻，于是两个都不做半成品，
 *       而不是把「按序打到底」冒充成其中之一。</li>
 * </ol>
 *
 * <p>用反射钉「不存在」而不是删掉测试：删了就没有任何东西阻止它被重新加回来。
 * 这份测试就是审计表里「蓝本没有 → 移除」那一行的可执行形式。
 */
class NoLocalRouteControlTest {

    /** 这些名字一旦重新出现，就说明自创的控制模式又回来了。 */
    private static final String[] REMOVED_AUTO_PILOT_METHODS = {
            "runUntil",          // 点路线行 -> 有界 AUTO
            "stopAfterTurn",
            "armBoundedTarget",
            "shouldStopAt",
    };

    /** {@code RoutePreview.Turn} 上「这一行能不能作为停止目标」的判定也必须消失。 */
    private static final String[] REMOVED_PREVIEW_METHODS = {
            "canTarget",
    };

    @Test
    void autoPilotExposesNoBoundedTargetApi() {
        for (String name : REMOVED_AUTO_PILOT_METHODS) {
            assertEquals(null, findMethod(AutoPilot.class, name),
                    "AutoPilot." + name + " 是蓝本没有的本地机制，必须整体移除（不是默认关）");
        }
    }

    @Test
    void routePreviewHasNoTargetingApi() {
        for (String name : REMOVED_PREVIEW_METHODS) {
            assertEquals(null, findMethod(RoutePreview.Turn.class, name),
                    "RoutePreview.Turn." + name + " 只服务于「点 Tn」这个自创入口，必须移除");
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }
}
