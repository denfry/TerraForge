package dev.terraforge.cli;

import dev.terraforge.core.coord.GeoBounds;
import picocli.CommandLine.Option;

/**
 * The bounding box every region-scoped command takes, with one definition of what makes it valid.
 *
 * <p>Shared as a mixin so {@code fetch}, {@code init} and {@code setup} cannot drift apart on the
 * rule that matters: they must all describe the same box, because the fetched tiles, the prepared
 * data and the configured region are the same region seen three times.
 */
final class RegionOptions {

    @Option(names = "--lat-min", description = "Southern edge, degrees.")
    Double latMin;

    @Option(names = "--lat-max", description = "Northern edge, degrees.")
    Double latMax;

    @Option(names = "--lon-min", description = "Western edge, degrees.")
    Double lonMin;

    @Option(names = "--lon-max", description = "Eastern edge, degrees.")
    Double lonMax;

    @Option(names = "--whole-world",
            description = "The entire planet, -90..90 by -180..180. Hundreds of gigabytes; "
                    + "run with --dry-run first.")
    boolean wholeWorld;

    /** @throws IllegalArgumentException with a message aimed at whoever typed the command */
    GeoBounds bounds() {
        boolean anyEdge = latMin != null || latMax != null || lonMin != null || lonMax != null;
        if (wholeWorld) {
            if (anyEdge) {
                throw new IllegalArgumentException(
                        "--whole-world already sets every edge; drop --lat-min/--lat-max/--lon-min/--lon-max");
            }
            return GeoBounds.world();
        }
        if (latMin == null || latMax == null || lonMin == null || lonMax == null) {
            throw new IllegalArgumentException("Give --lat-min, --lat-max, --lon-min and --lon-max, "
                    + "or --whole-world for the entire planet");
        }
        if (!Double.isFinite(latMin) || !Double.isFinite(latMax)
                || !Double.isFinite(lonMin) || !Double.isFinite(lonMax)
                || latMin < -90 || latMax > 90 || lonMin < -180 || lonMax > 180
                || latMin >= latMax || lonMin >= lonMax) {
            throw new IllegalArgumentException("The bounding box must satisfy "
                    + "-90 <= lat-min < lat-max <= 90 and -180 <= lon-min < lon-max <= 180");
        }
        return new GeoBounds(latMin, lonMin, latMax, lonMax);
    }
}
