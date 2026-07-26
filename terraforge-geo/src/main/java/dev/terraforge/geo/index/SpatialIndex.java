package dev.terraforge.geo.index;

import dev.terraforge.core.coord.GeoBounds;
import java.util.List;
import java.util.Optional;

/**
 * Bounding-box accelerated lookup over a polygon dataset.
 *
 * <p>Backed by a JTS {@code STRtree} plus prepared geometries: a point query first narrows to the
 * few candidate polygons whose envelope contains the point, then runs an exact point-in-polygon
 * test on those only. Scanning every country per query is explicitly out of budget.
 *
 * <p>The index is built once at load time and is read-only afterwards, so queries need no locking.
 *
 * @param <T> the attribute type carried by each polygon
 */
public interface SpatialIndex<T> {

    /** Exact point-in-polygon hit, if any. */
    Optional<T> query(double latitude, double longitude);

    /** Every entry whose envelope intersects the box; a coarse pre-filter, not an exact test. */
    List<T> queryBounds(GeoBounds bounds);

    /** Number of indexed geometries. */
    int size();
}
