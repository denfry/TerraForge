package dev.terraforge.towny;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AsyncTownGeographyStoreTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path temporaryDirectory;

    private Path database;
    private List<String> errors;

    @BeforeEach
    void prepare() {
        database = temporaryDirectory.resolve("terraforge.db");
        errors = new CopyOnWriteArrayList<>();
    }

    private AsyncTownGeographyStore store() {
        return new AsyncTownGeographyStore(database, errors::add);
    }

    private static TownGeography town(UUID id, String name, double latitude, double longitude) {
        return new TownGeography(id, name, latitude, longitude, 120.5, 1, 2);
    }

    private List<String> rows(String column) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT " + column + " FROM town_geography ORDER BY town_name")) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    @Test
    void persistsAQueuedTownWithoutBlockingTheCaller() throws Exception {
        UUID id = UUID.randomUUID();
        try (AsyncTownGeographyStore store = store()) {
            assertThat(store.save(town(id, "Berlin", 52.52, 13.405))).isTrue();
            assertThat(store.awaitIdle(TIMEOUT)).isTrue();
        }

        assertThat(rows("town_name")).containsExactly("Berlin");
        assertThat(rows("latitude")).containsExactly("52.52");
        assertThat(errors).isEmpty();
    }

    @Test
    void aSecondWriteForTheSameTownUpdatesItInsteadOfDuplicating() throws Exception {
        UUID id = UUID.randomUUID();
        try (AsyncTownGeographyStore store = store()) {
            store.save(town(id, "Berlin", 52.52, 13.405));
            store.save(town(id, "Berlin Neu", 48.0, 11.0));
            assertThat(store.awaitIdle(TIMEOUT)).isTrue();
        }

        assertThat(rows("town_name")).containsExactly("Berlin Neu");
    }

    @Test
    void writesKeepTheOrderTheyWereQueuedIn() throws Exception {
        UUID id = UUID.randomUUID();
        try (AsyncTownGeographyStore store = store()) {
            store.save(town(id, "Renamed", 52.52, 13.405));
            store.delete(id);
            store.save(town(id, "Recreated", 40.0, 10.0));
            assertThat(store.awaitIdle(TIMEOUT)).isTrue();
        }

        assertThat(rows("town_name")).containsExactly("Recreated");
    }

    @Test
    void deleteRemovesTheRow() throws Exception {
        UUID id = UUID.randomUUID();
        try (AsyncTownGeographyStore store = store()) {
            store.save(town(id, "Gone", 52.52, 13.405));
            store.delete(id);
            assertThat(store.awaitIdle(TIMEOUT)).isTrue();
        }

        assertThat(rows("town_name")).isEmpty();
    }

    @Test
    void deletingATownThatWasNeverAnnotatedIsHarmless() throws Exception {
        try (AsyncTownGeographyStore store = store()) {
            store.delete(UUID.randomUUID());
            assertThat(store.awaitIdle(TIMEOUT)).isTrue();
        }

        assertThat(errors).isEmpty();
    }

    @Test
    void closeAppliesWhatIsStillQueued() throws Exception {
        UUID id = UUID.randomUUID();
        AsyncTownGeographyStore store = store();
        store.save(town(id, "Late", 52.52, 13.405));
        store.close();

        assertThat(rows("town_name")).containsExactly("Late");
    }

    @Test
    void aClosedStoreRejectsFurtherWritesInsteadOfLosingThemSilently() {
        AsyncTownGeographyStore store = store();
        store.close();

        assertThat(store.save(town(UUID.randomUUID(), "Too late", 1, 1))).isFalse();
        assertThat(store.delete(UUID.randomUUID())).isFalse();
    }

    @Test
    void anUnknownElevationIsStoredAsNullRatherThanZero() throws Exception {
        UUID id = UUID.randomUUID();
        try (AsyncTownGeographyStore store = store()) {
            store.save(new TownGeography(id, "No DEM", 52.52, 13.405, Double.NaN, null, null));
            assertThat(store.awaitIdle(TIMEOUT)).isTrue();
        }

        assertThat(rows("elevation")).containsExactly((String) null);
        assertThat(rows("country_id")).containsExactly((String) null);
    }

    @Test
    void reportsAnUnwritableDatabaseInsteadOfThrowingIntoTheServerThread() throws Exception {
        AsyncTownGeographyStore broken = new AsyncTownGeographyStore(
                temporaryDirectory.resolve("no-such-directory/terraforge.db"), errors::add);
        try (broken) {
            assertThat(broken.save(town(UUID.randomUUID(), "Berlin", 52.52, 13.405))).isTrue();
            broken.awaitIdle(TIMEOUT);
        }

        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0)).contains("town geography");
    }
}
