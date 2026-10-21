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
                ShadowTracker.onCardPlayed(card, target);
            } catch (Throwable t) {
                Trace.error("card play hook failed", t);
            }
        }
    }
}
