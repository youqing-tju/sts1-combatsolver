package io.github.youqing.sts1solver.patches;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import io.github.youqing.sts1solver.diag.Trace;

/**
 * 回合结束（end-of-turn）状态机探针 —— **只读日志，不改任何游戏状态**。
 *
 * <p>为什么需要它：2026-10-31 真机反馈「灼烧 / 多层护甲 / 奥利哈刚 都不触发，但手点结束回合就正常」。
 * 这三样分属卡牌 / 能力 / 遗物三种机制，却在同一时刻一起失效 —— 症状指向**同一个入口**：
 * 原版 {@code AbstractRoom.endTurn()}（{@code hook phase} 见 {@code HookPhase.END_OF_TURN}）。
 *
 * <p>而这条链的启动条件写在 {@code AbstractPlayer.update()} 的字节码里（逐条核实）：
 *
 * <pre>
 * if (player.endTurnQueued) {
 *     if (actionManager.cardQueue.isEmpty() &amp;&amp; !actionManager.hasControl) {
 *         player.endTurnQueued = false;
 *         player.isEndingTurn = true;          // ← 只有这两个都满足才会置上
 *     }
 * } else { ...正常的出牌输入... }
 *
 * // AbstractRoom.update():  if (player.isEndingTurn) this.endTurn();
 * </pre>
 *
 * <p>所以只要「置 {@code endTurnQueued} 的那一刻」手牌队列非空、或还有动作在执行，
 * {@code endTurn()} 就**根本不会启动** —— 这一回合所有回合末效果（灼烧、多层护甲、山铜、
 * 金属化、香炉……）会**一起静默丢失**。反过来，手点按钮能触发、AUTO 不能，
 * 差别只能出在「谁在什么时机把 {@code endTurnQueued} 置 true」。
 *
 * <h2>手点按钮实际做了 5 件事（{@code EndTurnButton.disable(true)} 字节码）</h2>
 * <ol>
 *   <li>{@code InputHelper.moveCursorToNeutralPosition()}</li>
 *   <li>{@code actionManager.addToBottom(new NewQueueCardAction())} ← 先把挂着的牌收尾</li>
 *   <li>{@code enabled = false} / {@code hb.hovered = false} / {@code isGlowing = false}</li>
 *   <li>{@code player.endTurnQueued = true}  ← 我们只做了这一件</li>
 *   <li>{@code player.releaseCard()}（会顺手清 {@code justClickedLeft / isMouseDown / inSingleTargetMode}）</li>
 * </ol>
 *
 * <p>本探针就是要把这个时序差打成可读的日志：每次「与回合结束相关的布尔量」变化时打一行，
 * 另外在 {@code endTurnQueued} 为真期间记录 {@code cardQueue/actions} 的规模变化。
 * 有它就能一句话判定：是入口没启动，还是启动了但手牌已经空了。
 */
public final class EndTurnProbe {

    /** 上一次打印的签名；只有签名变化才落盘，避免每帧刷日志。 */
    private static String lastSignature = "";

    private EndTurnProbe() {
    }

    /** 主线程每帧调用（放在 {@code AutoPilot.tick()} 之前，所以看到的是我们「动手前」的状态）。 */
    public static void tick() {
        try {
            AbstractPlayer player = AbstractDungeon.player;
            GameActionManager am = AbstractDungeon.actionManager;
            if (player == null || am == null) {
                return;
            }
            boolean queued = player.endTurnQueued;
            boolean ending = player.isEndingTurn;
            boolean control = am.hasControl;
            boolean turnEnded = am.turnHasEnded;

            int cardQueue = am.cardQueue == null ? -1 : am.cardQueue.size();
            int actions = am.actions == null ? -1 : am.actions.size();
            // 只在「queued 期间」把队列规模纳入签名：否则一次抽牌/出牌就刷一行，日志会被淹
            String signature = queued + "|" + ending + "|" + control + "|" + turnEnded
                    + "|" + (queued ? cardQueue + "/" + actions : "-");
            if (signature.equals(lastSignature)) {
                return;
            }
            lastSignature = signature;

            int hand = player.hand == null ? -1 : player.hand.size();
            int energy = EnergyPanel.totalCount;
            int limbo = player.limbo == null ? -1 : player.limbo.size();

            Trace.info("EOT q=" + (queued ? 1 : 0)
                    + " ending=" + (ending ? 1 : 0)
                    + " hasControl=" + (control ? 1 : 0)
                    + " turnEnded=" + (turnEnded ? 1 : 0)
                    + " | cardQueue=" + cardQueue
                    + " actions=" + actions
                    + " limbo=" + limbo
                    + " hand=" + hand
                    + " e=" + energy
                    + " turn=" + am.turn
                    + "  " + verdict(queued, ending, control, cardQueue, actions, hand));
        } catch (Throwable t) {
            // 探针自身绝不能影响游戏
            Trace.warn("EOT probe failed: " + t);
        }
    }

    /** 把当前组合翻译成一句人话，省得每次读日志还要在脑子里跑一遍状态机。 */
    private static String verdict(boolean queued, boolean ending, boolean control,
                                  int cardQueue, int actions, int hand) {
        if (queued && !ending) {
            if (cardQueue > 0) {
                return "<- 卡在 cardQueue 非空：endTurn() 不会启动";
            }
            if (actions > 0) {
                return "<- 卡在 actions 非空";
            }
            if (control) {
                return "<- 卡在 hasControl=true";
            }
            return "<- 条件已满足，下一帧应变 ending=1";
        }
        if (ending) {
            return "<- endTurn() 应已启动（这一帧的回合末效果该结算了）";
        }
        return "";
    }

    /** 战斗边界清状态，避免「上一场的签名」把这一场的第一行吃掉。 */
    public static void reset() {
        lastSignature = "";
    }
}
