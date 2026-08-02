package dev.terraforge.geo.database;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.api.BlockPoint;
import dev.terraforge.core.api.ProjectedRing;
import dev.terraforge.core.api.RegionOptions;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.projection.EquirectangularProjection;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JtsBoundaryProjectorTest {

    @TempDir
    Path tmp;

    private SqliteBoundaryIndex index;
    private JtsBoundaryProjector projector;

    @BeforeEach
    void setUp() throws Exception {
        index = SqliteBoundaryIndex.load(TestGeoDatabase.create(tmp));
        CoordinateTransformer transformer = new CoordinateTransformer(
                new EquirectangularProjection(0.0), new GeoPoint(0.0, 0.0), 1.0);
        projector = new JtsBoundaryProjector(index, transformer);
    }

    @Test
    void unionsAdjacentCountriesIntoOneRing() {
        List<ProjectedRing> rings = projector.projectCountryUnion(
                List.of("AA", "BB"), RegionOptions.defaults());

        assertThat(rings).hasSize(1);
        assertThat(rings.get(0).points()).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void projectsIntoExpectedBlockArea() {
        List<ProjectedRing> rings = projector.projectCountryUnion(
                List.of("AA", "BB"), new RegionOptions(1.0, 10_000, 1.0));

        ProjectedRing ring = rings.get(0);
        int minX = ring.points().stream().mapToInt(BlockPoint::x).min().orElseThrow();
        int maxX = ring.points().stream().mapToInt(BlockPoint::x).max().orElseThrow();
        int minZ = ring.points().stream().mapToInt(BlockPoint::z).min().orElseThrow();
        int maxZ = ring.points().stream().mapToInt(BlockPoint::z).max().orElseThrow();

        assertThat(minX).isZero();
        assertThat(maxX).isGreaterThanOrEqualTo(200);
        assertThat(minZ).isLessThanOrEqualTo(-100);
        assertThat(maxZ).isZero();
    }

    @Test
    void unknownCountryIsSkipped() {
        List<ProjectedRing> rings = projector.projectCountryUnion(
                List.of("ZZ", "AA"), new RegionOptions(1.0, 10_000, 1.0));

        assertThat(rings).hasSize(1);
    }

    @Test
    void emptyInputProducesNoRings() {
        assertThat(projector.projectCountryUnion(List.of(), RegionOptions.defaults())).isEmpty();
        assertThat(projector.projectCountryUnion(List.of("ZZ"), RegionOptions.defaults())).isEmpty();
    }

    @Test
    void simplifyFitsVertexBudget() {
        List<ProjectedRing> rings = projector.projectCountryUnion(
                List.of("AA", "BB"), new RegionOptions(1.0, 4, 1.0));

        assertThat(rings).isNotEmpty();
        assertThat(rings.get(0).points()).hasSizeLessThanOrEqualTo(4);
    }
}
