package dev.terraforge.bluemap;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import dev.terraforge.core.api.GeoMarkerService;
import dev.terraforge.core.coord.CoordinateTransformer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Publishes the geographic marker registry to BlueMap.
 *
 * <p>BlueMap loads asynchronously and reloads on demand, discarding marker sets each time. Both are
 * handled by registering a listener with {@link BlueMapAPI#onEnable(Consumer)}: it fires once when
 * BlueMap becomes ready and again after every reload, and TerraForge re-publishes its sets from the
 * registry at that point.
 *
 * <p>Only marker sets whose id starts with {@link BlueMapMarkerSets#SET_PREFIX} are ever created,
 * updated or removed. Towny's sets, and any other plugin's, are left exactly as they are.
 *
 * <p>The world is supplied as an opaque {@link Object} so this module stays free of Paper types --
 * {@link BlueMapAPI#getWorld(Object)} accepts the Bukkit world instance directly.
 */
public final class BlueMapMarkerHook implements TerraForgeBlueMapHook {

    private final GeoMarkerService markers;
    private final CoordinateTransformer transformer;
    private final DoubleBinaryOperator surfaceY;
    private final Supplier<Object> world;
    private final boolean cityMarkers;
    private final boolean countryLabels;
    private final Logger logger;

    private Consumer<BlueMapAPI> onEnableListener;

    public BlueMapMarkerHook(GeoMarkerService markers,
                             CoordinateTransformer transformer,
                             DoubleBinaryOperator surfaceY,
                             Supplier<Object> world,
                             boolean cityMarkers,
                             boolean countryLabels,
                             Logger logger) {
        this.markers = Objects.requireNonNull(markers, "markers");
        this.transformer = Objects.requireNonNull(transformer, "transformer");
        this.surfaceY = Objects.requireNonNull(surfaceY, "surfaceY");
        this.world = Objects.requireNonNull(world, "world");
        this.cityMarkers = cityMarkers;
        this.countryLabels = countryLabels;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public boolean isAvailable() {
        return BlueMapAPI.getInstance().isPresent();
    }

    @Override
    public synchronized void enable() {
        if (onEnableListener != null) {
            return;
        }
        // Fires immediately when BlueMap is already up, and again after every BlueMap reload.
        onEnableListener = api -> refreshMarkers();
        BlueMapAPI.onEnable(onEnableListener);
    }

    @Override
    public synchronized void disable() {
        if (onEnableListener != null) {
            BlueMapAPI.unregisterListener(onEnableListener);
            onEnableListener = null;
        }
        BlueMapAPI.getInstance().ifPresent(api -> forEachMap(api, map -> removeOwnSets(map.getMarkerSets())));
    }

    @Override
    public void refreshMarkers() {
        Optional<BlueMapAPI> api = BlueMapAPI.getInstance();
        if (api.isEmpty()) {
            return;
        }
        Map<String, MarkerSet> published =
                BlueMapMarkerSets.build(markers.all(), transformer, surfaceY, cityMarkers, countryLabels);
        int maps = forEachMap(api.get(), map -> {
            Map<String, MarkerSet> sets = map.getMarkerSets();
            removeOwnSets(sets);
            sets.putAll(published);
        });
        if (maps == 0) {
            logger.warning("[TerraForge] BlueMap has no map for the TerraForge world yet; "
                    + "markers will appear after BlueMap finishes loading it.");
        } else {
            logger.info("[TerraForge] BlueMap: published " + markerCount(published) + " markers in "
                    + published.size() + " set(s) across " + maps + " map(s).");
        }
    }

    /** Applies an action to every BlueMap map rendering the TerraForge world. Returns the count. */
    private int forEachMap(BlueMapAPI api, Consumer<BlueMapMap> action) {
        Object bukkitWorld = world.get();
        if (bukkitWorld == null) {
            return 0;
        }
        Optional<BlueMapWorld> blueMapWorld = api.getWorld(bukkitWorld);
        if (blueMapWorld.isEmpty()) {
            return 0;
        }
        Collection<BlueMapMap> maps = blueMapWorld.get().getMaps();
        maps.forEach(action);
        return maps.size();
    }

    /**
     * Removes only the sets TerraForge owns.
     *
     * <p>Package-private so the ownership rule can be tested against a plain map: it is the one
     * piece of the hook that must never be wrong, because getting it wrong deletes another
     * plugin's markers from a live map.
     */
    static void removeOwnSets(Map<String, MarkerSet> sets) {
        List<String> owned = sets.keySet().stream()
                .filter(id -> id.startsWith(BlueMapMarkerSets.SET_PREFIX))
                .toList();
        owned.forEach(sets::remove);
    }

    private static int markerCount(Map<String, MarkerSet> sets) {
        return sets.values().stream().mapToInt(set -> set.getMarkers().size()).sum();
    }
}
