package dev.terraforge.geo.water;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.ElevationProvider;
import dev.terraforge.core.data.WaterProvider;
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
    void aLakeWithoutASourceElevationHasNoKnownSurface() {
        IndexedWaterProvider provider = new IndexedWaterProvider(List.of(
                feature(WaterType.LAKE, 0, 0, 1, 1)));

        // Not sea level: a lake whose altitude the source never stated cannot be placed at all.
        var column = provider.waterColumnAt(0.5, 0.5, 300.0);
        assertThat(column.type()).isEqualTo(WaterType.LAKE);
        assertThat(column.hasKnownSurface()).isFalse();
        assertThat(provider.waterColumnAt(2, 2, 300.0)).isEqualTo(WaterProvider.WaterColumn.DRY);
    }

    @Test
    void aLakeReportsItsOwnAltitudeAndDepth() {
        IndexedWaterProvider provider = new IndexedWaterProvider(List.of(
                new IndexedWaterProvider.WaterFeature(WaterType.LAKE, polygon(0, 0, 1, 1), 372.0, 154.0)));

        var column = provider.waterColumnAt(0.5, 0.5, 371.0);
        assertThat(column.surfaceElevationMeters()).isEqualTo(372.0);
        assertThat(column.bedDepthMeters()).isEqualTo(154.0);
    }

    @Test
    void anOceanIsAtSeaLevelAndARiverFollowsTheTerrain() {
        IndexedWaterProvider provider = new IndexedWaterProvider(List.of(
                feature(WaterType.OCEAN, 0, 0, 1, 1),
                new IndexedWaterProvider.WaterFeature(WaterType.RIVER, polygon(2, 2, 3, 3),
                        ElevationProvider.NO_DATA, 4.0)));

        assertThat(provider.waterColumnAt(0.5, 0.5, -20.0))
                .isEqualTo(WaterProvider.WaterColumn.OCEAN);
        // HydroRIVERS states no absolute level, so the river's surface is the terrain height.
        assertThat(provider.waterColumnAt(2.5, 2.5, 820.0).surfaceElevationMeters()).isEqualTo(820.0);
    }

    private static IndexedWaterProvider.WaterFeature feature(WaterType type, double minLatitude,
                                                              double minLongitude, double maxLatitude,
                                                              double maxLongitude) {
        return new IndexedWaterProvider.WaterFeature(type,
                polygon(minLatitude, minLongitude, maxLatitude, maxLongitude));
    }

    private static org.locationtech.jts.geom.Polygon polygon(double minLatitude, double minLongitude,
                                                              double maxLatitude, double maxLongitude) {
        return GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(minLongitude, minLatitude), new Coordinate(maxLongitude, minLatitude),
                new Coordinate(maxLongitude, maxLatitude), new Coordinate(minLongitude, maxLatitude),
                new Coordinate(minLongitude, minLatitude)
        });
    }
}
