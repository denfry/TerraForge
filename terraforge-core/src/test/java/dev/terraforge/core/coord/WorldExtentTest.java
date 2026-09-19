package dev.terraforge.core.coord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.core.projection.EquirectangularProjection;
import dev.terraforge.core.projection.WebMercatorProjection;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldExtentTest {

    private static WorldExtent planet(double originLatitude, double originLongitude, double blocksPerKm) {
        return WorldExtent.ofPlanet(new CoordinateTransformer(
                new EquirectangularProjection(originLatitude),
                new GeoPoint(originLatitude, originLongitude), blocksPerKm));
    }

    @Test
    @DisplayName("plate carree at one block per kilometre is the 40075 x 20037 km planet")
    void plateCarreePlanet() {
        WorldExtent extent = planet(0.0, 0.0, 1.0);
        assertThat(extent).isEqualTo(new WorldExtent(-20037, -10018, 20036, 10017));
        assertThat(extent.width()).isEqualTo(40074);
        assertThat(extent.depth()).isEqualTo(20036);
    }

    @Test
    @DisplayName("every block inside is a distinct place; the block past each edge is a repeat or a smear")
    void edgesAreTheProjectionSeams() {
        CoordinateTransformer transformer = new CoordinateTransformer(
                new EquirectangularProjection(0.0), new GeoPoint(0.0, 0.0), 1.0);
        WorldExtent extent = WorldExtent.ofPlanet(transformer);

        // One full turn east of the western edge is the same meridian again.
        GeoPoint west = transformer.toGeographic(extent.minX() + 0.5, 0.5);
        GeoPoint wrapped = transformer.toGeographic(extent.minX() + 0.5 + 40075.016686, 0.5);
        assertThat(wrapped.longitude()).isCloseTo(west.longitude(), Offset.offset(1e-6));

        // Inside, the northernmost row is still short of the pole; two rows further it is clamped.
        assertThat(transformer.toGeographic(0.5, extent.minZ() + 0.5).latitude()).isLessThan(90.0);
        assertThat(transformer.toGeographic(0.5, extent.minZ() - 1.5).latitude()).isEqualTo(90.0);
    }

    @Test
    @DisplayName("an off-centre origin shifts the rectangle, it does not resize it")
    void originShiftsTheRectangle() {
        WorldExtent centred = planet(0.0, 0.0, 1.0);
        WorldExtent shifted = planet(0.0, 10.0, 1.0);
        assertThat(shifted.width()).isCloseTo(centred.width(), Offset.offset(1));
        assertThat(shifted.contains(0.0, 0.0)).isTrue();
        assertThat(shifted.minX()).isLessThan(centred.minX());
    }

    @Test
    @DisplayName("scale multiplies the rectangle")
    void scaleMultiplies() {
        assertThat(planet(0.0, 0.0, 2.0).width()).isCloseTo(80150, Offset.offset(2));
    }

    @Test
    @DisplayName("web mercator ends at its own maximum latitude and is square")
    void webMercatorIsSquare() {
        WorldExtent extent = WorldExtent.ofPlanet(new CoordinateTransformer(
                new WebMercatorProjection(), new GeoPoint(0.0, 0.0), 1.0));
        assertThat(extent.depth()).isCloseTo(extent.width(), Offset.offset(2));
    }

    @Test
    @DisplayName("an edge chunk counts as inside, the next one does not")
    void chunkIntersection() {
        WorldExtent extent = new WorldExtent(-20, -10, 19, 9);
        assertThat(extent.intersectsChunk(-2, -1)).isTrue();   // blocks -32..-17 touch -20
        assertThat(extent.intersectsChunk(-3, 0)).isFalse();
        assertThat(extent.intersectsChunk(1, 0)).isTrue();     // blocks 16..31 touch 19
        assertThat(extent.intersectsChunk(2, 0)).isFalse();
        assertThat(extent.intersectsChunk(0, 1)).isFalse();    // z 16..31 is past 9
    }

    @Test
    @DisplayName("contains is block-exact and clamp lands inside")
    void containsAndClamp() {
        WorldExtent extent = new WorldExtent(-20, -10, 19, 9);
        assertThat(extent.contains(19.99, 9.99)).isTrue();
        assertThat(extent.contains(20.0, 0.0)).isFalse();
        assertThat(extent.contains(-20.0, -10.0)).isTrue();
        assertThat(extent.contains(-20.01, 0.0)).isFalse();

        assertThat(extent.clampX(500.0, 0.5)).isEqualTo(19.5);
        assertThat(extent.clampZ(-500.0, 0.5)).isEqualTo(-9.5);
        assertThat(extent.clampX(3.0, 0.5)).isEqualTo(3.0);
        assertThat(extent.contains(extent.clampX(1e9, 0.5), extent.clampZ(-1e9, 0.5))).isTrue();
    }

    @Test
    void rejectsEmptyExtent() {
        assertThatThrownBy(() -> new WorldExtent(5, 0, 4, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
