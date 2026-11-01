package io.github.youqing.sts1solver.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线区能显示几行。
 *
 * <p>用户原话：「面板太小了，需要能让我一次性看到全局路线」。所以这里钉的是
 * **按屏幕高度算行数**，而不是写死 3 行（旧值只能看见前三回合）。
 */
class SolverPanelRouteLayoutTest {

    @Test
    void planRowLimitGrowsWithScreenHeight() {
        // 1080p / 720p 都应该能吃满硬上限（16 行），而不是旧的三行
        assertEquals(16, SolverPanel.planRowLimit(1080f, 30f, 6f));
        assertEquals(16, SolverPanel.planRowLimit(720f, 30f, 6f));
        assertTrue(SolverPanel.planRowLimit(1080f, 30f, 6f)
                > SolverPanel.planRowLimit(720f, 30f, 6f) - 1,
                "1080p 不该比 720p 少");
    }

    @Test
    void planRowLimitShrinksOnSmallScreensButKeepsAFloor() {
        assertEquals(5, SolverPanel.planRowLimit(400f, 30f, 6f));
        assertEquals(3, SolverPanel.planRowLimit(200f, 30f, 6f),
                "再小也要留 3 行，否则连「下一手」都看不见");
        assertEquals(3, SolverPanel.planRowLimit(1080f, 0f, 6f),
                "字体还没就绪（行高 0）时退回最小值，不能除零");
    }

    @Test
    void planRowLimitIsCappedAndNeverNegative() {
        assertEquals(16, SolverPanel.planRowLimit(4000f, 30f, 6f),
                "超大屏也不超过硬上限（面板会挡住战斗画面）");
        assertTrue(SolverPanel.planRowLimit(0f, 30f, 6f) >= 3);
    }
}
