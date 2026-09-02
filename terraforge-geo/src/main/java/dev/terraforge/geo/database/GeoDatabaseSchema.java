package dev.terraforge.geo.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
                    .filter(line -> !line.stripLeading().toUpperCase(Locale.ROOT).startsWith("PRAGMA "))
                    .reduce("", (left, right) -> left + right + "\n");
            for (String sql : statements(ddl)) {
                if (!sql.isBlank()) {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate(sql);
                    }
                }
            }
        }
    }

    /**
     * Splits the script on statement boundaries, ignoring semicolons inside {@code --} comments and
     * string literals.
     *
     * <p>A plain {@code split(";")} looks equivalent until a schema comment contains a semicolon:
     * the statement is then cut in half and SQLite rejects the fragment as incomplete input, taking
     * the whole schema with it. Splitting on syntax rather than on a character keeps the schema's
     * documentation editable without booby-trapping it. Comments are left in the executed text, so
     * they survive into {@code sqlite_master}.
     */
    private static List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        int start = 0;
        boolean inComment = false;
        boolean inLiteral = false;
        for (int i = 0; i < script.length(); i++) {
            char current = script.charAt(i);
            if (inComment) {
                inComment = current != '\n';
            } else if (inLiteral) {
                inLiteral = current != '\'';
            } else if (current == '\'') {
                inLiteral = true;
            } else if (current == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                inComment = true;
            } else if (current == ';') {
                statements.add(script.substring(start, i));
                start = i + 1;
            }
        }
        statements.add(script.substring(start));
        return statements;
    }
}
