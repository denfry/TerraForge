package dev.terraforge.cli.progress;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProgressReporterTest {

    @Test
    void saysNothingUntilTheIntervalHasPassed() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ProgressReporter reporter = new ProgressReporter(print(buffer), "Fetched", 100, true);

        for (int i = 0; i < 100; i++) {
            reporter.step(1024);
        }

        // A hundred instant steps are one burst of work, not a hundred lines of log.
        assertThat(text(buffer)).isEmpty();
    }

    @Test
    void aZeroTotalDisablesReportingEntirely() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ProgressReporter reporter = new ProgressReporter(print(buffer), "Prepared", 0, false);

        reporter.step();
        reporter.finish();

        assertThat(text(buffer)).isEmpty();
    }

    @Test
    void finishAddsNoLineWhenNothingWasEverReported() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ProgressReporter reporter = new ProgressReporter(print(buffer), "Fetched", 10, true);

        reporter.step();
        reporter.finish();

        assertThat(text(buffer)).isEmpty();
    }

    @Test
    void formatsBytesInTheUnitsAnOperatorReads() {
        assertThat(ProgressReporter.humanBytes(512)).isEqualTo("512 B");
        assertThat(ProgressReporter.humanBytes(1536)).isEqualTo("1.5 KiB");
        assertThat(ProgressReporter.humanBytes(80L * 1024 * 1024 * 1024)).isEqualTo("80.0 GiB");
    }

    /** The point of the format: a multi-hour wait reads as hours, not as 8,412 seconds. */
    @Test
    void formatsDurationsCoarsely() {
        assertThat(ProgressReporter.humanDuration(Duration.ofSeconds(45))).isEqualTo("45s");
        assertThat(ProgressReporter.humanDuration(Duration.ofSeconds(125))).isEqualTo("2m05s");
        assertThat(ProgressReporter.humanDuration(Duration.ofSeconds(8412))).isEqualTo("2h20m");
    }

    @Test
    void negativeDurationsDoNotProduceNonsense() {
        assertThat(ProgressReporter.humanDuration(Duration.ofSeconds(-5))).isEqualTo("0s");
    }

    private static PrintStream print(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    private static String text(ByteArrayOutputStream buffer) {
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
