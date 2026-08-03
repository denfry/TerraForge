package dev.terraforge.plugin.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Preserves comments and line endings while changing exactly the primary world setting. */
public final class ServerPropertiesEditor {
    public PlannedEdit plan(Path serverProperties, String worldName) throws IOException {
        if (!"earth".equals(worldName)) throw new IllegalArgumentException("managed world must be earth");
        byte[] original = Files.exists(serverProperties) ? Files.readAllBytes(serverProperties) : new byte[0];
        String text = new String(original, StandardCharsets.ISO_8859_1);
        String lineEnd = text.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = text.split("\\R", -1);
        boolean replaced = false;
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("level-name=")) { output.append("level-name=earth"); replaced = true; }
            else output.append(line);
            if (i < lines.length - 1) output.append(lineEnd);
        }
        if (!replaced) {
            if (!text.isEmpty() && !text.endsWith("\n") && !text.endsWith("\r")) output.append(lineEnd);
            output.append("level-name=earth").append(lineEnd);
        }
        return new PlannedEdit(serverProperties, original, output.toString().getBytes(StandardCharsets.ISO_8859_1));
    }
}
