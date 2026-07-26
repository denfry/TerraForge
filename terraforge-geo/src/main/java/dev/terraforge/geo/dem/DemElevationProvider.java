package dev.terraforge.geo.dem;

import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.data.ElevationProvider;
import java.util.Optional;

/**
 * {@link ElevationProvider} backed by prepared {@code .tfdem} tiles.
 *
 * <p>This is the only elevation source the server uses. It is thread-safe and never blocks on
 * anything but a page fault: tiles come from {@link DemCache}, which reads memory-mapped files
 * prepared offline.
 *
 * <p>Points outside the prepared coverage return {@link ElevationProvider#NO_DATA}. Deciding what to
 * put there is the terrain pipeline's job -- it applies {@code terrain.fallback-elevation} -- not
 * this class's, which never guesses.
 */
public final class DemElevationProvider implements ElevationProvider {

    private final DemCache cache;
    private final GeoBounds coverage;
    private final double resolutionMeters;
    private final boolean bathymetry;

    public DemElevationProvider(DemCache cache, GeoBounds coverage, double resolutionMeters, boolean bathymetry) {
        this.cache = cache;
        this.coverage = coverage;
        this.resolutionMeters = resolutionMeters;
        this.bathymetry = bathymetry;
    }

    /**
     * Derives coverage from the tiles actually on disk, so {@code /earth debug} reports what exists
     * rather than what was hoped for. Coverage is empty-safe: with no tiles it degenerates to a
     * point at the origin and every query returns no-data.
     */
    public static DemElevationProvider of(DemCache cache, DemReader reader, double resolutionMeters,
                                          boolean bathymetry) {
        return new DemElevationProvider(cache, coverageOf(reader), resolutionMeters, bathymetry);
    }

    /** Bounding box of the tiles a reader can serve; also used by {@code terraforge info}. */
    public static GeoBounds coverageOf(DemReader reader) {
        int minLat = Integer.MAX_VALUE;
        int minLon = Integer.MAX_VALUE;
        int maxLat = Integer.MIN_VALUE;
        int maxLon = Integer.MIN_VALUE;
        for (DemTileKey key : reader.availableTiles()) {
            minLat = Math.min(minLat, key.latDegree());
            minLon = Math.min(minLon, key.lonDegree());
            maxLat = Math.max(maxLat, key.latDegree() + 1);
            maxLon = Math.max(maxLon, key.lonDegree() + 1);
        }
        if (minLat == Integer.MAX_VALUE) {
            return new GeoBounds(0, 0, 0, 0);
        }
        return new GeoBounds(minLat, minLon, maxLat, maxLon);
    }

    @Override
    public String name() {
        return "tfdem";
    }

    @Override
    public GeoBounds coverage() {
        return coverage;
    }

    @Override
    public double elevationAt(double latitude, double longitude) {
        DemTileKey own = DemTileKey.of(latitude, longitude);
        Optional<DemTile> tile = cache.get(own);
        if (tile.isPresent()) {
            double elevation = tile.get().interpolate(latitude, longitude);
            if (!ElevationProvider.isNoData(elevation)) {
                return elevation;
            }
        }
        // A point on a tile edge sits on the neighbouring tile's grid too. When this tile is absent
        // or has a void there, the neighbour may still carry the sample.
        return neighbourElevation(latitude, longitude, own);
    }

    private double neighbourElevation(double latitude, double longitude, DemTileKey own) {
        double epsilon = 1e-9;
        for (int dLat = -1; dLat <= 1; dLat++) {
            for (int dLon = -1; dLon <= 1; dLon++) {
                DemTileKey key = new DemTileKey(own.latDegree() + dLat, own.lonDegree() + dLon);
                if (key.equals(own)) {
                    continue;
                }
                if (!key.bounds().expand(epsilon).contains(latitude, longitude)) {
                    continue;
                }
                Optional<DemTile> neighbour = cache.get(key);
                if (neighbour.isPresent()) {
                    double elevation = neighbour.get().interpolate(latitude, longitude);
                    if (!ElevationProvider.isNoData(elevation)) {
                        return elevation;
                    }
                }
            }
        }
        return NO_DATA;
    }

    @Override
    public boolean hasBathymetry() {
        return bathymetry;
    }

    @Override
    public double resolutionMeters() {
        return resolutionMeters;
    }
}
