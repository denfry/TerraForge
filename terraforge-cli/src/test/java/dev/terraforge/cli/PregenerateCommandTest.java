package dev.terraforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PregenerateCommandTest {

    @TempDir
    Path temporaryDirectory;

    private Path config;

    @BeforeEach
    void writeDefaultConfiguration() throws Exception {
        config = temporaryDirectory.resolve("terraforge.yml");
        try (var defaults = PregenerateCommand.class.getResourceAsStream("/terraforge.yml")) {
            Files.write(config, defaults.readAllBytes());
        }
    }

    private PregenerateCommand command() {
        PregenerateCommand command = new PregenerateCommand();
        command.config = config;
        return command;
    }

    /** Runs the command with stdout captured, so the plan itself can be asserted on. */
    private static String output(Supplier<Integer> run, int expectedExitCode) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            assertThat(run.get()).isEqualTo(expectedExitCode);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void plansASquareRadiusAroundTheOrigin() {
        PregenerateCommand command = command();
        command.radius = 160;

        String plan = output(command::call, 0);

        // 321 blocks per axis at 16 blocks per chunk: chunks -10..10 on both axes.
        assertThat(plan).contains("Chunks:       X -10..10, Z -10..10 = 441 chunks");
        assertThat(plan).contains("/earth pregenerate 10");
    }

    @Test
    void plansTheConfiguredTestRegion() {
        PregenerateCommand command = command();
        command.testRegion = true;

        String plan = output(command::call, 0);

        assertThat(plan).contains("World:        earth");
        assertThat(plan).contains("chunks");
        assertThat(plan).contains("Region files:");
    }

    @Test
    void writesNothingAtAll() {
        PregenerateCommand command = command();
        command.radius = 64;

        String plan = output(command::call, 0);

        assertThat(plan).contains("Nothing was written.");
        assertThat(temporaryDirectory.toFile().list()).containsExactly("terraforge.yml");
    }

    @Test
    void saysSoWhenNoElevationIsPrepared() {
        PregenerateCommand command = command();
        command.radius = 64;

        assertThat(output(command::call, 0))
                .contains("does not exist -- the whole area would be generated flat");
    }

    @Test
    void reportsMissingTilesAgainstAPreparedDirectory() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("data/dem"));
        PregenerateCommand command = command();
        command.radius = 64;

        String plan = output(command::call, 0);

        // The origin 51N 10E sits on a tile corner, so a 64-block radius touches all four.
        assertThat(plan).contains("0 of 4 tile(s) prepared");
        assertThat(plan).contains("N51E010.tfdem");
        assertThat(plan).contains("terraforge prepare-region");
    }

    @Test
    void requiresExactlyOneAreaSelector() {
        assertThat(command().call()).isEqualTo(64);

        PregenerateCommand both = command();
        both.radius = 10;
        both.testRegion = true;
        assertThat(both.call()).isEqualTo(64);
    }

    @Test
    void rejectsANonPositiveRadius() {
        PregenerateCommand command = command();
        command.radius = 0;

        assertThat(command.call()).isEqualTo(64);
    }

    @Test
    void reportsAMissingConfigurationFile() {
        PregenerateCommand command = new PregenerateCommand();
        command.config = temporaryDirectory.resolve("absent.yml");
        command.radius = 10;

        assertThat(command.call()).isEqualTo(66);
    }
}
