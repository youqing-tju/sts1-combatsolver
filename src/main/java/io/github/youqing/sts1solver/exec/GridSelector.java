package io.github.youqing.sts1solver.exec;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.ui.buttons.GridSelectConfirmButton;

import io.github.youqing.sts1solver.diag.Trace;
import io.github.youqing.sts1solver.verify.AutoPilot;

/**
 * Drives card-selection grids (Dual Wield, Armaments, Warcry …).
 *
 * <p>⚠️ 时序是关键，这里踩过两次坑：
 * <ol>
 *   <li>不能把驱动挂在 {@code AbstractDungeon.update()}：界面打开时它不被调用（实测 0 次触发）。</li>
 *   <li>不能在整个界面 update 之后设 {@code hb.clicked}：{@code Hitbox.update()} 每帧都会重写
 *       这个字段，下一帧就被冲掉（实测 pick 了 42 次，牌一张没选上）。</li>
 * </ol>
 *
 * <p>正确窗口：<b>卡片自己 update 之后、界面循环读取之前</b> → 挂在
 * {@code AbstractCard.update()} 的 postfix 上。
 *
 * <p>确认按钮同理：它内部先 {@code hb.update()} 再判断 clicked，两者之间插不进去。
 * 所以用的是「临时把鼠标放到按钮上并伪造一次松开」——这是唯一能走游戏原始逻辑的办法，
 * 且只在这一帧内生效，postfix 里立刻还原。
 */
public final class GridSelector {

    private static boolean inputSaved;
    private static int savedX;
    private static int savedY;
    private static boolean savedRelease;
    /** 当前奖励界面的候选集合（换了一批就重新挑目标）。 */
    private static java.util.ArrayList<AbstractCard> rewardGroup;
    private static AbstractCard rewardTarget;
    /**
     * 已经点过的那个奖励界面（按 {@code rewardGroup} 的**实例**认）。
     *
     * <p>2026-10-30 真机：攻击药水的三选一被反复点了 **2989 次**（日志 50 秒刷屏），
     * 每次 {@code markReward} 都把 {@code rewardGroup} 清空又重算，界面却还没关 ——
     * 于是同一帧序列里一直重复「选牌 + 打日志」。这 50 秒里回合是停住的。
     */
    private static java.util.ArrayList<AbstractCard> rewardPicked;
    /** 同一个界面上最多重试多少次（点不动就放弃，别把回合卡死）。 */
    private static final int REWARD_PICK_RETRY_LIMIT = 120;
    private static int rewardPickAttempts;

    private GridSelector() {
    }

