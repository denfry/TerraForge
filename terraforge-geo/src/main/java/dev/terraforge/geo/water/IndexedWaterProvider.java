package dev.terraforge.geo.water;

import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.WaterProvider;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return WaterType.NONE;
        }
        Point point = GEOMETRY_FACTORY.createPoint(new org.locationtech.jts.geom.Coordinate(longitude, latitude));
        @SuppressWarnings("unchecked")
        List<IndexedFeature> candidates = index.query(new Envelope(longitude, longitude, latitude, latitude));
        return candidates.stream()
                .filter(candidate -> candidate.geometry().covers(point))
                .map(IndexedFeature::feature)
                .map(WaterFeature::type)
                .max(Comparator.comparingInt(IndexedWaterProvider::priority))
                .orElse(WaterType.NONE);
    }

    @Override
    public double waterSurfaceElevation(double latitude, double longitude) {
        return waterTypeAt(latitude, longitude).isWater() ? 0.0 : ElevationProvider.NO_DATA;
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

    /** One vetted natural water geometry. Man-made data is rejected by the offline importer. */
    public record WaterFeature(WaterType type, Geometry geometry) {
        public WaterFeature {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(geometry, "geometry");
            if (type == WaterType.NONE) {
                throw new IllegalArgumentException("Water features must have a water type");
            }
        }
    }
}
