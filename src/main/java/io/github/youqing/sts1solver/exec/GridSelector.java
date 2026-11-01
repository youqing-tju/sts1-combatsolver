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
     * 这个界面已经点了几帧（按 {@code rewardGroup} 的**实例**认，换界面就清零）。
     *
     * <p>2026-10-30 真机：攻击药水的三选一被反复点了 **2989 次**（日志 50 秒刷屏），
     * 因为每次 {@code markReward} 都把 {@code rewardGroup} 清空又重算。现在目标只在
     * 「换了一批候选」时算一次，点不动就**继续点同一张**（只按帧数记日志），不会重算。
     */
    private static int rewardPickAttempts;
    /** 重试日志的间隔（帧）；只在卡住时才会用到。 */
    private static final int REWARD_PICK_LOG_EVERY = 60;

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
            com.megacrit.cardcrawl.screens.CardRewardScreen reward = activeReward();
            if (reward != null) {
                markReward(reward, card);
            } else {
                resetRewardPick();
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
    /**
     * 「发现」的三选一：点哪张由影子估值决定，只点选中那张。
     *
     * <p>{@code AbstractCard.update()} 的 postfix 对**每张**候选都会调进来，
     * 所以必须先算出唯一目标再点，否则三张一起点。
     *
     * <p>🔴 只把 {@code hb.clicked} 置 true **是不够的**：{@code CardRewardScreen.cardSelectUpdate()}
     * 先算出「鼠标当前悬停的那张」（{@code hb.hovered}），再读**那一张**的 {@code hb.clicked}
     * （字节码 0-70 / 73-88 / 142-153）。鼠标不在目标卡上时，置了也没人看 ——
     * 2026-10-31 22:13 真机就是这样把「无色药水三选一」卡了 **9.1 秒**（22:13:01.195 →
     * 22:13:10.278 全无日志），直到人手点了一下才继续，那张 0 费牌等于白开。
     *
     * <p>所以这里按本类已有的做法（见 {@link #prepareConfirm}）把虚拟光标挪到卡面中心：
     * 紧跟其后的 {@code updateHoverLogic()} 会用这个坐标算出 {@code hovered=true}，
     * 屏幕循环于是读到我们置的 {@code clicked}。{@code InputHelper.update()} 每帧都会用真实鼠标
     * 重写 {@code mX/mY}，所以这个覆盖只活当前这一帧，不会污染别处。
     */
    private static void markReward(com.megacrit.cardcrawl.screens.CardRewardScreen screen,
                                   AbstractCard card) {
        if (!screen.rewardGroup.contains(card)) {
            return;
        }
        if (rewardGroup != screen.rewardGroup) {
            rewardGroup = screen.rewardGroup;
            rewardTarget = chooseRewardCard(screen.rewardGroup);
            rewardPickAttempts = 0;
        }
        if (card != rewardTarget) {
            return;
        }
        int index = screen.rewardGroup.indexOf(card);
        InputHelper.mX = (int) card.hb.cX;
        InputHelper.mY = (int) card.hb.cY;
        card.hb.clicked = true;
        rewardPickAttempts++;
        if (rewardPickAttempts == 1) {
            // 这个下标是「生成列表里的第几张」，不是手牌下标
            io.github.youqing.sts1solver.verify.ShadowTracker.onGeneratedChoicePicked(index);
            io.github.youqing.sts1solver.verify.ShadowTracker.onChoiceConfirmed();
            Trace.info("REWARD pick #" + index + " " + card.cardID + (card.upgraded ? "+" : "")
                    + "  cursor->" + (int) card.hb.cX + "," + (int) card.hb.cY);
        } else if (rewardPickAttempts % REWARD_PICK_LOG_EVERY == 0) {
            Trace.warn("REWARD pick still blocked after " + rewardPickAttempts
                    + " frames (" + card.cardID + ") — retrying on the same card");
        }
    }

    /** 界面消失（选完/被关掉）：清掉这一轮的目标，下一批候选重新估。 */
    private static void resetRewardPick() {
        rewardGroup = null;
        rewardTarget = null;
        rewardPickAttempts = 0;
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
