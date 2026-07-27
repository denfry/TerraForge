package dev.terraforge.cli;

import dev.terraforge.cli.geo.GeoNamesCityImporter;
import dev.terraforge.geo.database.GeoDatabaseSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "prepare-cities", description = "Import a GeoNames tab-separated city export.")
public final class PrepareCitiesCommand implements Callable<Integer> {
    @Option(names = {"-i", "--input"}, required = true) Path input;
    @Option(names = {"-d", "--database"}, required = true) Path database;
    @Option(names = "--replace", description = "Replace existing prepared cities.") boolean replace;
    @Override public Integer call() {
        if (!Files.isRegularFile(input)) { System.err.println("City input file does not exist: " + input); return 66; }
        try {
            Path parent = database.toAbsolutePath().normalize().getParent(); if (parent != null) Files.createDirectories(parent);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
                GeoDatabaseSchema.install(connection); connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    if (replace) statement.executeUpdate("DELETE FROM cities");
                    try (var rows = statement.executeQuery("SELECT 1 FROM cities LIMIT 1")) { if (!replace && rows.next()) { connection.rollback(); System.err.println("cities already has data; pass --replace to replace it."); return 65; } }
                }
                int total = GeoNamesCityImporter.importFile(input, connection); connection.commit();
                System.out.println("Prepared " + total + " city entries in " + database + "."); return 0;
            }
        } catch (Exception e) { System.err.println("Cannot prepare cities: " + e.getMessage()); return 74; }
    }
}
