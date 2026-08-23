package dev.mja00.villagerLobotomizer.utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentPreservingYamlMigratorTest {
    private final CommentPreservingYamlMigrator migrator =
            new CommentPreservingYamlMigrator(Logger.getLogger("CommentPreservingYamlMigratorTest"));

    @Test
    void preservesNestedUserCommentsDuringMerge() throws IOException {
        String existingYaml = """
                # Existing Villager Lobotomizer config
                behavior:
                  #Keep user comment for nested value
                  always-active-names:
                    - Alice
                """;

        String defaultYaml = """
                behavior:
                  always-active-names:
                    - DefaultName
                  cooldown-seconds: 30
                """;

        String merged = migrator.mergeWithComments(existingYaml, defaultYaml);

        assertTrue(merged.contains("#Keep user comment for nested value"),
                () -> "Expected merged YAML to contain nested user comment:\n" + merged);
    }

    @Test
    void appliesDefaultCommentsToNewNestedFields() throws IOException {
        String existingYaml = """
                behavior:
                  always-active-names:
                    - Alice
                """;

        String defaultYaml = """
                behavior:
                  always-active-names:
                    - DefaultName
                  #Default cooldown comment
                  cooldown-seconds: 30
                """;

        String merged = migrator.mergeWithComments(existingYaml, defaultYaml);

        assertTrue(merged.contains("#Default cooldown comment"),
                () -> "Expected merged YAML to contain default nested comment:\n" + merged);
    }

    /**
     * Reproduces the config-version 6 -> 7 upgrade that introduces {@code restock-schedule}: an
     * existing server config predating the feature is merged against the real shipped default
     * config.yml (which now has the new section), confirming the section is added with its
     * default values intact and pre-existing settings are preserved.
     */
    @Test
    void addsRestockScheduleSectionOnUpgradeFromV6() throws IOException {
        String existingYaml = """
                config-version: 6
                always-active-names:
                  - "alwaysbrain"
                check-interval: 150
                inactive-check-interval: 150
                restock-interval: 999000
                restock-random-range: 0
                only-lobotomize-villagers-with-professions: false
                """;

        String defaultYaml = Files.readString(Path.of("src/main/resources/config.yml"));
        assertTrue(defaultYaml.contains("restock-schedule:"),
                "test assumes the shipped default config.yml already defines restock-schedule");

        String merged = migrator.mergeWithComments(existingYaml, defaultYaml);

        assertTrue(merged.contains("restock-schedule:"),
                () -> "Expected merged YAML to add the new restock-schedule section:\n" + merged);
        assertTrue(merged.contains("mode: interval"),
                () -> "Expected the new section's default mode:\n" + merged);
        assertTrue(merged.contains("\"08:00\"") && merged.contains("\"10:30\"") && merged.contains("\"13:00\""),
                () -> "Expected the new section's default times:\n" + merged);

        // Pre-existing, unrelated settings must survive the merge untouched.
        assertTrue(merged.contains("restock-interval: 999000"),
                () -> "Expected pre-existing restock-interval to be preserved:\n" + merged);
    }
}
