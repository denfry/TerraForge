package dev.terraforge.cli.geo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.geo.database.GeoDatabaseSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Envelope;

/**
 * Selecting features for a bounding box, with the antimeridian in mind.
 *
 * <p>A country with territory on both sides of 180 degrees -- Russia, the United States, Fiji --
 * has a bounding box spanning the whole planet, so an envelope-only test imports it into every
 * region on Earth.
 */
class ClipTest {

    @TempDir Path temporaryDirectory;

    private Connection connection;

    @BeforeEach
    void openDatabase() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite:"
                + temporaryDirectory.resolve("terraforge.db").toAbsolutePath());
        GeoDatabaseSchema.install(connection);
    }

    @AfterEach
    void closeDatabase() throws Exception {
        connection.close();
    }

    /** Fiji: two clusters of islands, one either side of the antimeridian. */
    @Test
    void doesNotImportAnAntimeridianCountryIntoAnUnrelatedRegion() throws Exception {
        Path file = boundaries("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"NAME":"Fiji","ISO_A2":"FJ"},
                  "geometry":{"type":"MultiPolygon","coordinates":[
                    [[[177,-18],[179,-18],[179,-16],[177,-16],[177,-18]]],
                    [[[-180,-18],[-179,-18],[-179,-16],[-180,-16],[-180,-18]]]]}},
                 {"type":"Feature","properties":{"NAME":"Switzerland","ISO_A2":"CH"},
                  "geometry":{"type":"Polygon","coordinates":[[[8,47],[9,47],[9,48],[8,48],[8,47]]]}}
                ]}
                """);

        // Central Europe: nowhere near Fiji, but inside Fiji's envelope.
        var result = BoundaryGeoJsonImporter.importFile(file, connection,
                new Envelope(5.0, 15.5, 47.0, 55.5));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(rows("SELECT iso_code, name FROM countries")).containsExactly("CH|Switzerland");
    }

    /** The same country, from a box that genuinely overlaps it, is still imported. */
    @Test
    void importsAnAntimeridianCountryForABoxThatReallyTouchesIt() throws Exception {
        Path file = boundaries("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"NAME":"Fiji","ISO_A2":"FJ"},
                  "geometry":{"type":"MultiPolygon","coordinates":[
                    [[[177,-18],[179,-18],[179,-16],[177,-16],[177,-18]]],
                    [[[-180,-18],[-179,-18],[-179,-16],[-180,-16],[-180,-18]]]]}}
                ]}
                """);

        var result = BoundaryGeoJsonImporter.importFile(file, connection,
                new Envelope(176.0, 180.0, -20.0, -15.0));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(rows("SELECT iso_code, name FROM countries")).containsExactly("FJ|Fiji");
    }

    /** A whole-world box takes everything, including the features that straddle 180 degrees. */
    @Test
    void aWholeWorldBoxKeepsEveryCountry() throws Exception {
        Path file = boundaries("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"NAME":"Fiji","ISO_A2":"FJ"},
                  "geometry":{"type":"MultiPolygon","coordinates":[
                    [[[177,-18],[179,-18],[179,-16],[177,-16],[177,-18]]],
                    [[[-180,-18],[-179,-18],[-179,-16],[-180,-16],[-180,-18]]]]}},
                 {"type":"Feature","properties":{"NAME":"Switzerland","ISO_A2":"CH"},
                  "geometry":{"type":"Polygon","coordinates":[[[8,47],[9,47],[9,48],[8,48],[8,47]]]}}
                ]}
                """);

        var result = BoundaryGeoJsonImporter.importFile(file, connection,
                new Envelope(-180.0, 180.0, -90.0, 90.0));

        assertThat(result.imported()).isEqualTo(2);
    }

    /** A box that only clips a corner still takes the country -- it does reach in. */
    @Test
    void keepsAFeatureThatOnlyPartlyOverlapsTheBox() throws Exception {
        Path file = boundaries("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"NAME":"Switzerland","ISO_A2":"CH"},
                  "geometry":{"type":"Polygon","coordinates":[[[8,47],[9,47],[9,48],[8,48],[8,47]]]}}
                ]}
                """);

        var result = BoundaryGeoJsonImporter.importFile(file, connection,
                new Envelope(8.5, 12.0, 47.5, 50.0));

        assertThat(result.imported()).isEqualTo(1);
    }

    /** A concave country whose envelope covers a box its territory never enters. */
    @Test
    void aBoxInsideAnEnvelopeButOutsideTheTerritoryIsNotAMatch() throws Exception {
        // A ring around a hollow centre; the box sits in the hole.
        Path file = boundaries("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"NAME":"Ringland","ISO_A2":"RG"},
                  "geometry":{"type":"Polygon","coordinates":[
                    [[0,0],[10,0],[10,10],[0,10],[0,0]],
                    [[2,2],[8,2],[8,8],[2,8],[2,2]]]}}
                ]}
                """);

        var result = BoundaryGeoJsonImporter.importFile(file, connection,
                new Envelope(4.0, 6.0, 4.0, 6.0));

        assertThat(result.imported()).isZero();
    }

    private Path boundaries(String content) throws IOException {
        Path file = temporaryDirectory.resolve("boundaries.geojson");
        Files.writeString(file, content);
        return file;
    }

    private List<String> rows(String query) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            while (result.next()) {
                rows.add(result.getString(1) + "|" + result.getString(2));
            }
        }
        return rows;
    }
}
