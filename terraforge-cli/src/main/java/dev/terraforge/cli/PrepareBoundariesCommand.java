package dev.terraforge.cli;

import dev.terraforge.cli.geo.BoundaryGeoJsonImporter;
import dev.terraforge.geo.database.GeoDatabaseSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Prepares explicit country and regional boundaries for in-memory runtime lookup. */
@Command(name = "prepare-boundaries", description = "Import explicitly labelled administrative-boundary GeoJSON.")
public final class PrepareBoundariesCommand implements Callable<Integer> {
    @Option(names = {"-i", "--input"}, required = true) Path input;
    @Option(names = {"-d", "--database"}, required = true) Path database;
    @Option(names = "--replace", description = "Replace existing countries and regions.") boolean replace;
    @Override public Integer call() {
        if (!Files.isRegularFile(input)) { System.err.println("Boundary input file does not exist: " + input); return 66; }
        try {
            Path parent = database.toAbsolutePath().normalize().getParent(); if (parent != null) Files.createDirectories(parent);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
                GeoDatabaseSchema.install(connection); connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    if (replace) statement.executeUpdate("DELETE FROM countries");
                    try (var rows = statement.executeQuery("SELECT 1 FROM countries LIMIT 1")) {
                        if (!replace && rows.next()) { connection.rollback(); System.err.println("countries already has data; pass --replace to replace it."); return 65; }
                    }
                }
                var result = BoundaryGeoJsonImporter.importFile(input, connection); connection.commit();
                System.out.println("Prepared " + result.imported() + " administrative boundary feature(s) in "
                        + database + (result.skipped() == 0 ? "." : ", " + result.skipped() + " skipped."));
                return 0;
            }
        } catch (Exception exception) { System.err.println("Cannot prepare boundaries: " + exception.getMessage()); return 74; }
    }
}
