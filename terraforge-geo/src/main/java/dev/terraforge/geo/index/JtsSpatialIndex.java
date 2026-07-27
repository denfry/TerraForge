package dev.terraforge.geo.index;

import dev.terraforge.core.coord.GeoBounds;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;

/** Immutable WGS84 polygon index backed by an {@link STRtree} and prepared geometries. */
public final class JtsSpatialIndex<T> implements SpatialIndex<T> {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private final STRtree tree = new STRtree();
    private final int size;

    public JtsSpatialIndex(List<Entry<T>> entries) {
        Objects.requireNonNull(entries, "entries");
        int ordinal = 0;
        for (Entry<T> entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (entry.geometry().isEmpty()) {
                continue;
            }
            if (!entry.geometry().isValid()) {
                throw new IllegalArgumentException("Spatial-index geometry must be valid");
            }
            IndexedEntry<T> indexed = new IndexedEntry<>(entry.value(),
                    PreparedGeometryFactory.prepare(entry.geometry()), ordinal++);
            tree.insert(entry.geometry().getEnvelopeInternal(), indexed);
        }
        this.size = ordinal;
        tree.build();
    }

    @Override
    public Optional<T> query(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            return Optional.empty();
        }
        Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
        @SuppressWarnings("unchecked")
        List<IndexedEntry<T>> candidates = tree.query(new Envelope(longitude, longitude, latitude, latitude));
        return candidates.stream()
                .filter(candidate -> candidate.geometry().covers(point))
                // Borders can belong to two administrative polygons. Preserve source order so the
                // importer, rather than STRtree traversal, defines the deterministic tie-break.
                .min(Comparator.comparingInt(IndexedEntry::ordinal))
                .map(IndexedEntry::value);
    }

    @Override
    public List<T> queryBounds(GeoBounds bounds) {
        Objects.requireNonNull(bounds, "bounds");
        @SuppressWarnings("unchecked")
        List<IndexedEntry<T>> candidates = tree.query(new Envelope(
                bounds.minLongitude(), bounds.maxLongitude(), bounds.minLatitude(), bounds.maxLatitude()));
        return candidates.stream()
                .sorted(Comparator.comparingInt(IndexedEntry::ordinal))
                .map(IndexedEntry::value)
                .toList();
    }

    @Override
    public int size() {
        return size;
    }

    /** Associates one domain value with a validated WGS84 polygon or multipolygon. */
    public record Entry<T>(T value, Geometry geometry) {
        public Entry {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(geometry, "geometry");
        }
    }

    private record IndexedEntry<T>(T value, PreparedGeometry geometry, int ordinal) {
    }
}
