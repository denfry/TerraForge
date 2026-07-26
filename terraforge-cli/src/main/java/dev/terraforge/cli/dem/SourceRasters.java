package dev.terraforge.cli.dem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Finds and opens the source rasters in a preparation input directory. */
public final class SourceRasters {

    private SourceRasters() {
    }

    public static boolean isSupported(Path file) {
        return HgtRaster.matches(file) || GeoTiffRaster.matches(file);
    }

    public static SourceRaster open(Path file) throws IOException {
        if (HgtRaster.matches(file)) {
            return HgtRaster.open(file);
        }
        if (GeoTiffRaster.matches(file)) {
            return GeoTiffRaster.open(file);
        }
        throw new IOException("unsupported source format: " + file.getFileName()
                + " (expected .hgt, .tif or .tiff)");
    }

    /** Every supported raster under {@code directory}, recursively, in stable order. */
    public static List<Path> find(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("input directory does not exist: " + directory);
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile)
                    .filter(SourceRasters::isSupported)
                    .forEach(found::add);
        }
        found.sort(Comparator.comparing(Path::toString));
        return List.copyOf(found);
    }
}
