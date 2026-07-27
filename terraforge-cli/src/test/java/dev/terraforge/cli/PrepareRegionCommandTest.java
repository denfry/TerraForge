package dev.terraforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.geo.database.SqliteBoundaryIndex;
import dev.terraforge.geo.landcover.LandcoverGridFile;
import dev.terraforge.geo.water.SqliteWaterProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrepareRegionCommandTest {

    @TempDir
    Path temporaryDirectory;

    private Path input;
    private Path output;

    @BeforeEach
    void layOutSourceTree() throws Exception {
        input = temporaryDirectory.resolve("sources");
        output = temporaryDirectory.resolve("plugins/TerraForge");
        Files.createDirectories(input);
    }

    private PrepareRegionCommand command() {
        PrepareRegionCommand command = new PrepareRegionCommand();
        command.input = input;
        command.output = output;
        command.databaseName = "terraforge.db";
        command.encoding = "int16";
        command.latMin = 10;
        command.latMax = 12;
        command.lonMin = 20;
        command.lonMax = 22;
        return command;
    }

    private void writeBoundaries() throws Exception {
        Files.createDirectories(input.resolve("boundaries"));
        Files.writeString(input.resolve("boundaries/admin.geojson"), """
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"boundary_type":"COUNTRY","name":"Testland","iso_code":"TT"},
                  "geometry":{"type":"Polygon","coordinates":[[[20,10],[22,10],[22,12],[20,12],[20,10]]]}},
                 {"type":"Feature","properties":{"boundary_type":"COUNTRY","name":"Farland","iso_code":"FF"},
                  "geometry":{"type":"Polygon","coordinates":[[[100,50],[102,50],[102,52],[100,52],[100,50]]]}}
                ]}
                """);
    }

    private void writeCities() throws Exception {
        Files.createDirectories(input.resolve("cities"));
        // GeoNames layout: id, name, ascii, alt, lat, lon, class, code, country, ... , population
        Files.writeString(input.resolve("cities/cities.txt"),
                geoName("1", "Inside", 11.0, 21.0, "TT", "PPLC", 100_000)
                        + geoName("2", "Outside", 51.0, 101.0, "FF", "PPL", 5_000));
    }

    private static String geoName(String id, String name, double latitude, double longitude,
                                  String country, String featureCode, long population) {
        return String.join("\t", id, name, name, "", String.valueOf(latitude), String.valueOf(longitude),
                "P", featureCode, country, "", "", "", "", "", String.valueOf(population)) + "\n";
    }

    private void writeWater() throws Exception {
        Files.createDirectories(input.resolve("water"));
        Files.writeString(input.resolve("water/lakes.geojson"), """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"name":"Test lake","water_type":"lake"},
                   "geometry":{"type":"Polygon","coordinates":[[[20,10],[22,10],[22,12],[20,12],[20,10]]]}}
                ]}
                """);
    }

    private void writeLandcover() throws Exception {
        Files.createDirectories(input.resolve("landcover"));
        Files.writeString(input.resolve("landcover/region.asc"), """
                ncols 2
                nrows 2
                xllcorner 20
                yllcorner 10
                cellsize 1
                NODATA_value -9999
                10 50
                90 -9999
                """);
    }

    @Test
    void preparesEveryDatasetForTheBoxInOnePass() throws Exception {
        writeBoundaries();
        writeCities();
        writeWater();
        writeLandcover();

        assertThat(command().call()).isZero();

        Path database = output.resolve("terraforge.db");
        SqliteBoundaryIndex index = SqliteBoundaryIndex.load(database);
        assertThat(index.countryAt(11, 21)).map(country -> country.isoCode()).contains("TT");
        assertThat(index.findCityByName("Inside")).isPresent();
        assertThat(SqliteWaterProvider.load(database).waterTypeAt(11, 21)).isEqualTo(WaterType.LAKE);
        assertThat(LandcoverGridFile.read(output.resolve("data/landcover/region.tflc"))
                .landcoverAt(11.5, 20.5)).isEqualTo(LandcoverClass.TREE_COVER);
    }

    @Test
    void clipsEveryDatasetToTheBoundingBox() throws Exception {
        writeBoundaries();
        writeCities();

        assertThat(command().call()).isZero();

        SqliteBoundaryIndex index = SqliteBoundaryIndex.load(output.resolve("terraforge.db"));
        assertThat(index.countries()).extracting(country -> country.isoCode()).containsExactly("TT");
        assertThat(index.cities()).extracting(city -> city.name()).containsExactly("Inside");
    }

    @Test
    void resolvesCityCountriesBecauseBoundariesAreImportedFirst() throws Exception {
        writeBoundaries();
        writeCities();

        assertThat(command().call()).isZero();

        SqliteBoundaryIndex index = SqliteBoundaryIndex.load(output.resolve("terraforge.db"));
        int testland = index.findCountry("TT").orElseThrow().id();
        assertThat(index.cities()).singleElement()
                .satisfies(city -> assertThat(city.countryId()).isEqualTo(testland));
    }

    @Test
    void refusesToOverwritePreparedDataWithoutReplace() throws Exception {
        writeBoundaries();
        assertThat(command().call()).isZero();

        assertThat(command().call()).isEqualTo(65);

        PrepareRegionCommand replacing = command();
        replacing.replace = true;
        assertThat(replacing.call()).isZero();
        assertThat(SqliteBoundaryIndex.load(output.resolve("terraforge.db")).countries()).hasSize(1);
    }

    @Test
    void rejectsAnInvertedBoundingBoxBeforeReadingAnything() throws Exception {
        writeBoundaries();
        PrepareRegionCommand command = command();
        command.latMin = 12;
        command.latMax = 10;

        assertThat(command.call()).isEqualTo(64);
        assertThat(Files.exists(output.resolve("terraforge.db"))).isFalse();
    }

    @Test
    void reportsAnEmptySourceTreeInsteadOfWritingAnEmptyDatabase() {
        assertThat(command().call()).isEqualTo(66);
        assertThat(Files.exists(output.resolve("terraforge.db"))).isFalse();
    }

    @Test
    void leavesTheDatabaseUntouchedWhenOneSourceFileIsBroken() throws Exception {
        writeBoundaries();
        Files.writeString(input.resolve("boundaries/broken.geojson"), """
                {"type":"FeatureCollection","features":[
                 {"type":"Feature","properties":{"boundary_type":"COUNTRY","name":"Broken"},
                  "geometry":{"type":"Polygon","coordinates":[[[20,10],[22,10],[22,12],[20,12],[20,10]]]}}
                ]}
                """);

        assertThat(command().call()).isEqualTo(74);

        // The schema is installed outside the transaction; what matters is that no half-finished
        // import survived it.
        assertThat(SqliteBoundaryIndex.load(output.resolve("terraforge.db")).countries()).isEmpty();
    }
}
