package io.github.youqing.sts1solver.sim;

/**
 * 一次「伤害落地」的类型，对应原版 {@code DamageInfo.DamageType}。
 *
 * <p>原版三档的差别**不在伤害数字上，而在管线里谁认它**（全部按字节码核实）：
 *
 * <pre>
 *                吃格挡   鸟居(Torii)   烈焰屏障   无实体   钨棒(TungstenRod)
 * NORMAL           ✅        ✅            ✅        ✅        ✅
 * THORNS           ✅        ❌            ❌        ✅        ✅
 * HP_LOSS          ❌        ❌            ❌        ✅        ✅
 * </pre>
 *
 * <ul>
 *   <li>格挡：{@code AbstractCreature.decrementBlock} 第一件事就是
 *       {@code if (info.type == HP_LOSS) return damage;} —— 只有 HP_LOSS 跳过格挡。</li>
 *   <li>鸟居 / 烈焰屏障：字节码里显式排除 {@code THORNS} 与 {@code HP_LOSS}，
 *       并且都要求 {@code info.owner != null}。</li>
 *   <li>无实体：{@code if (damage > 1 && hasPower("IntangiblePlayer")) damage = 1;}
 *       **不看类型** —— 所以掉血也被压成 1。</li>
 *   <li>钨棒：{@code AbstractRelic.onLoseHpLast} 在 {@code damage()} 末尾**无分支**地跑一遍，
 *       所以三种类型都减 1。这也是「掉血也吃钨棒」的依据。</li>
 * </ul>
 *
 * <p>🔴 {@code LoseHPAction} 的实现是 {@code target.damage(new DamageInfo(source, amount, HP_LOSS))}
 * —— 掉血**不是**另一条管线，只是同一个 {@code damage()} 的一个类型值。所以影子里
 * 「掉血」也走 {@link CombatFlow#hitPlayer}，只是把类型传成 {@link #HP_LOSS}。
 */
public enum DamageKind {

    /** 普通攻击：三种触发全开。 */
    NORMAL,

    /** 荆棘 / 缠绕 / 苦痛之击这类「不是攻击的伤害」：吃格挡，但不触发鸟居与烈焰屏障。 */
    THORNS,

    /** 掉血（{@code LoseHPAction}）：**无视格挡**，其余与 {@link #THORNS} 相同。 */
    HP_LOSS
}
