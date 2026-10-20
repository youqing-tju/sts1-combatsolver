package io.github.youqing.sts1solver;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build-chain smoke test: proves javac --release 8, JUnit 5 and resource packaging all work.
 * No game logic here on purpose.
 */
class BuildChainSmokeTest {

    @Test
    void toolchainRuns() {
        assertTrue(true);
    }

    @Test
    void modMetadataIsOnClasspath() {
        InputStream in = getClass().getClassLoader().getResourceAsStream("ModTheSpire.json");
        assertNotNull(in, "ModTheSpire.json should be packaged as a resource");
    }
}
