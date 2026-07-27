package dev.terraforge.towny;

import dev.terraforge.geo.database.GeoDatabaseSchema;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Writes town geography to SQLite on a dedicated thread.
 *
 * <p>A town is created or its spawn moved while a player waits for the command to return. A SQLite
 * write is a disk write: doing it inline means the main thread blocks on the filesystem, and on a
 * busy server that shows up as a tick spike. Everything Towny- or Bukkit-related is therefore read
 * on the server thread and handed over as a plain {@link TownGeography} record; only the write
 * itself moves off it.
 *
 * <p>One thread and one connection, so writes keep the order they were enqueued in: a rename
 * followed by a delete can never land the other way round. The queue is bounded -- if it ever fills
 * up, dropping the oldest pending write would silently lose data, so the enqueue is rejected loudly
 * instead.
 */
public final class AsyncTownGeographyStore implements AutoCloseable {

    private static final int QUEUE_CAPACITY = 4_096;

    private static final String UPSERT = """
            INSERT INTO town_geography
                (town_uuid, town_name, latitude, longitude, elevation, country_id, region_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(town_uuid) DO UPDATE SET
                town_name = excluded.town_name,
                latitude = excluded.latitude,
                longitude = excluded.longitude,
                elevation = excluded.elevation,
                country_id = excluded.country_id,
                region_id = excluded.region_id,
                updated_at = excluded.updated_at
            """;

    private final Path database;
    private final Consumer<String> errorLog;
    private final BlockingQueue<Task> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    /**
     * @param database target SQLite file; created with the standard schema if it does not exist
     * @param errorLog where write failures are reported. A failed write must never propagate into
     *                 Towny's own transaction, but it must never be swallowed either
     */
    public AsyncTownGeographyStore(Path database, Consumer<String> errorLog) {
        this.database = Objects.requireNonNull(database, "database");
        this.errorLog = Objects.requireNonNull(errorLog, "errorLog");
        this.worker = new Thread(this::drain, "TerraForge-TownGeography");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** Queues an insert-or-update. Returns false when the store is closed or the queue is full. */
    public boolean save(TownGeography geography) {
        return enqueue(new Task.Save(Objects.requireNonNull(geography, "geography")));
    }

    /** Queues the removal of a town's geography, e.g. after Towny deleted it. */
    public boolean delete(UUID townUuid) {
        return enqueue(new Task.Delete(Objects.requireNonNull(townUuid, "townUuid")));
    }

    /** Writes still waiting to be applied. Zero does not imply the last one has been committed. */
    public int pending() {
        return queue.size();
    }

    /**
     * Blocks until every write enqueued before this call has been applied.
     *
     * <p>For shutdown and for tests. Never call it from the server thread during normal play.
     *
     * @return false on timeout
     */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        CountDownLatch applied = new CountDownLatch(1);
        if (!enqueue(new Task.Barrier(applied))) {
            return !running.get();
        }
        return applied.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops accepting writes, applies what is already queued, then closes the connection. */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean enqueue(Task task) {
        if (!running.get()) {
            return false;
        }
        if (!queue.offer(task)) {
            errorLog.accept("Town-geography write queue is full (" + QUEUE_CAPACITY
                    + " pending); dropped a write for " + task.describe()
                    + ". Run '/earth towny refresh' once the server is idle.");
            return false;
        }
        return true;
    }

    // --- writer thread ------------------------------------------------------

    private void drain() {
        Connection connection = null;
        try {
            while (true) {
                Task task = queue.poll(200, TimeUnit.MILLISECONDS);
                if (task == null) {
                    if (!running.get()) {
                        return;
                    }
                    continue;
                }
                if (task instanceof Task.Barrier barrier) {
                    barrier.applied().countDown();
                    continue;
                }
                if (connection == null) {
                    connection = open();
                    if (connection == null) {
                        return;
                    }
                }
                apply(connection, task);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            // Interrupted by close(): apply what is already queued rather than losing it.
            connection = drainRemaining(connection);
        } finally {
            closeQuietly(connection);
        }
    }

    private Connection drainRemaining(Connection connection) {
        List<Task> remaining = new java.util.ArrayList<>();
        queue.drainTo(remaining);
        for (Task task : remaining) {
            if (task instanceof Task.Barrier barrier) {
                barrier.applied().countDown();
                continue;
            }
            if (connection == null) {
                connection = open();
                if (connection == null) {
                    return null;
                }
            }
            apply(connection, task);
        }
        return connection;
    }

    private Connection open() {
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
            GeoDatabaseSchema.install(connection);
            return connection;
        } catch (SQLException | IOException exception) {
            errorLog.accept("Cannot open " + database + " for town geography: " + exception.getMessage()
                    + "; town annotations are not being persisted.");
            return null;
        }
    }

    private void apply(Connection connection, Task task) {
        try {
            if (task instanceof Task.Save save) {
                save(connection, save.geography());
            } else if (task instanceof Task.Delete delete) {
                delete(connection, delete.townUuid());
            }
        } catch (SQLException exception) {
            errorLog.accept("Cannot persist town geography for " + task.describe() + ": " + exception.getMessage());
        }
    }

    private static void save(Connection connection, TownGeography geography) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, geography.townUuid().toString());
            statement.setString(2, geography.townName());
            statement.setDouble(3, geography.latitude());
            statement.setDouble(4, geography.longitude());
            if (Double.isNaN(geography.elevation())) {
                statement.setNull(5, java.sql.Types.REAL);
            } else {
                statement.setDouble(5, geography.elevation());
            }
            setNullableInt(statement, 6, geography.countryId());
            setNullableInt(statement, 7, geography.regionId());
            statement.setLong(8, Instant.now().getEpochSecond());
            statement.executeUpdate();
        }
    }

    private static void delete(Connection connection, UUID townUuid) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM town_geography WHERE town_uuid = ?")) {
            statement.setString(1, townUuid.toString());
            statement.executeUpdate();
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            errorLog.accept("Cannot close the town-geography database: " + exception.getMessage());
        }
    }

    private sealed interface Task {

        String describe();

        record Save(TownGeography geography) implements Task {
            @Override
            public String describe() {
                return geography.townName();
            }
        }

        record Delete(UUID townUuid) implements Task {
            @Override
            public String describe() {
                return "town " + townUuid;
            }
        }

        record Barrier(CountDownLatch applied) implements Task {
            @Override
            public String describe() {
                return "flush barrier";
            }
        }
    }
}
