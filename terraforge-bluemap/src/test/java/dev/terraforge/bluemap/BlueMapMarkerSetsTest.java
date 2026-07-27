package dev.terraforge.bluemap;

import static org.assertj.core.api.Assertions.assertThat;

import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import dev.terraforge.core.api.GeoMarkerService;
import dev.terraforge.core.api.GeoMarkerService.GeoMarker;
import dev.terraforge.core.api.GeoMarkerService.MarkerType;
import dev.terraforge.core.api.InMemoryGeoMarkerService;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.coord.MinecraftPos;
import dev.terraforge.core.projection.ProjectionRegistry;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import org.junit.jupiter.api.Test;

class BlueMapMarkerSetsTest {

    private static final GeoPoint ORIGIN = GeoPoint.of(51.0, 10.0);
    private static final CoordinateTransformer TRANSFORMER = new CoordinateTransformer(
            new ProjectionRegistry().create("equirectangular", ORIGIN.latitude()), ORIGIN, 1.0);
    private static final DoubleBinaryOperator SEA_LEVEL = (latitude, longitude) -> 63.0;

    private static GeoMarkerService registry(GeoMarker... markers) {
        GeoMarkerService service = new InMemoryGeoMarkerService();
        for (GeoMarker marker : markers) {
            service.register(marker);
        }
        return service;
    }

    private static GeoMarker marker(String id, MarkerType type) {
        return new GeoMarker(id, id, type, GeoPoint.of(52.52, 13.405), "detail of " + id);
    }

    @Test
    void splitsMarkersIntoTheThreeTerraForgeSets() {
        GeoMarkerService markers = registry(
                marker("terraforge:city/1", MarkerType.CITY),
                marker("terraforge:capital/2", MarkerType.CAPITAL),
                marker("terraforge:country/3", MarkerType.COUNTRY),
                marker("terraforge:region/4", MarkerType.REGION),
                marker("myplugin:harbour", MarkerType.POINT_OF_INTEREST));

        Map<String, MarkerSet> sets = BlueMapMarkerSets.build(markers.all(), TRANSFORMER, SEA_LEVEL, true, true);

        assertThat(sets).containsOnlyKeys(
                BlueMapMarkerSets.CITY_SET, BlueMapMarkerSets.COUNTRY_SET, BlueMapMarkerSets.POI_SET);
        assertThat(sets.get(BlueMapMarkerSets.CITY_SET).getMarkers())
                .containsOnlyKeys("terraforge:city/1", "terraforge:capital/2");
        assertThat(sets.get(BlueMapMarkerSets.COUNTRY_SET).getMarkers())
                .containsOnlyKeys("terraforge:country/3", "terraforge:region/4");
        assertThat(sets.get(BlueMapMarkerSets.POI_SET).getMarkers()).containsOnlyKeys("myplugin:harbour");
    }

    @Test
    void everySetIdIsOwnedByTerraForge() {
        GeoMarkerService markers = registry(
                marker("terraforge:city/1", MarkerType.CITY),
                marker("terraforge:country/2", MarkerType.COUNTRY),
                marker("myplugin:poi", MarkerType.CUSTOM));

        Map<String, MarkerSet> sets = BlueMapMarkerSets.build(markers.all(), TRANSFORMER, SEA_LEVEL, true, true);

        assertThat(sets.keySet()).allMatch(id -> id.startsWith(BlueMapMarkerSets.SET_PREFIX));
    }

    @Test
    void neverPublishesTownyOwnedTypes() {
        GeoMarkerService markers = registry(
                marker("towny:town/1", MarkerType.TOWN),
                marker("towny:nation/1", MarkerType.NATION));

        assertThat(BlueMapMarkerSets.build(markers.all(), TRANSFORMER, SEA_LEVEL, true, true)).isEmpty();
    }

    @Test
    void honoursTheCityAndCountryConfigSwitches() {
        GeoMarkerService markers = registry(
                marker("terraforge:city/1", MarkerType.CITY),
                marker("terraforge:country/2", MarkerType.COUNTRY));

        assertThat(BlueMapMarkerSets.build(markers.all(), TRANSFORMER, SEA_LEVEL, false, true))
                .containsOnlyKeys(BlueMapMarkerSets.COUNTRY_SET);
        assertThat(BlueMapMarkerSets.build(markers.all(), TRANSFORMER, SEA_LEVEL, true, false))
                .containsOnlyKeys(BlueMapMarkerSets.CITY_SET);
        assertThat(BlueMapMarkerSets.build(markers.all(), TRANSFORMER, SEA_LEVEL, false, false)).isEmpty();
    }

    @Test
    void placesMarkersAtTheProjectedPositionAndGivenHeight() {
        GeoPoint berlin = GeoPoint.of(52.52, 13.405);
        GeoMarkerService markers = registry(
                new GeoMarker("terraforge:city/1", "Berlin", MarkerType.CITY, berlin, "population 3,600,000"));

        MarkerSet cities = BlueMapMarkerSets
                .build(markers.all(), TRANSFORMER, (latitude, longitude) -> 74.0, true, true)
                .get(BlueMapMarkerSets.CITY_SET);
        POIMarker marker = (POIMarker) cities.get("terraforge:city/1");
        MinecraftPos expected = TRANSFORMER.toMinecraft(berlin);

        assertThat(marker.getLabel()).isEqualTo("Berlin");
        assertThat(marker.getDetail()).isEqualTo("population 3,600,000");
        assertThat(marker.getPosition().getX()).isEqualTo(expected.x());
        assertThat(marker.getPosition().getZ()).isEqualTo(expected.z());
        assertThat(marker.getPosition().getY()).isEqualTo(74.0);
    }

    @Test
    void blankDetailIsLeftUnsetSoThePopupFallsBackToTheLabel() {
        GeoMarkerService markers = registry(
                new GeoMarker("terraforge:region/9", "Bavaria", MarkerType.REGION, GeoPoint.of(48.8, 11.5), ""));

        MarkerSet countries = BlueMapMarkerSets.build(markers.all(), TRANSFORMER, SEA_LEVEL, true, true)
                .get(BlueMapMarkerSets.COUNTRY_SET);

        assertThat(((POIMarker) countries.get("terraforge:region/9")).getDetail()).isEqualTo("Bavaria");
    }
}
