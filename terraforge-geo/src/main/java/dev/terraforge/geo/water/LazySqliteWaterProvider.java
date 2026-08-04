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
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return WaterType.NONE;
        }
        Point point = point(latitude, longitude);
        return candidatesAt(latitude, longitude).stream()
                .filter(entry -> covers(entry, point))
                .map(CatalogEntry::type)
                .max(Comparator.comparingInt(LazySqliteWaterProvider::priority))
                .orElse(WaterType.NONE);
    }

    @Override
    public double waterSurfaceElevation(double latitude, double longitude) {
        return waterTypeAt(latitude, longitude).isWater() ? 0.0 : ElevationProvider.NO_DATA;
    }

    @Override
    public double waterSurfaceElevation(double latitude, double longitude, double knownElevationMeters) {
        return waterTypeAt(latitude, longitude) == WaterType.RIVER ? knownElevationMeters
                : waterSurfaceElevation(latitude, longitude);
    }

    @Override
    public double riverBedDepthMeters(double latitude, double longitude, double knownElevationMeters) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return 0.0;
        }
        Point point = point(latitude, longitude);
        return candidatesAt(latitude, longitude).stream()
                .filter(entry -> entry.type() == WaterType.RIVER && covers(entry, point))
                .mapToDouble(CatalogEntry::riverBedDepthMetres)
                .max().orElse(0.0);
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
     * across them without one. A miss is cached as empty rather than retried, so a corrupt row costs
     * one failed decode instead of one per lookup against the cell it covers.
     */
    private Optional<PreparedGeometry> decode(CatalogEntry entry) {
        synchronized (connectionLock) {
            try {
                geometryById.setLong(1, entry.id());
                try (ResultSet rows = geometryById.executeQuery()) {
                    if (!rows.next()) {
                        return Optional.empty();
                    }
                    Geometry geometry = new WKBReader().read(rows.getBytes("geometry"));
                    return Optional.of(PreparedGeometryFactory.prepare(geometry));
                }
            } catch (Exception exception) {
                LOG.log(System.Logger.Level.WARNING, "Cannot decode water feature {0}: {1}",
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

    /** One catalogued feature: bounding box and type, read cheaply; geometry decoded on demand. */
    record CatalogEntry(long id, WaterType type, Envelope envelope, double riverBedDepthMetres) {
    }
}