    private static GridCardSelectScreen activeGrid() {
        if (!AutoPilot.enabled() || !AbstractDungeon.isScreenUp) {
            return null;
        }
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID) {
            return null;
        }
        // 🔴 只接管战斗内的选牌。火堆升级（forUpgrade）、事件选择也用 GRID 界面，
        // 自动点确认会把玩家的选择吞掉。
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || room.phase != AbstractRoom.RoomPhase.COMBAT) {
            return null;
        }
        return AbstractDungeon.gridSelectScreen;
    }

    /** 「发现」这类 3 选 1 用的是奖励界面（CARD_REWARD），不是 GRID。 */
    private static com.megacrit.cardcrawl.screens.CardRewardScreen activeReward() {
        if (!AutoPilot.enabled() || !AbstractDungeon.isScreenUp) {
            return null;
        }
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.CARD_REWARD) {
            return null;
        }
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || room.phase != AbstractRoom.RoomPhase.COMBAT) {
            return null;     // 战斗结束后的选牌奖励不归我们管
        }
        com.megacrit.cardcrawl.screens.CardRewardScreen screen = AbstractDungeon.cardRewardScreen;
        if (screen == null || screen.discoveryCard != null) {
            rewardPicked = null;
            rewardPickAttempts = 0;
            return null;     // 已经选过了
        }
        if (screen.rewardGroup == null || screen.rewardGroup.isEmpty()) {
            return null;
        }
        return screen;
    }

    /** 卡片 update 之后调用：把「刚点过」标记写进去，供界面同一帧读取。 */
    public static void markClick(AbstractCard card) {
        try {
            if (card == null) {
                return;
            }
            if (activeGrid() != null) {
                markGrid(card);
                return;
            }
            if (activeReward() != null) {
                markReward(card);
            }
        } catch (Throwable t) {
            Trace.error("grid mark failed", t);
        }
    }

    private static void markGrid(AbstractCard card) {
        GridCardSelectScreen grid = activeGrid();
        if (grid == null || grid.confirmScreenUp) {
            return;
        }
        if (grid.targetGroup == null || grid.targetGroup.group == null) {
            return;
        }
        if (!grid.targetGroup.group.contains(card)) {
            return;
        }
        if (grid.selectedCards != null && grid.selectedCards.contains(card)) {
            return;
        }
        card.hb.clicked = true;
        // 记下实机选中的是哪张，对拍重放时要用同一个选择
        io.github.youqing.sts1solver.verify.ShadowTracker.onChoicePicked(card);
        Trace.info("GRID pick " + card.cardID + (card.upgraded ? "+" : ""));
    }

    /**
     * 「发现」的三选一：点哪张由影子估值决定，只点选中那张。
     *
     * <p>{@code AbstractCard.update()} 的 postfix 对**每张**候选都会调进来，
     * 所以必须先算出唯一目标再点，否则三张一起点。
     */
    private static void markReward(AbstractCard card) {
        com.megacrit.cardcrawl.screens.CardRewardScreen screen = activeReward();
        if (screen == null || !screen.rewardGroup.contains(card)) {
            return;
        }
        // 同一个界面点过一次就不再点（除非界面没关、且还没超过重试上限）
        if (rewardPicked == screen.rewardGroup) {
            if (rewardPickAttempts++ < REWARD_PICK_RETRY_LIMIT) {
                return;
            }
            // 超过上限：多半是这张牌点不动，放弃这一格，避免整场卡住
            return;
        }
        if (rewardGroup != screen.rewardGroup) {
            rewardGroup = screen.rewardGroup;
            rewardTarget = chooseRewardCard(screen.rewardGroup);
        }
        if (card != rewardTarget) {
            return;
        }
        int index = screen.rewardGroup.indexOf(card);
        card.hb.clicked = true;
        // 这个下标是「生成列表里的第几张」，不是手牌下标
        io.github.youqing.sts1solver.verify.ShadowTracker.onGeneratedChoicePicked(index);
        io.github.youqing.sts1solver.verify.ShadowTracker.onChoiceConfirmed();
        Trace.info("REWARD pick #" + index + " " + card.cardID + (card.upgraded ? "+" : ""));
        rewardPicked = screen.rewardGroup;
        rewardPickAttempts = 0;
        rewardGroup = null;
        rewardTarget = null;
    }

    /** 用影子估一下「拿到哪张最好」。没有快照时退回第一张。 */
    private static AbstractCard chooseRewardCard(
            java.util.ArrayList<AbstractCard> options) {
        io.github.youqing.sts1solver.sim.SimState base =
                io.github.youqing.sts1solver.verify.ShadowTracker.snapshotForEvaluation();
        if (base == null) {
            return options.get(0);
        }
        AbstractCard best = options.get(0);
        long bestScore = Long.MIN_VALUE;
        for (AbstractCard option : options) {
            io.github.youqing.sts1solver.sim.SimState probe = base.copy();
            probe.hand.add(new io.github.youqing.sts1solver.sim.SimCard(option.cardID,
                    option.upgraded, 0, option.baseDamage,
                    option.type == null ? "" : option.type.name()));
            long score = io.github.youqing.sts1solver.search.BeamSolver.evaluate(probe);
            if (score > bestScore) {
                bestScore = score;
                best = option;
            }
        }
        return best;
    }

    /** 确认按钮 update 之前调用：伪造一次「在这个按钮上松开鼠标」。 */
    public static void prepareConfirm(GridSelectConfirmButton button) {
        try {
            GridCardSelectScreen grid = activeGrid();
            if (grid == null || button == null || button.isDisabled || !grid.confirmScreenUp) {
                return;
            }
            if (inputSaved) {
                return;
            }
            savedX = InputHelper.mX;
            savedY = InputHelper.mY;
            savedRelease = InputHelper.justReleasedClickLeft;
            inputSaved = true;

            InputHelper.mX = (int) (button.hb.x + button.hb.width / 2f);
            InputHelper.mY = (int) (button.hb.y + button.hb.height / 2f);
            InputHelper.justReleasedClickLeft = true;
            Trace.info("GRID confirm");
        } catch (Throwable t) {
            Trace.error("grid confirm failed", t);
        }
    }

    /** 确认按钮 update 之后调用：还原输入状态，避免污染同一帧的其他判断。 */
    public static void restoreInput() {
        if (!inputSaved) {
            return;
        }
        inputSaved = false;
        InputHelper.mX = savedX;
        InputHelper.mY = savedY;
        InputHelper.justReleasedClickLeft = savedRelease;
    }
}
