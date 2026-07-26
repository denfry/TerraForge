package dev.terraforge.core.coord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.core.projection.EquirectangularProjection;
import dev.terraforge.core.projection.Projection;
import dev.terraforge.core.projection.WebMercatorProjection;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoordinateTransformerTest {

    private static final GeoPoint ORIGIN = new GeoPoint(51.0, 10.0);

    private CoordinateTransformer transformer(double blocksPerKm) {
        return new CoordinateTransformer(new EquirectangularProjection(51.0), ORIGIN, blocksPerKm);
    }

    @Test
    @DisplayName("configured origin maps to Minecraft 0,0")
    void originIsWorldZero() {
        MinecraftPos pos = transformer(1.0).toMinecraft(ORIGIN);
        assertThat(pos.x()).isCloseTo(0.0, Offset.offset(1e-9));
        assertThat(pos.z()).isCloseTo(0.0, Offset.offset(1e-9));
    }

    @ParameterizedTest(name = "round-trip at {0} blocks/km")
    @ValueSource(doubles = {0.5, 1.0, 2.0, 5.0})
    void geoToMinecraftRoundTrips(double blocksPerKm) {
        CoordinateTransformer transformer = transformer(blocksPerKm);
        GeoPoint frankfurt = new GeoPoint(50.110644, 8.682092);

        MinecraftPos pos = transformer.toMinecraft(frankfurt);
        GeoPoint back = transformer.toGeographic(pos.x(), pos.z());

        assertThat(back.latitude()).isCloseTo(frankfurt.latitude(), Offset.offset(1e-9));
        assertThat(back.longitude()).isCloseTo(frankfurt.longitude(), Offset.offset(1e-9));
    }

    @Test
    @DisplayName("north of the origin is negative Z (Minecraft Z grows south)")
    void northIsNegativeZ() {
        MinecraftPos north = transformer(1.0).toMinecraft(52.0, 10.0);
        assertThat(north.z()).isNegative();
        assertThat(north.x()).isCloseTo(0.0, Offset.offset(1e-6));
    }

    @Test
    @DisplayName("east of the origin is positive X")
    void eastIsPositiveX() {
        MinecraftPos east = transformer(1.0).toMinecraft(51.0, 11.0);
        assertThat(east.x()).isPositive();
    }

    @Test
    @DisplayName("at 1 block/km one degree of latitude is about 111 blocks")
    void latitudeDegreeIsAboutOneHundredEleven() {
        double blocks = Math.abs(transformer(1.0).toMinecraft(52.0, 10.0).z());
        assertThat(blocks).isCloseTo(111.2, Offset.offset(0.5));
    }

    @Test
    @DisplayName("doubling blocks-per-km doubles the block distance")
    void scaleIsLinear() {
        double atOne = Math.abs(transformer(1.0).toMinecraft(52.0, 10.0).z());
        double atTwo = Math.abs(transformer(2.0).toMinecraft(52.0, 10.0).z());
        assertThat(atTwo).isCloseTo(atOne * 2.0, Offset.offset(1e-6));
    }

    @Test
    void metersPerBlockFollowsScale() {
        assertThat(transformer(1.0).metersPerBlock()).isEqualTo(1000.0);
        assertThat(transformer(2.0).metersPerBlock()).isEqualTo(500.0);
        assertThat(transformer(0.5).metersPerBlock()).isEqualTo(2000.0);
    }

    @Test
    @DisplayName("chunk bounds contain the chunk's own centre")
    void chunkBoundsContainCentre() {
        CoordinateTransformer transformer = transformer(1.0);
        GeoBounds bounds = transformer.chunkBounds(3, -7);
        GeoPoint centre = transformer.toGeographic(3 * 16 + 8.0, -7 * 16 + 8.0);
        assertThat(bounds.contains(centre)).isTrue();
    }

    @Test
    @DisplayName("web mercator ground distance shrinks with latitude")
    void mercatorGroundMetersPerBlock() {
        Projection mercator = WebMercatorProjection.INSTANCE;
        CoordinateTransformer transformer = new CoordinateTransformer(mercator, ORIGIN, 1.0);
        assertThat(transformer.groundMetersPerBlock(60.0))
                .isCloseTo(500.0, Offset.offset(1e-6));
        assertThat(transformer.groundMetersPerBlock(0.0))
                .isCloseTo(1000.0, Offset.offset(1e-6));
    }

    @Test
    void rejectsNonPositiveScale() {
        assertThatThrownBy(() -> transformer(0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transformer(-1.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
