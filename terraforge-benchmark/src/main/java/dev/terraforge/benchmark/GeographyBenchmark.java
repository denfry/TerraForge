package dev.terraforge.benchmark;

import dev.terraforge.geo.database.GeoDatabaseSchema;
import dev.terraforge.geo.database.SqliteBoundaryIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Geography lookups against a dataset the size of a real one.
 *
 * <p>The synthetic world here is deliberately larger than Natural Earth Admin 0 (roughly 250
 * countries and 4,600 first-level regions) and than {@code cities15000} (roughly 25,000 places), so
 * a result measured here is an upper bound on what a server actually carries.
 *
 * <p>Two things are being defended. {@code countryAt} runs on the command path and must stay in
 * microseconds however many polygons exist -- that is what the STRtree plus prepared geometries buy.
 * {@code searchCities} runs on every tab-completion keystroke and scans linearly, so it is the one
 * with a real size dependency.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class GeographyBenchmark {

    private static final int COUNTRIES = 400;
    private static final int REGIONS_PER_COUNTRY = 20;
    private static final int CITIES = 50_000;

    private static final GeometryFactory GEOMETRIES = new GeometryFactory();

    private Path database;
    private SqliteBoundaryIndex index;

    @Setup(Level.Trial)
    public void setUp() throws IOException, SQLException {
        database = Files.createTempFile("terraforge-benchmark", ".db");
        Files.deleteIfExists(database);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            GeoDatabaseSchema.install(connection);
            connection.setAutoCommit(false);
            generate(connection);
            connection.commit();
        }
        index = SqliteBoundaryIndex.load(database);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        Files.deleteIfExists(database);
    }

    /** The command path: which country is this block in? */
    @Benchmark
    public void countryAt(Blackhole blackhole) {
        blackhole.consume(index.countryAt(11.3, 21.7));
    }

    @Benchmark
    public void regionAt(Blackhole blackhole) {
        blackhole.consume(index.regionAt(11.3, 21.7));
    }

    /** A miss costs more than a hit: every candidate envelope has to be rejected exactly. */
    @Benchmark
    public void countryAtWithNoHit(Blackhole blackhole) {
        blackhole.consume(index.countryAt(-89.0, 179.0));
    }

    @Benchmark
    public void nearestCity(Blackhole blackhole) {
        blackhole.consume(index.nearestCity(11.3, 21.7));
    }

    /** One tab-completion keystroke. */
    @Benchmark
    public void searchCities(Blackhole blackhole) {
        blackhole.consume(index.searchCities("cit", 40));
    }

    @Benchmark
    public void findCityByExactName(Blackhole blackhole) {
        blackhole.consume(index.findCity("City 25000"));
    }

    // --- synthetic dataset --------------------------------------------------

    /**
     * A grid of non-overlapping square countries, each subdivided into regions, with cities spread
     * over the same area. Shapes are simple on purpose: the point is to measure the index, not
     * JTS's handling of thousand-vertex coastlines.
     */
    private static void generate(Connection connection) throws SQLException {
        int perAxis = (int) Math.ceil(Math.sqrt(COUNTRIES));
        try (PreparedStatement country = connection.prepareStatement(
                "INSERT INTO countries (id, iso_code, name, min_lat, min_lon, max_lat, max_lon, geometry)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement region = connection.prepareStatement(
                     "INSERT INTO regions (id, country_id, name, min_lat, min_lon, max_lat, max_lon, geometry)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            int regionId = 1;
            for (int i = 0; i < COUNTRIES; i++) {
                double south = 10.0 + (i / perAxis);
                double west = 20.0 + (i % perAxis);
                insert(country, i + 1, isoCode(i), "Country " + i, south, west, south + 1, west + 1);
                for (int r = 0; r < REGIONS_PER_COUNTRY; r++) {
                    double regionSouth = south + r / (double) REGIONS_PER_COUNTRY;
                    insert(region, regionId++, i + 1, "Region " + i + "-" + r,
                            regionSouth, west, regionSouth + 1.0 / REGIONS_PER_COUNTRY, west + 1);
                }
            }
        }
        try (PreparedStatement city = connection.prepareStatement(
                "INSERT INTO cities (id, name, latitude, longitude, population, country_id, capital)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0)")) {
            for (int i = 0; i < CITIES; i++) {
                double latitude = 10.0 + (i % 1_000) * (perAxis / 1_000.0);
                double longitude = 20.0 + ((i / 1_000) % 1_000) * (perAxis / 1_000.0);
                city.setInt(1, i + 1);
                city.setString(2, "City " + i);
                city.setDouble(3, latitude);
                city.setDouble(4, longitude);
                city.setLong(5, CITIES - i);
                city.setInt(6, 1 + i % COUNTRIES);
                city.addBatch();
            }
            city.executeBatch();
        }
    }

    /** Unique two-letter codes for far more entries than ISO 3166 itself has. */
    private static String isoCode(int ordinal) {
        return String.format(Locale.ROOT, "%c%c",
                (char) ('A' + ordinal / 26 % 26), (char) ('A' + ordinal % 26));
    }

    private static void insert(PreparedStatement statement, int id, Object owner, String name,
                               double south, double west, double north, double east) throws SQLException {
        statement.setInt(1, id);
        if (owner instanceof String iso) {
            statement.setString(2, iso);
        } else {
            statement.setInt(2, (Integer) owner);
        }
        statement.setString(3, name);
        statement.setDouble(4, south);
        statement.setDouble(5, west);
        statement.setDouble(6, north);
        statement.setDouble(7, east);
        statement.setBytes(8, new WKBWriter().write(GEOMETRIES.createPolygon(new Coordinate[]{
                new Coordinate(west, south), new Coordinate(east, south),
                new Coordinate(east, north), new Coordinate(west, north), new Coordinate(west, south)})));
        statement.executeUpdate();
    }
}
