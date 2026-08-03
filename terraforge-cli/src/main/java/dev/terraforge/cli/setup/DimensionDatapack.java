package dev.terraforge.cli.setup;

import dev.terraforge.core.config.VerticalProfile;
import dev.terraforge.core.world.TerraForgeDatapack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the datapack that gives the world its height.
 *
 * <p>World height is not a plugin setting: it belongs to the dimension type, which Minecraft reads
 * from a datapack before any plugin loads. A generator cannot raise it from inside, and a world
 * created without it silently keeps vanilla's 384 blocks -- at which point TerraForge's own
 * {@code min-y} and {@code max-y} are aspirations that the chunk data clamps away.
 *
 * <p>So the pack is written next to the prepared data and the operator copies it into the world.
 * That is one manual step, and it is the honest one: writing into a world directory that does not
 * exist yet, for a world the server has not been told to create, would be guessing at a path and
 * failing silently when the guess is wrong.
 */
public final class DimensionDatapack {

    private DimensionDatapack() {
    }

    /**
     * Writes the pack under {@code directory}, replacing any previous copy.
     *
     * <p>It overrides {@code minecraft:overworld} rather than defining a new dimension, because the
     * world TerraForge generates <em>is</em> the overworld -- a separate dimension would need its
     * own portal, its own spawn and its own set of plugins that know about it.
     *
     * @return the directory to copy into {@code <world>/datapacks/}
     */
    public static Path write(Path directory, VerticalProfile profile) throws IOException {
        Path root = directory.resolve("datapack").resolve("terraforge-world-height");
        Files.createDirectories(root.resolve("data/minecraft/dimension_type"));

        for (var entry : TerraForgeDatapack.render(profile).files().entrySet()) {
            Path output = root.resolve(entry.getKey()).normalize();
            if (!output.startsWith(root)) throw new IOException("Refusing datapack path outside destination");
            Files.createDirectories(output.getParent());
            Files.write(output, entry.getValue());
        }
        return root;
    }

    /** What the operator still has to do, and what happens if they do not. */
    public static String instructions(Path pack, String worldName, VerticalProfile profile) {
        return """
                World height needs a datapack -- Minecraft reads it before any plugin loads.

                  1. Copy   %s
                     into   %s/datapacks/
                  2. Start the server, or run /reload if the world already exists.

                Without it the world keeps vanilla's -64..320 and TerraForge's terrain is clamped
                into it: %s
                """.formatted(pack, worldName, profile.describe());
    }
}
