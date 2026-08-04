package dev.terraforge.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.WaterProvider.WaterType;
import dev.terraforge.geo.water.SqliteWaterProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrepareGeoCommandTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void importsExplicitlyLabelledNaturalWaterIntoTheRuntimeDatabase() throws Exception {
        Path input = temporaryDirectory.resolve("input");
        Files.createDirectories(input);
        Files.writeString(input.resolve("lakes.geojson"), """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"name":"Test lake","water_type":"lake"},
                   "geometry":{"type":"Polygon","coordinates":[[[20,10],[22,10],[22,12],[20,12],[20,10]]]}}
                ]}
                """);
        Path database = temporaryDirectory.resolve("out/terraforge.db");

        PrepareGeoCommand command = new PrepareGeoCommand();
        command.input = input;
        command.database = database;

        assertThat(command.call()).isZero();
        var provider = SqliteWaterProvider.load(database);
        try {
            assertThat(provider.waterTypeAt(11, 21)).isEqualTo(WaterType.LAKE);
        } finally {
            if (provider instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    @Test
    void rejectsFeaturesWithoutTheNaturalWaterWhitelistLabel() throws Exception {
        Path input = temporaryDirectory.resolve("input");
        Files.createDirectories(input);
        Files.writeString(input.resolve("unsafe.geojson"), """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"name":"not allowed"},
                   "geometry":{"type":"Polygon","coordinates":[[[20,10],[22,10],[22,12],[20,12],[20,10]]]}}
                ]}
                """);
        Path database = temporaryDirectory.resolve("out/terraforge.db");

        PrepareGeoCommand command = new PrepareGeoCommand();
        command.input = input;
        command.database = database;

        assertThat(command.call()).isEqualTo(74);
    }

    @Test
    void rejectsAnUnclosedWaterRingBeforeWritingTheDatabase() throws Exception {
        Path input = temporaryDirectory.resolve("input");
        Files.createDirectories(input);
        Files.writeString(input.resolve("broken.geojson"), """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"water_type":"lake"},
                   "geometry":{"type":"Polygon","coordinates":[[[20,10],[22,10],[22,12],[20,12]]]}}
                ]}
                """);
        Path database = temporaryDirectory.resolve("out/terraforge.db");

        PrepareGeoCommand command = new PrepareGeoCommand();
        command.input = input;
        command.database = database;

        assertThat(command.call()).isEqualTo(74);
        assertThat(SqliteWaterProvider.load(database)).isNull();
    }
}
