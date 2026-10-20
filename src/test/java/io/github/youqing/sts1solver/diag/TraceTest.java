package io.github.youqing.sts1solver.diag;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void pointLogDirAtTemp() {
        System.setProperty("sts1solver.logDir", tempDir.toString());
    }

    @AfterAll
    static void releaseLogFile() {
        Trace.close();
    }

    @Test
    void writesLinesIntoConfiguredDirectory() throws Exception {
        Trace.init();
        Trace.info("hello from test");

        File file = new File(Trace.path());
        assertTrue(file.exists(), "log file should exist at " + Trace.path());

        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("hello from test"), "log should contain the message");
        assertTrue(content.contains("[INFO]"), "log should carry a level tag");
    }
}
