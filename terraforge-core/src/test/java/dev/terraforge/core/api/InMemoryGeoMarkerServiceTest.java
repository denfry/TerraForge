package dev.terraforge.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.coord.GeoPoint;
import org.junit.jupiter.api.Test;

class InMemoryGeoMarkerServiceTest {
    @Test
    void replacesByIdAndReturnsStableSnapshots() {
        InMemoryGeoMarkerService service = new InMemoryGeoMarkerService();
        var first = marker("test:b", GeoMarkerService.MarkerType.CITY);
        var replacement = marker("test:b", GeoMarkerService.MarkerType.CAPITAL);
        service.register(first);

        assertThat(service.register(replacement)).contains(first);
        assertThat(service.all()).containsExactly(replacement);
        assertThat(service.byType(GeoMarkerService.MarkerType.CAPITAL)).containsExactly(replacement);
        assertThat(service.unregister("test:b")).isTrue();
        assertThat(service.all()).isEmpty();
    }

    private static GeoMarkerService.GeoMarker marker(String id, GeoMarkerService.MarkerType type) {
        return new GeoMarkerService.GeoMarker(id, "Marker", type, new GeoPoint(50, 10), "detail");
    }
}
