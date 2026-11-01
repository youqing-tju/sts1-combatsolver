package io.github.youqing.sts1solver.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.monsters.AbstractMonster;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.verify.ShadowTracker;

/**
 * Records what the player played, before it resolves. This is the input side of the
 * A2 differential harness — read-only, no state is touched here.
 */
public final class CardPlayPatch {

    private CardPlayPatch() {
    }

    @SpirePatch(clz = AbstractPlayer.class, method = "useCard",
            paramtypez = {AbstractCard.class, AbstractMonster.class, int.class})
    public static class BeforeUse {

        @SpirePrefixPatch
        public static void Prefix(AbstractPlayer __instance, AbstractCard card,
                                  AbstractMonster target, int energyOnUse) {
            try {
                String key = card.cardID + (card.upgraded ? "+" : "");
                if (!io.github.youqing.sts1solver.mirror.MirrorRegistry.supports(key)) {
                    // 只在未镜像的牌上打一行：若玩家点了牌却没有这行，说明点击根本没到 useCard
                    // （是游戏侧拒绝，不是我们的钩子出问题）
                    Trace.info("PLAY unmirrored " + key);
                }
                // 先告诉执行层「这张是不是我们排的」：不是就记一次手工输入分歧，
                // 那个回合的跨回合世界线不能再复用（否则会拿预测去套用户改过的局面）。
                io.github.youqing.sts1solver.verify.AutoPilot.onCardPlayedObserved(key);
                ShadowTracker.onCardPlayed(card, target);
            } catch (Throwable t) {
                Trace.error("card play hook failed", t);
            }
        }
    }
}
