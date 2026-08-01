package dev.terraforge.cli;

import dev.terraforge.cli.geo.WaterGeoJsonImporter;
import dev.terraforge.geo.database.GeoDatabaseSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.locationtech.jts.geom.Envelope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Imports boundaries, gazetteer entries and natural water polygons into {@code terraforge.db}.
 *
 * <p>Only natural and administrative features are accepted. Roads, buildings, railways and other
 * man-made geometry are rejected at import time, so they cannot reach the world even by accident.
 */
@Command(name = "prepare-geo", description = "Import explicitly labelled natural-water GeoJSON data.")
public final class PrepareGeoCommand implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, required = true,
            description = "Directory with source GeoJSON/shapefile datasets (see DATA_SOURCES.md).")
    Path input;

    @Option(names = {"-d", "--database"}, required = true,
            description = "Target SQLite file, e.g. plugins/TerraForge/terraforge.db")
    Path database;

    @Option(names = "--bbox", split = ",", arity = "4",
            description = "Optional clip box: latMin,lonMin,latMax,lonMax")
    double[] bbox;

    @Option(names = "--replace-water", description = "Replace existing prepared natural water features.")
    boolean replaceWater;

    @Override
    public Integer call() {
        if (!Files.isDirectory(input)) {
            System.err.println("Input directory does not exist: " + input);
            return 66;
        }
        Envelope clip;
        try {
            clip = bbox == null ? null : clipEnvelope();
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return 64;
        }
        List<Path> files;
        try (var paths = Files.walk(input)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".geojson"))
                    .sorted(Comparator.naturalOrder()).toList();
        } catch (IOException exception) {
            System.err.println("Cannot scan " + input + ": " + exception.getMessage());
            return 74;
        }
        if (files.isEmpty()) {
            System.err.println("No .geojson files found under " + input + ".");
            return 66;
        }
        try {
            Path parent = database.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
                GeoDatabaseSchema.install(connection);
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    if (replaceWater) statement.executeUpdate("DELETE FROM water_bodies");
                    boolean hasExistingWater;
                    try (ResultSet rows = statement.executeQuery("SELECT 1 FROM water_bodies LIMIT 1")) {
                        hasExistingWater = rows.next();
                    }
                    if (!replaceWater && hasExistingWater) {
                        System.err.println("water_bodies already has data; rerun with --replace-water to replace it.");
                        connection.rollback();
                        return 65;
                    }
                }
                int total = 0;
                for (Path file : files) total += WaterGeoJsonImporter.importFile(file, connection, clip).imported();
                connection.commit();
                System.out.println("Prepared " + total + " natural water feature(s) in " + database + ".");
                return 0;
            }
        } catch (IOException | SQLException exception) {
            System.err.println("Cannot prepare geographic data: " + exception.getMessage());
            return 74;
        }
    }

    private Envelope clipEnvelope() {
        if (bbox.length != 4 || bbox[0] < -90 || bbox[2] > 90 || bbox[1] < -180 || bbox[3] > 180
                || bbox[0] >= bbox[2] || bbox[1] >= bbox[3]) {
            throw new IllegalArgumentException("--bbox must be latMin,lonMin,latMax,lonMax within WGS84 bounds");
        }
        return new Envelope(bbox[1], bbox[3], bbox[0], bbox[2]);
    }

}
