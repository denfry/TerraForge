package dev.terraforge.towny;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Town;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.EarthLocation;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.geodesy.Geodesy;
import dev.terraforge.core.geo.Country;
import dev.terraforge.core.geo.Region;
import dev.terraforge.geo.database.SqliteBoundaryIndex;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Towny/NewTowny bridge that only annotates towns with prepared TerraForge geography.
 *
 * <p>Reading Towny is strictly a server-thread operation and writing SQLite is strictly not:
 * {@link #refresh(Town)} therefore takes its snapshot on the calling (server) thread and hands the
 * plain record to {@link AsyncTownGeographyStore}, which persists it on its own thread.
 */
public final class SqliteTownGeoService implements TownGeoService, AutoCloseable {

    private final CoordinateTransformer transformer;
    private final ElevationProvider elevation;
    /** Swapped wholesale by {@code /earth reload}; every read takes the current index. */
    private volatile SqliteBoundaryIndex geography;

    private final AsyncTownGeographyStore store;

    public SqliteTownGeoService(CoordinateTransformer transformer, ElevationProvider elevation,
                                SqliteBoundaryIndex geography, Path database, Consumer<String> errorLog) {
        this(transformer, elevation, geography, new AsyncTownGeographyStore(database, errorLog));
    }

    SqliteTownGeoService(CoordinateTransformer transformer, ElevationProvider elevation,
                         SqliteBoundaryIndex geography, AsyncTownGeographyStore store) {
        this.transformer = transformer;
        this.elevation = elevation;
        this.geography = geography;
        this.store = store;
    }

    @Override
    public Optional<EarthLocation> getEarthLocation(Town town) {
        var spawn = town == null ? null : town.getSpawnOrNull();
        if (spawn == null) {
            return Optional.empty();
        }
        var point = transformer.toGeographic(spawn.getX(), spawn.getZ());
        return Optional.of(EarthLocation.builder(point)
                .elevation(elevation.elevationAt(point.latitude(), point.longitude()))
                .minecraft(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ())
                .build());
    }

    @Override
    public Optional<Country> getCountry(Town town) {
        return getEarthLocation(town)
                .flatMap(location -> geography.countryAt(location.latitude(), location.longitude()));
    }

    @Override
    public Optional<Region> getRegion(Town town) {
        return getEarthLocation(town)
                .flatMap(location -> geography.regionAt(location.latitude(), location.longitude()));
    }

    @Override
    public double getDistance(Town a, Town b) {
        return Geodesy.vincentyMeters(getEarthLocation(a).orElseThrow().position(),
                getEarthLocation(b).orElseThrow().position());
    }

    @Override
    public double getDistanceToCoordinates(Town town, double latitude, double longitude) {
        return Geodesy.vincentyMeters(getEarthLocation(town).orElseThrow().position(),
                new GeoPoint(latitude, longitude));
    }

    /**
     * Snapshots the town on the calling thread and queues the write.
     *
     * <p>Returns as soon as the snapshot is taken; the row appears a moment later. Must be called
     * from the server thread, because it reads Towny state.
     */
    @Override
    public void refresh(Town town) {
        store.save(snapshot(town));
    }

    /** Re-annotates every town Towny knows about. Returns how many were queued. */
    public int refreshAll() {
        int queued = 0;
        for (Town town : TownyAPI.getInstance().getTowns()) {
            // Towny allows a town to exist without a spawn transiently; there is nothing to locate.
            if (town.getSpawnOrNull() == null) {
                continue;
            }
            store.save(snapshot(town));
            queued++;
        }
        return queued;
    }

    /**
     * Re-annotates one town by name.
     *
     * @return the town's canonical name, or empty when Towny has no such town or it has no spawn
     */
    public Optional<String> refreshByName(String name) {
        Town town = name == null ? null : TownyAPI.getInstance().getTown(name);
        if (town == null || town.getSpawnOrNull() == null) {
            return Optional.empty();
        }
        store.save(snapshot(town));
        return Optional.of(town.getName());
    }

    /** Town names Towny currently knows, for tab completion. */
    public List<String> townNames() {
        return TownyAPI.getInstance().getTowns().stream().map(Town::getName).sorted().toList();
    }

    /**
     * Points the service at a freshly loaded boundary index, e.g. after {@code /earth reload}.
     *
     * <p>Existing rows keep whatever country and region they were resolved against; run
     * {@code /earth towny refresh} to re-resolve them.
     */
    public void useGeography(SqliteBoundaryIndex reloaded) {
        this.geography = java.util.Objects.requireNonNull(reloaded, "reloaded");
    }

    /** Forgets a deleted town. Safe to call for a town that was never annotated. */
    public void forget(UUID townUuid) {
        store.delete(townUuid);
    }

    /** Writes still waiting to be applied. */
    public int pendingWrites() {
        return store.pending();
    }

    /** Blocks until every write queued so far has been applied. Shutdown and tests only. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        return store.awaitIdle(timeout);
    }

    @Override
    public void close() {
        store.close();
    }

    private TownGeography snapshot(Town town) {
        if (town.getSpawnOrNull() == null) {
            throw new IllegalArgumentException("Town has no spawn");
        }
        var spawn = town.getSpawnOrNull();
        return snapshotAt(town.getUUID(), town.getName(), spawn.getX(), spawn.getZ());
    }

    /**
     * The whole of the snapshot except reading Towny.
     *
     * <p>Split out so it can be tested: Towny's {@code Town} has a static initialiser that needs a
     * running server, which makes it unmockable, while everything interesting here -- projection,
     * elevation, country and region resolution -- is server-free.
     */
    TownGeography snapshotAt(UUID townUuid, String townName, double blockX, double blockZ) {
        GeoPoint point = transformer.toGeographic(blockX, blockZ);
        SqliteBoundaryIndex index = geography;
        return new TownGeography(
                townUuid,
                townName,
                point.latitude(),
                point.longitude(),
                elevation.elevationAt(point.latitude(), point.longitude()),
                index.countryAt(point.latitude(), point.longitude()).map(Country::id).orElse(null),
                index.regionAt(point.latitude(), point.longitude()).map(Region::id).orElse(null));
    }

    /** Queues a write for a position that has already been read off the server thread. */
    void refreshAt(UUID townUuid, String townName, double blockX, double blockZ) {
        store.save(snapshotAt(townUuid, townName, blockX, blockZ));
    }
}
