package dev.terraforge.core.api;

import dev.terraforge.core.coord.GeoPoint;
import java.util.Collection;
import java.util.Optional;

/**
 * Registry of geographic points of interest exposed to map integrations.
 *
 * <p>A marker is <strong>metadata</strong>: a label at a coordinate. Registering one never places a
 * block, structure or entity in the world.
 *
 * <p>The BlueMap module subscribes to this registry; if BlueMap is absent, markers are still
 * registered and simply have no renderer.
 */
public interface GeoMarkerService {

    /** Registers or replaces a marker. Returns the previous marker with the same id, if any. */
    Optional<GeoMarker> register(GeoMarker marker);

    boolean unregister(String id);

    Optional<GeoMarker> get(String id);

    Collection<GeoMarker> all();

    Collection<GeoMarker> byType(MarkerType type);

    /**
     * @param id       globally unique id, conventionally {@code "<namespace>:<key>"}
     * @param label    display name
     * @param type     marker category
     * @param position geographic position
     * @param detail   optional description shown in the map popup
     */
    record GeoMarker(String id, String label, MarkerType type, GeoPoint position, String detail) {
        public GeoMarker {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("marker id must not be blank");
            }
        }
    }

    /** Marker categories; renderers pick an icon per type. */
    enum MarkerType {
        CITY,
        CAPITAL,
        REGION,
        COUNTRY,
        POINT_OF_INTEREST,
        TOWN,
        NATION,
        CUSTOM
    }
}
