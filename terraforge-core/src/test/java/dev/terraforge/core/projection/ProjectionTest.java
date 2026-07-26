package dev.terraforge.core.projection;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.coord.GeoPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProjectionTest {

    private static final double METER_TOLERANCE = 1.0e-6;
    private static final double DEGREE_TOLERANCE = 1.0e-9;

    @ParameterizedTest(name = "web mercator round-trip {0}, {1}")
    @CsvSource({
            "0.0, 0.0",
            "50.110644, 8.682092",   // Frankfurt
            "-33.868820, 151.209290", // Sydney
            "64.135300, -21.895300",  // Reykjavik
            "-54.801910, -68.302950", // Ushuaia
    })
    void webMercatorRoundTripsExactly(double latitude, double longitude) {
        Projection projection = WebMercatorProjection.INSTANCE;
        PlanePoint plane = projection.toPlane(latitude, longitude);
        GeoPoint back = projection.toGeographic(plane);

        assertThat(back.latitude()).isCloseTo(latitude, org.assertj.core.data.Offset.offset(DEGREE_TOLERANCE));
        assertThat(back.longitude()).isCloseTo(longitude, org.assertj.core.data.Offset.offset(DEGREE_TOLERANCE));
    }

    @ParameterizedTest(name = "equirectangular round-trip {0}, {1}")
    @CsvSource({
            "0.0, 0.0",
            "51.0, 10.0",
            "89.0, 179.0",
            "-89.0, -179.0",
    })
    void equirectangularRoundTripsExactly(double latitude, double longitude) {
        Projection projection = new EquirectangularProjection(51.0);
        PlanePoint plane = projection.toPlane(latitude, longitude);
        GeoPoint back = projection.toGeographic(plane);

        assertThat(back.latitude()).isCloseTo(latitude, org.assertj.core.data.Offset.offset(DEGREE_TOLERANCE));
        assertThat(back.longitude()).isCloseTo(longitude, org.assertj.core.data.Offset.offset(DEGREE_TOLERANCE));
    }

    @Test
    @DisplayName("origin projects to the plane origin")
    void originMapsToZero() {
        PlanePoint plane = WebMercatorProjection.INSTANCE.toPlane(0.0, 0.0);
        assertThat(plane.east()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(METER_TOLERANCE));
        assertThat(plane.north()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(METER_TOLERANCE));
    }

    @Test
    @DisplayName("web mercator stretches by 1/cos(lat)")
    void webMercatorScaleFactor() {
        assertThat(WebMercatorProjection.INSTANCE.scaleFactor(0.0)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(WebMercatorProjection.INSTANCE.scaleFactor(60.0)).isCloseTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("web mercator clamps beyond its defined latitude range")
    void webMercatorClampsPoles() {
        PlanePoint atLimit = WebMercatorProjection.INSTANCE.toPlane(WebMercatorProjection.MAX_LATITUDE, 0.0);
        PlanePoint beyondLimit = WebMercatorProjection.INSTANCE.toPlane(89.9, 0.0);
        assertThat(beyondLimit.north()).isCloseTo(atLimit.north(), org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("equirectangular preserves north-south distance exactly")
    void equirectangularNorthSouthIsExact() {
        Projection projection = new EquirectangularProjection(51.0);
        double oneDegreeNorth = projection.toPlane(1.0, 0.0).north() - projection.toPlane(0.0, 0.0).north();
        // One degree of latitude on a sphere of radius R.
        double expected = Math.toRadians(1.0) * Projection.EARTH_RADIUS_METERS;
        assertThat(oneDegreeNorth).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void registryResolvesKnownProjections() {
        ProjectionRegistry registry = new ProjectionRegistry();
        assertThat(registry.create("web_mercator", 51.0)).isInstanceOf(WebMercatorProjection.class);
        assertThat(registry.create("EQUIRECTANGULAR", 51.0))
                .isInstanceOfSatisfying(EquirectangularProjection.class,
                        p -> assertThat(p.standardParallel()).isEqualTo(51.0));
        assertThat(registry.ids()).contains("web_mercator", "equirectangular", "plate_carree");
    }

    @Test
    void registryRejectsUnknownProjection() {
        ProjectionRegistry registry = new ProjectionRegistry();
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> registry.create("mollweide", 0.0)))
                .hasMessageContaining("Unknown projection");
    }
}
