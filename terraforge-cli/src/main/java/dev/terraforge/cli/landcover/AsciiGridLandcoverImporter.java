package dev.terraforge.cli.landcover;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.geo.landcover.LandcoverGridFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

/** Imports an Arc/Info ASCII Grid whose values use ESA WorldCover's published class codes. */
public final class AsciiGridLandcoverImporter {

    private AsciiGridLandcoverImporter() {
    }

    public static void importFile(Path input, Path output) throws IOException {
        try (Scanner scanner = new Scanner(input)) {
            scanner.useLocale(Locale.ROOT);
            Map<String, Double> header = new HashMap<>();
            while (scanner.hasNext() && !scanner.hasNextDouble()) {
                String key = scanner.next().toLowerCase(Locale.ROOT);
                if (!scanner.hasNextDouble()) {
                    throw new IOException("Invalid Arc/Info ASCII Grid header");
                }
                header.put(key, scanner.nextDouble());
            }
            int width = integer(header, "ncols");
            int height = integer(header, "nrows");
            double cellSize = required(header, "cellsize");
            double west = coordinate(header, "xllcorner", "xllcenter", cellSize);
            double south = coordinate(header, "yllcorner", "yllcenter", cellSize);
            if (!Double.isFinite(cellSize) || cellSize <= 0.0) {
                throw new IOException("Arc/Info ASCII Grid cellsize must be positive");
            }
            double nodata = header.getOrDefault("nodata_value", Double.NaN);
            int cells;
            try {
                cells = Math.multiplyExact(width, height);
            } catch (ArithmeticException exception) {
                throw new IOException("Land-cover grid dimensions overflow", exception);
            }
            if (cells > 64 * 1024 * 1024) {
                throw new IOException("Land-cover grid exceeds the 64 MiB runtime limit");
            }
            LandcoverClass[] values = new LandcoverClass[cells];
            for (int index = 0; index < cells; index++) {
                if (!scanner.hasNextDouble()) {
                    throw new IOException("Arc/Info ASCII Grid ended before all pixels were read");
                }
                double sourceClass = scanner.nextDouble();
                values[index] = Double.compare(sourceClass, nodata) == 0 ? LandcoverClass.UNKNOWN : map(sourceClass);
            }
            if (scanner.hasNext()) {
                throw new IOException("Arc/Info ASCII Grid has more pixels than its header declares");
            }
            LandcoverGridFile.write(output, south, west, south + height * cellSize, west + width * cellSize,
                    width, height, values);
        } catch (IllegalStateException exception) {
            throw new IOException("Cannot read Arc/Info ASCII Grid " + input, exception);
        }
    }

    private static int integer(Map<String, Double> header, String key) throws IOException {
        double value = required(header, key);
        if (value < 1 || value != Math.rint(value) || value > Integer.MAX_VALUE) {
            throw new IOException(key + " must be a positive integer");
        }
        return (int) value;
    }

    private static double required(Map<String, Double> header, String key) throws IOException {
        Double value = header.get(key);
        if (value == null || !Double.isFinite(value)) {
            throw new IOException("Arc/Info ASCII Grid is missing " + key);
        }
        return value;
    }

    private static double coordinate(Map<String, Double> header, String corner, String center, double cellSize)
            throws IOException {
        if (header.containsKey(corner)) {
            return required(header, corner);
        }
        if (header.containsKey(center)) {
            return required(header, center) - cellSize / 2.0;
        }
        throw new IOException("Arc/Info ASCII Grid is missing " + corner + " or " + center);
    }

    private static LandcoverClass map(double value) {
        if (value != Math.rint(value)) {
            return LandcoverClass.UNKNOWN;
        }
        return switch ((int) value) {
            case 10 -> LandcoverClass.TREE_COVER;
            case 20 -> LandcoverClass.SHRUBLAND;
            case 30 -> LandcoverClass.GRASSLAND;
            case 40 -> LandcoverClass.CROPLAND;
            case 50 -> LandcoverClass.BUILT_UP;
            case 60 -> LandcoverClass.BARE_SPARSE;
            case 70 -> LandcoverClass.SNOW_ICE;
            case 80 -> LandcoverClass.PERMANENT_WATER;
            case 90 -> LandcoverClass.HERBACEOUS_WETLAND;
            case 95 -> LandcoverClass.MANGROVES;
            case 100 -> LandcoverClass.MOSS_LICHEN;
            default -> LandcoverClass.UNKNOWN;
        };
    }
}
