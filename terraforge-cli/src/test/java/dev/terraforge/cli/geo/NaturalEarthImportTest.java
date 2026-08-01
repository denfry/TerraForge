package dev.terraforge.cli.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.locationtech.jts.geom.Envelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The importers against the attribute names Natural Earth actually publishes -- the dataset the
 * documentation recommends and {@code fetch} downloads.
 */
class NaturalEarthImportTest {

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

    @Test
    void importsAdminZeroCountriesByTheirIsoCode() throws Exception {
        Path file = geoJson("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"NAME":"Switzerland","ADMIN":"Switzerland","ISO_A2":"CH"},
                  "geometry":{"type":"Polygon","coordinates":[[[8,47],[9,47],[9,48],[8,48],[8,47]]]}}
                ]}
                """);

        var result = BoundaryGeoJsonImporter.importFile(file, connection);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(rows("SELECT iso_code, name FROM countries")).containsExactly("CH|Switzerland");
    }

    @Test
    void importsAdminOneProvincesAndAttachesThemToTheirCountry() throws Exception {
        Path file = geoJson("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"NAME":"Switzerland","ISO_A2":"CH"},
                  "geometry":{"type":"Polygon","coordinates":[[[8,47],[9,47],[9,48],[8,48],[8,47]]]}}
                ]}
                """);
        Path provinces = geoJson("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"name":"Zurich","admin":"Switzerland","iso_a2":"CH"},
                  "geometry":{"type":"Polygon","coordinates":[[[8.4,47.3],[8.6,47.3],[8.6,47.5],[8.4,47.5],[8.4,47.3]]]}}
                ]}
                """, "provinces.geojson");
        BoundaryGeoJsonImporter.importFile(file, connection);

        var result = BoundaryGeoJsonImporter.importFile(provinces, connection);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(rows("SELECT r.name, c.iso_code FROM regions r JOIN countries c ON c.id = r.country_id"))
                .containsExactly("Zurich|CH");
    }

    @Test
    void skipsTerritoriesPublishedWithoutAnIsoCode() throws Exception {
        // Natural Earth writes -99 for disputed and partially recognised territories.
        Path file = geoJson("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"NAME":"Somaliland","ISO_A2":"-99"},
                  "geometry":{"type":"Polygon","coordinates":[[[43,8],[48,8],[48,11],[43,11],[43,8]]]}},
                 {"type":"Feature","properties":{"NAME":"Switzerland","ISO_A2":"CH"},
                  "geometry":{"type":"Polygon","coordinates":[[[8,47],[9,47],[9,48],[8,48],[8,47]]]}}
                ]}
                """);

        var result = BoundaryGeoJsonImporter.importFile(file, connection);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void doesNotFailOverATerritoryListedTwice() throws Exception {
        Path file = geoJson("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"NAME":"Switzerland","ISO_A2":"CH"},
                  "geometry":{"type":"Polygon","coordinates":[[[8,47],[9,47],[9,48],[8,48],[8,47]]]}},
                 {"type":"Feature","properties":{"NAME":"Switzerland (again)","ISO_A2":"CH"},
                  "geometry":{"type":"Polygon","coordinates":[[[8,47],[9,47],[9,48],[8,48],[8,47]]]}}
                ]}
                """);

        var result = BoundaryGeoJsonImporter.importFile(file, connection);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void refusesABoundaryFileItCannotInterpretAtAll() throws Exception {
        Path file = geoJson("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"shape_id":7},
                  "geometry":{"type":"Polygon","coordinates":[[[8,47],[9,47],[9,48],[8,48],[8,47]]]}}
                ]}
                """);

        assertThatThrownBy(() -> BoundaryGeoJsonImporter.importFile(file, connection))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cannot tell what these polygons are");
    }

    @Test
    void importsLakesButNotTheReservoirsPeopleBuilt() throws Exception {
        Path file = geoJson("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"featurecla":"Lake","name":"Lake Zurich"},
                  "geometry":{"type":"Polygon","coordinates":[[[8.5,47.2],[8.8,47.2],[8.8,47.3],[8.5,47.3],[8.5,47.2]]]}},
                 {"type":"Feature","properties":{"featurecla":"Reservoir","name":"Sihlsee"},
                  "geometry":{"type":"Polygon","coordinates":[[[8.7,47.1],[8.8,47.1],[8.8,47.2],[8.7,47.2],[8.7,47.1]]]}}
                ]}
                """);

        var result = WaterGeoJsonImporter.importFile(file, connection, null);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(rows("SELECT name, water_type FROM water_bodies")).containsExactly("Lake Zurich|LAKE");
    }

    @Test
    void refusesAWaterFileItCannotInterpretAtAll() throws Exception {
        Path file = geoJson("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"kind":"wet"},
                  "geometry":{"type":"Polygon","coordinates":[[[8,47],[9,47],[9,48],[8,48],[8,47]]]}}
                ]}
                """);

        assertThatThrownBy(() -> WaterGeoJsonImporter.importFile(file, connection, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cannot tell what these polygons are");
    }

    @Test
    void stillHoldsTerraForgesOwnSchemaToItsContract() throws Exception {
        Path file = geoJson("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"boundary_type":"COUNTRY","name":"Testland","iso_code":"lower"},
                  "geometry":{"type":"Polygon","coordinates":[[[8,47],[9,47],[9,48],[8,48],[8,47]]]}}
                ]}
                """);

        assertThatThrownBy(() -> BoundaryGeoJsonImporter.importFile(file, connection))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("uppercase two-letter iso_code");
    }

    @Test
    void widensHydroRiversLinesUsingTheirDischarge() throws Exception {
        Path file = geoJson("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"DIS_AV_CMS":2500},
                  "geometry":{"type":"LineString","coordinates":[[8,47],[8.1,47]]}}
                ]}
                """, "hydrorivers.geojson");

        var result = WaterGeoJsonImporter.importFile(file, connection, null, 1.0);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(rows("SELECT water_type, name FROM water_bodies")).containsExactly("RIVER|null");
    }

    @Test
    void rejectsHydroRiversCanalsBeforeTheyReachTheDatabase() throws Exception {
        Path file = geoJson("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"DIS_AV_CMS":2500,"FCLASS":"Canal"},
                  "geometry":{"type":"LineString","coordinates":[[8,47],[8.1,47]]}}
                ]}
                """, "hydrorivers.geojson");

        var result = WaterGeoJsonImporter.importFile(file, connection, null, 1.0);

        assertThat(result.imported()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void keepsAndWidensARiverCrossingTheAntimeridian() throws Exception {
        Path file = geoJson("""
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"DIS_AV_CMS":1000},
                  "geometry":{"type":"LineString","coordinates":[[179.9,0],[-179.9,0]]}}
                ]}
                """, "hydrorivers.geojson");

        var result = WaterGeoJsonImporter.importFile(file, connection, new Envelope(179.8, 180, -1, 1), 1.0);

        assertThat(result.imported()).isEqualTo(1);
    }

    // --- helpers ------------------------------------------------------------

    private Path geoJson(String content) throws IOException {
        return geoJson(content, "boundaries.geojson");
    }

    private Path geoJson(String content, String name) throws IOException {
        Path file = temporaryDirectory.resolve(name);
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
