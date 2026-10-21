package io.github.youqing.sts1solver.mirror;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The mirrored semantics of one card (one upgrade state). */
public final class CardMirror {

    public final String key;
    public final List<Effect> effects;
    /** 这张牌是否需要指定某个怪物（单目标攻击/减益）。搜索据此决定展开多少个目标。 */
    public final boolean targeted;
    /** 是否需要从手牌里挑一张（军备/战吼/烧牌契约）。 */
    public final boolean needsChoice;
    /** 打出后进消耗堆（虹吸 / 威吓 / 冲击波 / 铜墙铁壁 / 恶魔之火 …）。 */
    public final boolean exhaustsOnPlay;

    /** 选牌时从哪个牌堆里挑（手牌 / 弃牌堆 / 消耗堆）。 */
    public final ChoicePile choicePile;
    /** 费用随战斗进程变化（浴血奋战：每次损血降 1 费）。 */
    public final boolean costDropsWithHpLoss;

    /** 选牌来源。 */
    public enum ChoicePile {
        HAND, DISCARD, EXHAUST
    }

    public CardMirror(String key, List<Effect> effects) {
        this(key, effects, false);
    }

    public CardMirror(String key, List<Effect> effects, boolean exhaustsOnPlay) {
        this(key, effects, exhaustsOnPlay, ChoicePile.HAND, false);
    }

    public CardMirror(String key, List<Effect> effects, boolean exhaustsOnPlay,
                      ChoicePile choicePile, boolean costDropsWithHpLoss) {
        this.exhaustsOnPlay = exhaustsOnPlay;
        this.choicePile = choicePile;
        this.costDropsWithHpLoss = costDropsWithHpLoss;
        this.key = key;
        this.effects = Collections.unmodifiableList(new ArrayList<Effect>(effects));
        boolean needsTarget = false;
        for (Effect e : effects) {
            if (e.needsMonsterTarget()) {
                needsTarget = true;
                break;
            }
        }
        this.targeted = needsTarget;

        boolean needsPick = false;
        for (Effect e : effects) {
            if (e instanceof ChoiceEffect) {
                needsPick = true;
                break;
            }
        }
        this.needsChoice = needsPick;
    }

    @Override
    public String toString() {
        return key + effects;
    }
}
