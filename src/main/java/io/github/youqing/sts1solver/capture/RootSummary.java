package io.github.youqing.sts1solver.capture;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

/**
 * M0 root-state readout. This is deliberately a plain string: M1 replaces it with a
 * structured {@code RootSnapshot} that can be rebuilt into a shadow combat state and
 * diffed field by field.
 *
 * <p>Everything here is read-only and runs on the main thread.
 */
public final class RootSummary {

    private RootSummary() {
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("act=").append(AbstractDungeon.actNum)
              .append(" floor=").append(AbstractDungeon.floorNum)
              .append(" asc=").append(AbstractDungeon.ascensionLevel);

            if (AbstractDungeon.player != null) {
                sb.append(" | hp=").append(AbstractDungeon.player.currentHealth)
                  .append('/').append(AbstractDungeon.player.maxHealth)
                  .append(" block=").append(AbstractDungeon.player.currentBlock)
                  .append(" energy=").append(EnergyPanel.totalCount);

                sb.append(" | hand=").append(size(AbstractDungeon.player.hand))
                  .append(" draw=").append(size(AbstractDungeon.player.drawPile))
                  .append(" discard=").append(size(AbstractDungeon.player.discardPile))
                  .append(" exhaust=").append(size(AbstractDungeon.player.exhaustPile));
            }

            MonsterGroup group = AbstractDungeon.getMonsters();
            if (group != null && group.monsters != null) {
                sb.append(" | monsters=").append(group.monsters.size()).append('[');
                for (int i = 0; i < group.monsters.size(); i++) {
                    AbstractMonster m = group.monsters.get(i);
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(m.name).append(' ').append(m.currentHealth).append('/').append(m.maxHealth);
                }
                sb.append(']');
            }

            sb.append(" | rng card=").append(counter(AbstractDungeon.cardRandomRng))
              .append(" monster=").append(counter(AbstractDungeon.monsterRng))
              .append(" ai=").append(counter(AbstractDungeon.aiRng))
              .append(" shuffle=").append(counter(AbstractDungeon.shuffleRng))
              .append(" misc=").append(counter(AbstractDungeon.miscRng));
        } catch (Throwable t) {
            sb.append(" | summary failed: ").append(t);
        }
        return sb.toString();
    }

    private static int size(com.megacrit.cardcrawl.cards.CardGroup group) {
        return group == null ? -1 : group.size();
    }

    private static int counter(com.megacrit.cardcrawl.random.Random rng) {
        return rng == null ? -1 : rng.counter;
    }
}
