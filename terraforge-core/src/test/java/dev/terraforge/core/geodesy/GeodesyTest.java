package dev.terraforge.core.geodesy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.coord.GeoPoint;
import org.assertj.core.data.Offset;
import org.assertj.core.data.Percentage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeodesyTest {

    private static final GeoPoint BERLIN = new GeoPoint(52.520008, 13.404954);
    private static final GeoPoint PARIS = new GeoPoint(48.856613, 2.352222);
    private static final GeoPoint FRANKFURT = new GeoPoint(50.110644, 8.682092);

    @Test
    @DisplayName("Berlin to Paris is about 878 km")
    void berlinToParis() {
        double km = Geodesy.vincentyMeters(BERLIN, PARIS) / 1000.0;
        assertThat(km).isCloseTo(878.0, Offset.offset(3.0));
    }

    @Test
    @DisplayName("haversine agrees with vincenty within 0.5%")
    void haversineAgreesWithVincenty() {
        assertThat(Geodesy.haversineMeters(BERLIN, PARIS))
                .isCloseTo(Geodesy.vincentyMeters(BERLIN, PARIS), Percentage.withPercentage(0.5));
        assertThat(Geodesy.haversineMeters(BERLIN, FRANKFURT))
                .isCloseTo(Geodesy.vincentyMeters(BERLIN, FRANKFURT), Percentage.withPercentage(0.5));
    }

    @Test
    void identicalPointsAreZeroApart() {
        assertThat(Geodesy.vincentyMeters(BERLIN, BERLIN)).isZero();
        assertThat(Geodesy.haversineMeters(BERLIN, BERLIN)).isZero();
    }

    @Test
    @DisplayName("one degree of latitude is about 111 km")
    void oneDegreeOfLatitude() {
        double meters = Geodesy.vincentyMeters(50.0, 10.0, 51.0, 10.0);
        assertThat(meters / 1000.0).isCloseTo(111.2, Offset.offset(0.5));
    }

    @Test
    @DisplayName("antipodal points fall back gracefully instead of hanging")
    void antipodalDoesNotFail() {
        double meters = Geodesy.vincentyMeters(0.0, 0.0, 0.0, 180.0);
        assertThat(meters).isGreaterThan(19_000_000.0).isLessThan(21_000_000.0);
    }

    @Test
    void bearingFromBerlinToParisPointsWest() {
        double bearing = Geodesy.initialBearingDegrees(
                BERLIN.latitude(), BERLIN.longitude(), PARIS.latitude(), PARIS.longitude());
        assertThat(bearing).isBetween(230.0, 260.0);
        assertThat(Geodesy.compassPoint(bearing)).isIn("SW", "WSW");
    }

    @Test
    void compassPointsCoverTheCircle() {
        assertThat(Geodesy.compassPoint(0.0)).isEqualTo("N");
        assertThat(Geodesy.compassPoint(90.0)).isEqualTo("E");
        assertThat(Geodesy.compassPoint(180.0)).isEqualTo("S");
        assertThat(Geodesy.compassPoint(270.0)).isEqualTo("W");
        assertThat(Geodesy.compassPoint(359.9)).isEqualTo("N");
    }
}
