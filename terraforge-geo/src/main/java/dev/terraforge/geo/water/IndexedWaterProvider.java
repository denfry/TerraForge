package dev.terraforge.geo.water;

import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.WaterProvider;
import java.util.List;
import java.util.Objects;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

/**
 * Read-only, in-memory index of natural water geometries in WGS84 coordinates.
 *
 * <p>The index is deliberately built before the server starts generating chunks. A column lookup
 * only examines polygons whose envelopes contain the point, rather than querying SQLite or scanning
 * every coastline feature. Coordinates use longitude for X and latitude for Y, as required by JTS.
 */
public final class IndexedWaterProvider implements WaterProvider {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private final STRtree index;

    public IndexedWaterProvider(List<WaterFeature> features) {
        Objects.requireNonNull(features, "features");
        this.index = new STRtree();
        for (WaterFeature feature : features) {
            if (feature.geometry().isEmpty()) {
                continue;
            }
            this.index.insert(feature.geometry().getEnvelopeInternal(), new IndexedFeature(feature));
        }
        this.index.build();
    }

    @Override
    public WaterType waterTypeAt(double latitude, double longitude) {
        IndexedFeature best = bestAt(latitude, longitude);
        return best == null ? WaterType.NONE : best.feature().type();
    }

    @Override
    public WaterColumn waterColumnAt(double latitude, double longitude, double knownElevationMeters) {
        IndexedFeature best = bestAt(latitude, longitude);
        return best == null ? WaterColumn.DRY : best.feature().toColumn(knownElevationMeters);
    }

    /**
     * The highest-priority feature covering the point, or {@code null}.
     *
     * <p>Shared by both queries on purpose: classification and attributes must come from the same
     * feature, or a river's depth can end up applied to a lake's surface.
     */
    private IndexedFeature bestAt(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return null;
        }
        Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
        @SuppressWarnings("unchecked")
        List<IndexedFeature> candidates = index.query(new Envelope(longitude, longitude, latitude, latitude));
        IndexedFeature best = null;
        for (IndexedFeature candidate : candidates) {
            if (!candidate.geometry().covers(point)) {
                continue;
            }
            if (best == null || priority(candidate.feature().type()) > priority(best.feature().type())) {
                best = candidate;
            }
        }
        return best;
    }

    private static int priority(WaterType type) {
        return switch (type) {
            case RIVER -> 3;
            case LAKE -> 2;
            case OCEAN -> 1;
            case NONE -> 0;
        };
    }

    private record IndexedFeature(WaterFeature feature, PreparedGeometry geometry) {
        private IndexedFeature(WaterFeature feature) {
            this(feature, PreparedGeometryFactory.prepare(feature.geometry()));
        }
    }

    /**
     * One vetted natural water geometry. Man-made data is rejected by the offline importer.
     *
     * @param surfaceElevationMetres absolute water surface above sea level, or
     *                               {@link ElevationProvider#NO_DATA} when the source did not ship
     *                               one. Ignored for ocean (sea level) and river (terrain height).
     * @param bedDepthMetres         bed depth below the surface, 0 when unknown
     */
    public record WaterFeature(WaterType type, Geometry geometry, double surfaceElevationMetres,
                                double bedDepthMetres) {
        public WaterFeature {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(geometry, "geometry");
            if (type == WaterType.NONE) {
                throw new IllegalArgumentException("Water features must have a water type");
            }
            if (!(bedDepthMetres >= 0.0) || !Double.isFinite(bedDepthMetres)) {
                throw new IllegalArgumentException("Bed depth must be finite and non-negative");
            }
        }

        public WaterFeature(WaterType type, Geometry geometry) {
            this(type, geometry, ElevationProvider.NO_DATA, 0.0);
        }

        WaterColumn toColumn(double knownElevationMeters) {
            return new WaterColumn(type, switch (type) {
                case OCEAN -> 0.0;
                // A river's surface is the terrain it runs through: HydroRIVERS ships a centreline
                // and a discharge, never an absolute water level.
                case RIVER -> knownElevationMeters;
                case LAKE -> surfaceElevationMetres;
                case NONE -> ElevationProvider.NO_DATA;
            }, bedDepthMetres);
        }
    }
}
