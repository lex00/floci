package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.smallrye.config.common.utils.StringUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the spelling of the IAM enforcement config key wherever it is documented.
 *
 * <p>The class comment on {@link IamEnforcementFilter} named it {@code floci.iam.enforcement-enabled}
 * and so did the CloudTrail service page, but {@code EmulatorConfig} nests the toggle under
 * {@code services}, so only {@code floci.services.iam.enforcement-enabled} binds to anything.
 * Setting the documented name is not a startup error; it is simply ignored, enforcement stays
 * off and every request is allowed. That failure mode is silent, which is how lex00/floci#213
 * spent a session believing enforcement was on.
 */
class IamEnforcementConfigKeyDocumentationTest {

    private static final String WRONG_KEY = "floci.iam.enforcement-enabled";

    private static final List<Path> SCANNED_ROOTS = List.of(
            Path.of("src/main/java"),
            Path.of("docs"));

    /**
     * Derives the key from the config interfaces rather than restating it, so the guard below
     * keeps pointing at whatever {@code EmulatorConfig} actually binds.
     */
    @Test
    void theEnforcementToggleBindsUnderServicesIam() throws NoSuchMethodException {
        Method services = EmulatorConfig.class.getMethod("services");
        Method iam = services.getReturnType().getMethod("iam");
        Method enforcementEnabled = iam.getReturnType().getMethod("enforcementEnabled");

        String key = "floci"
                + "." + StringUtil.skewer(services.getName())
                + "." + StringUtil.skewer(iam.getName())
                + "." + StringUtil.skewer(enforcementEnabled.getName());

        assertEquals("floci.services.iam.enforcement-enabled", key);
    }

    @Test
    void noSourceOrDocMentionsTheKeyWithoutTheServicesSegment() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path root : SCANNED_ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".java") && !name.endsWith(".md")) {
                        continue;
                    }
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains(WRONG_KEY)) {
                            offenders.add(file + ":" + (i + 1));
                        }
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "The IAM enforcement toggle is floci.services.iam.enforcement-enabled. The shorter "
                        + WRONG_KEY + " binds to nothing and leaves enforcement off with no warning, "
                        + "so it must not appear in sources or docs. Found at: " + offenders);
    }
}
