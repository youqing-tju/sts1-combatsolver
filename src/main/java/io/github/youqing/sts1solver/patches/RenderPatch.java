package io.github.youqing.sts1solver.patches;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

import io.github.youqing.sts1solver.ui.SolverPanel;

/**
 * Draws the solver panel after the dungeon frame has been rendered.
 * Verified: CardCrawlGame.render() calls AbstractDungeon.render(SpriteBatch) once per frame.
 */
public final class RenderPatch {

    private RenderPatch() {
    }

    @SpirePatch(clz = AbstractDungeon.class, method = "render", paramtypez = {SpriteBatch.class})
    public static class AfterRender {

        @SpirePostfixPatch
        public static void Postfix(AbstractDungeon __instance, SpriteBatch sb) {
            SolverPanel.render(sb);
        }
    }
}
