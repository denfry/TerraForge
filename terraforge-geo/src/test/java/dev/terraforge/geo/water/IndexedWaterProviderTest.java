package dev.terraforge.geo.water;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.WaterProvider.WaterType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

class IndexedWaterProviderTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    void resolvesWaterUsingLongitudeAsXAndLatitudeAsY() {
        IndexedWaterProvider provider = new IndexedWaterProvider(List.of(
                feature(WaterType.OCEAN, 10, 20, 12, 22)));

        assertThat(provider.waterTypeAt(11, 21)).isEqualTo(WaterType.OCEAN);
        assertThat(provider.waterTypeAt(21, 11)).isEqualTo(WaterType.NONE);
    }

    @Test
    void mostSpecificNaturalWaterFeatureWinsWhenPolygonsOverlap() {
        IndexedWaterProvider provider = new IndexedWaterProvider(List.of(
                feature(WaterType.OCEAN, 0, 0, 10, 10),
                feature(WaterType.LAKE, 2, 2, 8, 8),
                feature(WaterType.RIVER, 4, 4, 6, 6)));

        assertThat(provider.waterTypeAt(1, 1)).isEqualTo(WaterType.OCEAN);
        assertThat(provider.waterTypeAt(3, 3)).isEqualTo(WaterType.LAKE);
        assertThat(provider.waterTypeAt(5, 5)).isEqualTo(WaterType.RIVER);
    }

    @Test
    void reportsSeaLevelOnlyForWater() {
        IndexedWaterProvider provider = new IndexedWaterProvider(List.of(
                feature(WaterType.LAKE, 0, 0, 1, 1)));

        assertThat(provider.waterSurfaceElevation(0.5, 0.5)).isZero();
        assertThat(ElevationProvider.isNoData(provider.waterSurfaceElevation(2, 2))).isTrue();
    }

    private static IndexedWaterProvider.WaterFeature feature(WaterType type, double minLatitude,
                                                              double minLongitude, double maxLatitude,
                                                              double maxLongitude) {
        return new IndexedWaterProvider.WaterFeature(type, GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(minLongitude, minLatitude), new Coordinate(maxLongitude, minLatitude),
                new Coordinate(maxLongitude, maxLatitude), new Coordinate(minLongitude, maxLatitude),
                new Coordinate(minLongitude, minLatitude)
        }));
    }
}
