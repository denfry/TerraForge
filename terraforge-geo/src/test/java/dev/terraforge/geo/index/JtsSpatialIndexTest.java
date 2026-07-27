package dev.terraforge.geo.index;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.coord.GeoBounds;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

class JtsSpatialIndexTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    void performsExactPointInPolygonLookupAfterEnvelopeFiltering() {
        var index = new JtsSpatialIndex<>(List.of(
                entry("square", 10, 20, 12, 22)));

        assertThat(index.query(11, 21)).contains("square");
        assertThat(index.query(11, 23)).isEmpty();
        assertThat(index.query(Double.NaN, 21)).isEmpty();
        assertThat(index.query(91, 21)).isEmpty();
    }

    @Test
    void keepsSourceOrderForSharedBordersAndOrdersBoundsResultsDeterministically() {
        var index = new JtsSpatialIndex<>(List.of(
                entry("west", 0, 0, 10, 10),
                entry("east", 0, 10, 10, 20)));

        assertThat(index.query(5, 10)).contains("west");
        assertThat(index.queryBounds(new GeoBounds(4, 9, 6, 11))).containsExactly("west", "east");
        assertThat(index.size()).isEqualTo(2);
    }

    private static JtsSpatialIndex.Entry<String> entry(String value, double south, double west,
                                                         double north, double east) {
        return new JtsSpatialIndex.Entry<>(value, GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(west, south), new Coordinate(east, south), new Coordinate(east, north),
                new Coordinate(west, north), new Coordinate(west, south)
        }));
    }
}
