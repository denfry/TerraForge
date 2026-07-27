package dev.terraforge.core.api;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe marker registry that remains useful even when no map renderer is installed. */
public final class InMemoryGeoMarkerService implements GeoMarkerService {
    private final ConcurrentHashMap<String, GeoMarker> markers = new ConcurrentHashMap<>();

    @Override
    public Optional<GeoMarker> register(GeoMarker marker) {
        if (marker == null) throw new IllegalArgumentException("marker must not be null");
        return Optional.ofNullable(markers.put(marker.id(), marker));
    }

    @Override
    public boolean unregister(String id) {
        return id != null && markers.remove(id) != null;
    }

    @Override
    public Optional<GeoMarker> get(String id) {
        return Optional.ofNullable(id == null ? null : markers.get(id));
    }

    @Override
    public Collection<GeoMarker> all() {
        return sorted(markers.values());
    }

    @Override
    public Collection<GeoMarker> byType(MarkerType type) {
        if (type == null) return List.of();
        return sorted(markers.values().stream().filter(marker -> marker.type() == type).toList());
    }

    private static List<GeoMarker> sorted(Collection<GeoMarker> markers) {
        return markers.stream().sorted(Comparator.comparing(GeoMarker::id)).toList();
    }
}
