package io.github.youqing.sts1solver.patches;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补丁清单必须能自己发现所有补丁，且每个补丁的目标方法都要真的存在。
 *
 * <p>这条测试的价值：以后谁把某个补丁的目标方法写错/写了个不存在的方法名，
 * 构建阶段就会红，而不是等到游戏里「某个功能没反应」再排查。
 */
class PatchManifestTest {

    @Test
    void discoversEveryAnnotatedPatch() throws Exception {
        List<String> lines = PatchManifest.collect();

        assertFalse(lines.isEmpty(), "should find at least one @SpirePatch class");
        assertTrue(contains(lines, "UpdatePatch"), "UpdatePatch missing: " + lines);
        assertTrue(contains(lines, "CardPlayPatch"), "CardPlayPatch missing: " + lines);
        assertTrue(contains(lines, "GridPatch"), "GridPatch missing: " + lines);
    }

    @Test
    void everyPatchTargetsAnExistingMethod() throws Exception {
        List<String> lines = PatchManifest.collect();
        for (String line : lines) {
            assertFalse(line.contains("⚠"),
                    "patch targets a method that does not exist: " + line);
        }
    }

    private static boolean contains(List<String> lines, String needle) {
        for (String line : lines) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
