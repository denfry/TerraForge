package dev.terraforge.geo.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/** Installs the geographic database schema from the geo module's bundled resource. */
public final class GeoDatabaseSchema {

    private GeoDatabaseSchema() {
    }

    public static void install(Connection connection) throws IOException, SQLException {
        try (InputStream stream = GeoDatabaseSchema.class.getResourceAsStream("/schema.sql")) {
            if (stream == null) {
                throw new IOException("Bundled geographic schema is missing");
            }
            String schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            // PRAGMAs belong to the caller: some mutate connection state and some return a result
            // set. Strip only PRAGMA lines, rather than dropping a semicolon-delimited fragment
            // that happens to mention one in a preceding comment.
            String ddl = Arrays.stream(schema.split("\\R"))
                    .filter(line -> !line.stripLeading().toUpperCase().startsWith("PRAGMA "))
                    .reduce("", (left, right) -> left + right + "\n");
            for (String sql : ddl.split(";")) {
                if (!sql.isBlank()) {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate(sql);
                    }
                }
            }
        }
    }
}
