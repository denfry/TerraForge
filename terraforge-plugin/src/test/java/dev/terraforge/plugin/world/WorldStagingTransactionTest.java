package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldStagingTransactionTest {
    @TempDir Path temporaryDirectory;
    @Test void keepsOriginalBytesInBackups() throws Exception {
        Path target = temporaryDirectory.resolve("server.properties"); Files.writeString(target, "level-name=spawn\n");
        Path backups = temporaryDirectory.resolve("backups");
        new WorldStagingTransaction().commit(List.of(new PlannedEdit(target, Files.readAllBytes(target), "level-name=earth\n".getBytes(StandardCharsets.UTF_8))), backups);
        assertThat(Files.readString(target)).isEqualTo("level-name=earth\n");
        assertThat(Files.readString(backups.resolve("000.bak"))).isEqualTo("level-name=spawn\n");
    }

    @Test void retainsBackupsAfterCommitUntilTheManifestReachesReady() throws Exception {
        Path target = temporaryDirectory.resolve("server.properties"); Files.writeString(target, "level-name=spawn\n");
        Path backups = temporaryDirectory.resolve("backups");

        new WorldStagingTransaction().commit(List.of(new PlannedEdit(target, Files.readAllBytes(target),
                "level-name=earth\n".getBytes(StandardCharsets.UTF_8))), backups);

        // Staging only reaches PENDING_RESTART here; nothing in the transaction deletes backups,
        // so they must still be recoverable for as long as the manifest remains pre-READY.
        assertThat(Files.readString(backups.resolve("000.bak"))).isEqualTo("level-name=spawn\n");
        assertThat(Files.exists(backups.resolve("000.bak"))).isTrue();
    }

    @Test void refusesToOverwriteAFileChangedAfterPlanning() throws Exception {
        Path target = temporaryDirectory.resolve("server.properties");
        byte[] plannedOriginal = "level-name=spawn\n".getBytes(StandardCharsets.UTF_8);
        Files.writeString(target, "level-name=operator-change\n");

        assertThatThrownBy(() -> new WorldStagingTransaction().commit(List.of(new PlannedEdit(
                target, plannedOriginal, "level-name=earth\n".getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(target)).isEqualTo("level-name=operator-change\n");
    }
}
