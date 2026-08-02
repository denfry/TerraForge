package dev.terraforge.geo.database;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.api.CountryHit;
import dev.terraforge.core.coord.CoordinateTransformer;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.projection.EquirectangularProjection;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockCountryResolverTest {

    @TempDir
    Path tmp;

    private BlockCountryResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        SqliteBoundaryIndex index = SqliteBoundaryIndex.load(TestGeoDatabase.create(tmp));
        CoordinateTransformer transformer = new CoordinateTransformer(
                new EquirectangularProjection(0.0), new GeoPoint(0.0, 0.0), 1.0);
        resolver = new BlockCountryResolver(index, transformer);
    }

    @Test
    void resolvesBlockInsideFirstCountry() {
        Optional<CountryHit> hit = resolver.countryAt(50, -50);
        assertThat(hit).hasValueSatisfying(country -> {
            assertThat(country.isoCode()).isEqualTo("AA");
        });
    }

    @Test
    void resolvesBlockInsideSecondCountry() {
        Optional<CountryHit> hit = resolver.countryAt(150, -50);
        assertThat(hit).hasValueSatisfying(country -> {
            assertThat(country.isoCode()).isEqualTo("BB");
        });
    }

    @Test
    void returnsEmptyOverOcean() {
        assertThat(resolver.countryAt(50_000, 50_000)).isEmpty();
    }
}
