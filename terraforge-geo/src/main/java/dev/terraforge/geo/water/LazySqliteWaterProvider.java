package dev.terraforge.geo.water;

import dev.terraforge.core.cache.CacheStatistics;
import dev.terraforge.core.cache.ManagedCache;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.WaterProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.io.WKBReader;

/**
 * {@link WaterProvider} over the prepared SQLite database's {@code water_bodies} table, catalogued
 * eagerly but decoded lazily.
 *
 * <p>Water used to be loaded whole: every row's WKB geometry was parsed and indexed at startup. That
 * held the main thread for about a minute against a whole-Earth import -- the same shape of problem
 * land cover already solved by cataloguing
 * files instead of loading them. Here only the bounding box columns are read at open time -- cheap,
 * and covered by {@code idx_water_bbox} -- and geometry is decoded from its row on first use, then
 * kept in a cache bounded by {@code cache.water-feature-cache-entries}. A lookup still never scans
 * every feature: the bounding boxes are indexed in an in-memory {@link STRtree}, exactly as before.
 */
final class LazySqliteWaterProvider implements WaterProvider, AutoCloseable {

    private static final System.Logger LOG = System.getLogger("TerraForge-Water");
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private final Connection connection;
    private final PreparedStatement geometryById;
    private final Object connectionLock = new Object();
    private final STRtree index;
    private final ManagedCache<Long, Optional<PreparedGeometry>> geometries;

    LazySqliteWaterProvider(Connection connection, PreparedStatement geometryById, List<CatalogEntry> entries,
                             ManagedCache<Long, Optional<PreparedGeometry>> geometries) {
        this.connection = connection;
        this.geometryById = geometryById;
        this.geometries = geometries;
        this.index = new STRtree();
        for (CatalogEntry entry : entries) {
            index.insert(entry.envelope(), entry);
        }
        index.build();
    }

    @Override
    public WaterType waterTypeAt(double latitude, double longitude) {
        CatalogEntry best = bestAt(latitude, longitude);
        return best == null ? WaterType.NONE : best.type();
    }

    @Override
    public WaterColumn waterColumnAt(double latitude, double longitude, double knownElevationMeters) {
        CatalogEntry best = bestAt(latitude, longitude);
        return best == null ? WaterColumn.DRY : best.toColumn(knownElevationMeters);
    }

    /**
     * The highest-priority catalogued feature covering the point, or {@code null}.
     *
     * <p>Both queries go through here so classification and attributes always describe the same
     * feature. Decoding geometry is what costs; the R-tree query and the priority comparison do not.
     */
    private CatalogEntry bestAt(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return null;
        }
        Point point = point(latitude, longitude);
        CatalogEntry best = null;
        for (CatalogEntry entry : candidatesAt(latitude, longitude)) {
            if (!covers(entry, point)) {
                continue;
            }
            if (best == null || priority(entry.type()) > priority(best.type())) {
                best = entry;
            }
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    private List<CatalogEntry> candidatesAt(double latitude, double longitude) {
        return index.query(new Envelope(longitude, longitude, latitude, latitude));
    }

    private boolean covers(CatalogEntry entry, Point point) {
        return geometryFor(entry).map(geometry -> geometry.covers(point)).orElse(false);
    }

    private Optional<PreparedGeometry> geometryFor(CatalogEntry entry) {
        return geometries.get(entry.id(), id -> decode(entry));
    }

    /**
     * Reads and parses one feature's geometry on a cache miss. Guarded by {@link #connectionLock}:
     * Paper generates chunks on several threads at once, and a JDBC connection is not safe to share
     * across them without one.
     *
     * <p>A failure is cached as empty rather than retried, so a corrupt row costs one failed decode
     * instead of one per lookup against the cell it covers. That has a consequence worth naming: the
     * feature becomes land for the rest of the session, so an undecodable lake silently drains. It is
     * logged at WARNING with the row id -- once, because the empty result is cached -- and the row id
     * is enough to find and re-prepare the offending feature. Retrying instead would turn one corrupt
     * row into a decode attempt per column of the cell it covers, for the life of the server.
     */
    private Optional<PreparedGeometry> decode(CatalogEntry entry) {
        synchronized (connectionLock) {
            try {
                geometryById.setLong(1, entry.id());
                try (ResultSet rows = geometryById.executeQuery()) {
                    if (!rows.next()) {
                        LOG.log(System.Logger.Level.WARNING, "Water feature {0} was catalogued but its "
                                + "row is gone; treating it as land. Re-run `terraforge prepare-geo`.",
                                entry.id());
                        return Optional.empty();
                    }
                    Geometry geometry = new WKBReader().read(rows.getBytes("geometry"));
                    return Optional.of(PreparedGeometryFactory.prepare(geometry));
                }
            } catch (Exception exception) {
                LOG.log(System.Logger.Level.WARNING, "Cannot decode water feature {0} ({1}); treating "
                        + "it as land for this session. Re-run `terraforge prepare-geo`.",
                        entry.id(), exception.getMessage());
                return Optional.empty();
            }
        }
    }

    private static Point point(double latitude, double longitude) {
        return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
    }

    private static int priority(WaterType type) {
        return switch (type) {
            case RIVER -> 3;
            case LAKE -> 2;
            case OCEAN -> 1;
            case NONE -> 0;
        };
    }

    public CacheStatistics cacheStatistics() {
        return geometries.statistics();
    }

    @Override
    public void close() {
        synchronized (connectionLock) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Read-only handle going away at shutdown; nothing to recover.
            }
        }
    }

    /**
     * One catalogued feature: bounding box and attributes, read cheaply; geometry decoded on demand.
     *
     * @param surfaceElevationMetres absolute water surface, or {@link ElevationProvider#NO_DATA}
     *                               when {@code surface_elevation_m} was NULL in the prepared row
     * @param bedDepthMetres         bed depth below the surface, 0 when the source shipped none
     */
    record CatalogEntry(long id, WaterType type, Envelope envelope, double surfaceElevationMetres,
                         double bedDepthMetres, double dischargeCubicMetresPerSecond) {

        WaterColumn toColumn(double knownElevationMeters) {
            return new WaterColumn(type, switch (type) {
                case OCEAN -> 0.0;
                // HydroRIVERS ships a centreline and a discharge, never an absolute water level:
                // a river's surface is the terrain height it runs through.
                case RIVER -> knownElevationMeters;
                case LAKE -> surfaceElevationMetres;
                case NONE -> ElevationProvider.NO_DATA;
            }, bedDepthMetres);
        }
    }
}
