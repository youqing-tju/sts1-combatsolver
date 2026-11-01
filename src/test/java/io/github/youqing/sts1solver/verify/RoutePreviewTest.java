package io.github.youqing.sts1solver.verify;

import io.github.youqing.sts1solver.search.RouteStep;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePreviewTest {
    private static RouteStep card(String id) {
        return new RouteStep(false, id, 0, -1);
    }

    private static RouteStep end() {
        return new RouteStep(true, null, -1, -1);
    }

    @Test
    void absoluteTurnsDoNotRenumberWhenExecutionConsumesSteps() {
        RouteStep bash = card("Bash");
        RouteStep strike = card("Strike");
        RoutePreview preview = RoutePreview.fromSteps(4,
                Arrays.asList(bash, strike, end(), card("Defend"), end()));

        assertEquals(4, preview.turns().get(0).absoluteTurn());
        assertEquals(5, preview.turns().get(1).absoluteTurn());
        assertEquals("T4 ▸ ▶Bash · Strike", preview.turns().get(0).text(bash));
        assertEquals("T4 ▸ Bash · ▶Strike", preview.turns().get(0).text(strike));
        assertEquals("T5 ▸ Defend", preview.turns().get(1).text(null));
    }

    /**
     * 没有 END_TURN 收尾的尾行照样要显示（它是这条路线的一部分），只是标成「没走到回合末」。
     *
     * <p>这里刻意不提供「点这一行打到这里的」语义：蓝本的路线行 {@code MouseFilter = Ignore}，
     * 没有任何以路线行为入口的停止控制。
     */
    @Test
    void trailingTurnWithoutEndTurnIsRenderedButNotConverged() {
        RoutePreview preview = RoutePreview.fromSteps(2,
                Arrays.asList(card("Strike"), end(), card("Defend")));

        assertEquals(2, preview.turns().size());
        assertTrue(preview.turns().get(0).endsTurn(), "T2 以 END_TURN 收尾 = 本回合走完");
        assertFalse(preview.turns().get(1).endsTurn(), "T3 没有 END_TURN = 计划没走到回合末");
        assertEquals("T3 ▸ Defend", preview.turns().get(1).text(null));
    }

    @Test
    void moreThanSixActionsKeepExistingCompactNotation() {
        RoutePreview preview = RoutePreview.fromSteps(1, Arrays.asList(
                card("A"), card("B"), card("C"), card("D"),
                card("E"), card("F"), card("G"), end()));

        assertEquals("T1 ▸ A · B · C · D · E · F · +1",
                preview.turns().get(0).text(null));
    }

    @Test
    void emptyAndConsecutiveEndTurnsAreWellDefined() {
        assertTrue(RoutePreview.fromSteps(1, Collections.<RouteStep>emptyList()).turns().isEmpty());
        RoutePreview preview = RoutePreview.fromSteps(3, Arrays.asList(end(), end()));
        assertEquals(2, preview.turns().size());
        assertEquals("T3 ▸ —", preview.turns().get(0).text(null));
        assertEquals(4, preview.turns().get(1).absoluteTurn());
    }
}
