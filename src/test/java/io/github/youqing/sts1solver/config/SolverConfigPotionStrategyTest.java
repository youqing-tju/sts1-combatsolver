package io.github.youqing.sts1solver.config;

import io.github.youqing.sts1solver.search.PotionStrategySnapshot;
import io.github.youqing.sts1solver.search.SolverPotionDirective;
import io.github.youqing.sts1solver.search.SolverPotionPolicy;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 药水策略的持久化。
 *
 * <p>两条底线：
 * <ol>
 *   <li><b>旧配置不能炸</b>：没有这个键 / 值被写坏 → 退回「全 Smart、无覆盖」，而不是抛异常；</li>
 *   <li><b>不能碰用户真实配置</b>：所有落盘测试都在临时目录里做（{@code config.properties}
 *       是玩家正在用的那份，测试写坏了会直接影响他的下一局）。</li>
 * </ol>
 */
class SolverConfigPotionStrategyTest {

    private static File tempConfig(String content) throws Exception {
        File dir = Files.createTempDirectory("sts1-potion-config").toFile();
        File file = new File(dir, "config.properties");
        if (content != null) {
            Properties props = new Properties();
            props.setProperty(SolverConfig.KEY_POTION_STRATEGY, content);
            FileOutputStream out = new FileOutputStream(file);
            try {
                props.store(out, "test");
            } finally {
                out.close();
            }
        }
        return file;
    }

    private static String read(File file) throws Exception {
        if (!file.isFile()) {
            return null;                    // 没有这个键 = 旧配置
        }
        Properties props = new Properties();
        FileInputStream in = new FileInputStream(file);
        try {
            props.load(in);
        } finally {
            in.close();
        }
        return props.getProperty(SolverConfig.KEY_POTION_STRATEGY);
    }

    @Test
    void legacyConfigDefaultsToSmartWithoutOverrides() {
        PotionStrategySnapshot snapshot = SolverConfig.parsePotionStrategy(null);
        assertEquals(SolverPotionPolicy.SMART, snapshot.globalPolicy(),
                "旧版本没有 potionStrategy 这个键 -> 默认 Smart");
        assertEquals(0, snapshot.storageDirectiveCount());
        assertTrue(snapshot.allowsExplicitUse(0, "Fire Potion"));
    }

    @Test
    void corruptedConfigValueFallsBackToDefaults() {
        PotionStrategySnapshot snapshot = SolverConfig.parsePotionStrategy(
                "policy=NONSENSE;directive=0:Fire Potion:??");
        assertEquals(SolverPotionPolicy.SMART, snapshot.globalPolicy(),
                "读不懂的档位退回 Smart（不是退回 Disabled —— 那会让玩家一瓶药都喝不了）");
        assertEquals(0, snapshot.storageDirectiveCount());
        assertFalse(snapshot.hasForcedDirectives());
    }

    /**
     * 只有**每槽指令**能落盘；全局档位每次新设置都回到 Smart（蓝本 {@code [JsonIgnore]}）。
     */
    @Test
    void onlyDirectivesSurviveAPropertiesRoundTripOnDisk() throws Exception {
        PotionStrategySnapshot original = PotionStrategySnapshot.defaults()
                .withGlobalPolicy(SolverPotionPolicy.REQUIRE_AT_LEAST_ONE)
                .withDirective(0, "Fire Potion", SolverPotionDirective.FORCE)
                .withDirective(1, "Liquid Memories", SolverPotionDirective.DISABLED);

        File file = tempConfig(original.toStorageString());
        try {
            PotionStrategySnapshot loaded = SolverConfig.parsePotionStrategy(read(file));
            assertEquals(SolverPotionPolicy.SMART, loaded.globalPolicy(),
                    "全局档位不持久化，重新加载必须是 Smart");
            assertEquals(SolverPotionDirective.FORCE, loaded.directiveFor(0, "Fire Potion"),
                    "药水 ID 里有空格也要能原样回来");
            assertEquals(SolverPotionDirective.DISABLED,
                    loaded.directiveFor(1, "Liquid Memories"));
            assertEquals(original.toStorageString(), loaded.toStorageString(),
                    "每槽指令逐条一致（含顺序）");
        } finally {
            deleteTree(file.getParentFile());
        }
    }

    @Test
    void missingKeyAndEmptyValueBehaveTheSame() throws Exception {
        File absent = tempConfig(null);
        try {
            assertEquals(SolverPotionPolicy.SMART,
                    SolverConfig.parsePotionStrategy(read(absent)).globalPolicy());
        } finally {
            deleteTree(absent.getParentFile());
        }
        for (String raw : new String[]{"", "  ", ";;"}) {
            assertEquals(SolverPotionPolicy.SMART,
                    SolverConfig.parsePotionStrategy(raw).globalPolicy(),
                    "空值等价于没有键（输入：" + raw + "）");
        }
    }

    @Test
    void inMemoryStrategyIsAppliedEvenBeforeTheConfigExists() {
        // 没 load 过配置（单测/早期启动）时 apply 不能抛，也要能立刻被读到
        PotionStrategySnapshot custom = PotionStrategySnapshot.defaults()
                .withDirective(0, "Fire Potion", SolverPotionDirective.DISABLED);
        SolverConfig.applyPotionStrategy(custom);
        assertEquals(SolverPotionDirective.DISABLED,
                SolverConfig.potionStrategy().directiveFor(0, "Fire Potion"));
        SolverConfig.applyPotionStrategy(PotionStrategySnapshot.defaults());
        assertEquals(SolverPotionDirective.SMART,
                SolverConfig.potionStrategy().directiveFor(0, "Fire Potion"));
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File kid : kids) {
                deleteTree(kid);
            }
        }
        f.delete();
    }
}
