package dev.terraforge.geo.database;

import dev.terraforge.core.api.BlockPoint;
import dev.terraforge.core.api.GeoBoundaryProjector;
import dev.terraforge.core.api.ProjectedRing;
import dev.terraforge.core.api.RegionOptions;
import dev.terraforge.core.coord.CoordinateTransformer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;

/**
 * {@link GeoBoundaryProjector} backed by the prepared geographic database.
 *
 * <p>Countries are looked up by ISO code, their WGS84 polygons are unioned, the union is projected
 * to Minecraft block coordinates through the shared {@link CoordinateTransformer} and every ring is
 * simplified in block space. Holes are dropped: WorldGuard polygon regions have no holes.
 */
public final class JtsBoundaryProjector implements GeoBoundaryProjector {

    private static final int MIN_RING_POINTS = 4;
    private static final int MAX_SIMPLIFY_PASSES = 12;

    private final SqliteBoundaryIndex index;
    private final CoordinateTransformer transformer;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public JtsBoundaryProjector(SqliteBoundaryIndex index, CoordinateTransformer transformer) {
        this.index = Objects.requireNonNull(index, "index");
        this.transformer = Objects.requireNonNull(transformer, "transformer");
    }

    @Override
    public List<ProjectedRing> projectCountryUnion(Collection<String> isoCodes, RegionOptions options) {
        Objects.requireNonNull(options, "options");
        if (isoCodes == null || isoCodes.isEmpty()) {
            return List.of();
        }

        List<Geometry> boundaries = new ArrayList<>();
        for (String iso : isoCodes) {
            if (iso == null || iso.isBlank()) {
                continue;
            }
            index.findCountry(iso.strip())
                    .flatMap(index::geometryOf)
                    .ifPresent(boundaries::add);
        }
        if (boundaries.isEmpty()) {
            return List.of();
        }

        Geometry union = union(boundaries);

        List<RingCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < union.getNumGeometries(); i++) {
            Geometry part = union.getGeometryN(i);
            if (!(part instanceof Polygon polygon)) {
                continue;
            }
            List<BlockPoint> ring = projectRing(polygon.getExteriorRing());
            ring = simplify(ring, options.simplifyToleranceBlocks(), options.maxPointsPerRing());
            double area = shoelaceArea(ring);
            if (ring.size() < MIN_RING_POINTS || area < options.minRingAreaBlocks()) {
                continue;
            }
            candidates.add(new RingCandidate(ring, area));
        }

        candidates.sort(Comparator.comparingDouble(RingCandidate::area).reversed());
        return candidates.stream()
                .map(candidate -> new ProjectedRing(candidate.points()))
                .toList();
    }

    private Geometry union(List<Geometry> geometries) {
        if (geometries.size() == 1) {
            return geometries.get(0);
        }
        return UnaryUnionOp.union(geometries);
    }

    /** Projects one WGS84 ring into block coordinates, dropping consecutive duplicate vertices. */
    private List<BlockPoint> projectRing(LineString ring) {
        List<BlockPoint> projected = new ArrayList<>();
        BlockPoint last = null;
        for (Coordinate coordinate : ring.getCoordinates()) {
            var minecraft = transformer.toMinecraft(coordinate.y, coordinate.x);
            BlockPoint point = new BlockPoint(minecraft.blockX(), minecraft.blockZ());
            if (last == null || last.x() != point.x() || last.z() != point.z()) {
                projected.add(point);
                last = point;
            }
        }
        return projected;
    }

    /**
     * Simplifies a projected ring in block space until it fits the vertex budget.
     *
     * <p>The ring's own closing vertex is dropped before simplification and re-added after, because
     * Douglas-Peucker treats the first/last equality as a degenerate segment.
     */
    private List<BlockPoint> simplify(List<BlockPoint> ring, double tolerance, int maxPoints) {
        if (ring.size() <= maxPoints) {
            return ring;
        }
        LineString line = toLineString(ring);
        double current = tolerance;
        List<BlockPoint> best = ring;
        for (int pass = 0; pass < MAX_SIMPLIFY_PASSES; pass++) {
            LineString simplified = (LineString) DouglasPeuckerSimplifier.simplify(line, current);
            List<BlockPoint> points = fromLineString(simplified);
            if (points.size() <= maxPoints) {
                return points;
            }
            best = points;
            current *= 2.0;
        }
        return best;
    }

    private LineString toLineString(List<BlockPoint> points) {
        Coordinate[] coordinates = new Coordinate[points.size()];
        for (int i = 0; i < points.size(); i++) {
            BlockPoint point = points.get(i);
            coordinates[i] = new Coordinate(point.x(), point.z());
        }
        return geometryFactory.createLineString(coordinates);
    }

    private List<BlockPoint> fromLineString(LineString line) {
        List<BlockPoint> points = new ArrayList<>();
        for (Coordinate coordinate : line.getCoordinates()) {
            points.add(new BlockPoint((int) Math.round(coordinate.x), (int) Math.round(coordinate.y)));
        }
        return points;
    }

    private static double shoelaceArea(List<BlockPoint> ring) {
        double sum = 0.0;
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            BlockPoint a = ring.get(i);
            BlockPoint b = ring.get((i + 1) % n);
            sum += (long) a.x() * b.z() - (long) b.x() * a.z();
        }
        return Math.abs(sum) / 2.0;
    }

    private record RingCandidate(List<BlockPoint> points, double area) {
    }
}
