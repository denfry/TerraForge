package dev.terraforge.geo.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKBWriter;

/**
 * Builds a minimal prepared geographic database in a temp directory for tests.
 *
 * <p>Two adjacent countries: {@code AA} covering lat 0..1 × lon 0..1 and {@code BB} covering
 * lat 0..1 × lon 1..2. They share the lon=1 edge, so their union is one polygon.
 */
final class TestGeoDatabase {

    private static final GeometryFactory FACTORY = new GeometryFactory();

    private TestGeoDatabase() {
    }

    static Path create(Path directory) throws IOException {
        Path database = directory.resolve("geo.db");
        String url = "jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/');
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE countries (
                        id INTEGER PRIMARY KEY,
                        iso_code TEXT NOT NULL UNIQUE,
                        iso_code_3 TEXT,
                        name TEXT NOT NULL,
                        min_lat REAL NOT NULL,
                        min_lon REAL NOT NULL,
                        max_lat REAL NOT NULL,
                        max_lon REAL NOT NULL,
                        geometry BLOB NOT NULL
                    )""");
            statement.execute("""
                    CREATE TABLE regions (
                        id INTEGER PRIMARY KEY,
                        country_id INTEGER,
                        name TEXT NOT NULL,
                        admin_level INTEGER DEFAULT 1,
                        min_lat REAL NOT NULL,
                        min_lon REAL NOT NULL,
                        max_lat REAL NOT NULL,
                        max_lon REAL NOT NULL,
                        geometry BLOB NOT NULL
                    )""");
            statement.execute("""
                    CREATE TABLE cities (
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        population INTEGER DEFAULT 0,
                        country_id INTEGER,
                        region_id INTEGER,
                        capital INTEGER DEFAULT 0
                    )""");
            insertCountry(statement, 1, "AA", "Alpha", 0.0, 0.0, 1.0, 1.0,
                    polygon(0.0, 0.0, 1.0, 1.0));
            insertCountry(statement, 2, "BB", "Beta", 0.0, 1.0, 1.0, 2.0,
                    polygon(0.0, 1.0, 1.0, 2.0));
        } catch (SQLException exception) {
            throw new IOException("Cannot create test database", exception);
        }
        return database;
    }

    private static void insertCountry(Statement statement, int id, String iso, String name,
                                      double minLat, double minLon, double maxLat, double maxLon,
                                      Polygon polygon) throws SQLException {
        try (var ps = statement.getConnection().prepareStatement(
                "INSERT INTO countries (id, iso_code, iso_code_3, name, min_lat, min_lon, max_lat, max_lon, geometry) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, id);
            ps.setString(2, iso);
            ps.setString(3, null);
            ps.setString(4, name);
            ps.setDouble(5, minLat);
            ps.setDouble(6, minLon);
            ps.setDouble(7, maxLat);
            ps.setDouble(8, maxLon);
            ps.setBytes(9, new WKBWriter().write(polygon));
            ps.executeUpdate();
        }
    }

    private static Polygon polygon(double minLat, double minLon, double maxLat, double maxLon) {
        LinearRing shell = FACTORY.createLinearRing(new Coordinate[]{
                new Coordinate(minLon, minLat),
                new Coordinate(maxLon, minLat),
                new Coordinate(maxLon, maxLat),
                new Coordinate(minLon, maxLat),
                new Coordinate(minLon, minLat),
        });
        return FACTORY.createPolygon(shell, null);
    }
}
