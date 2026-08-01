package dev.terraforge.cli.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.core.coord.GeoBounds;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The fetch plan and its concurrency bounds; the transfers themselves are {@link DownloadsTest}. */
class FetcherTest {

    @Test
    void aPlanetWideBoxPlansEveryDegreeCell() {
        Fetcher.Options options = worldOptions(Fetcher.DEFAULT_PARALLELISM);

        var plan = Fetcher.plan(options);
        long dem = plan.stream().filter(download -> download.dataset().equals("dem")).count();

        // One Copernicus tile per degree cell: 180 by 360. Ocean-only cells are simply not
        // published, which the fetch reports as absent rather than failing.
        assertThat(dem).isEqualTo(180L * 360L);
    }

    @Test
    void aPlanetWideBoxPlansLandcoverOnlyWhereWorldCoverIsPublished() {
        var plan = Fetcher.plan(worldOptions(Fetcher.DEFAULT_PARALLELISM));
        long landcover = plan.stream().filter(download -> download.dataset().equals("landcover")).count();

        // WorldCover is three-degree tiles between 60S and 84N: 48 rows of 120.
        assertThat(landcover).isEqualTo(48L * 120L);
    }

    @Test
    void refusesAParallelismThatWouldHammerAMirror() {
        assertThatThrownBy(() -> worldOptions(64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--parallel must be between 1 and 16");
    }

    @Test
    void refusesParallelismBelowOne() {
        assertThatThrownBy(() -> worldOptions(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--parallel must be between 1 and 16");
    }

    @Test
    void defaultsToTheSharedParallelismWhenNoneIsGiven() {
        Fetcher.Options options = new Fetcher.Options(GeoBounds.world(),
                SourceCatalog.DemResolution.GLO_90, "cities15000", Fetcher.DATASETS, false);

        assertThat(options.parallelism()).isEqualTo(Fetcher.DEFAULT_PARALLELISM);
    }

    @Test
    void bathymetryCanBeSkippedLikeEveryOtherDataset() {
        assertThat(Fetcher.DATASETS).contains("bathymetry");
        assertThat(Fetcher.datasets(java.util.List.of("bathymetry"))).doesNotContain("bathymetry");
    }

    private static Fetcher.Options worldOptions(int parallelism) {
        return new Fetcher.Options(GeoBounds.world(), SourceCatalog.DemResolution.GLO_90,
                "cities15000", Set.of("dem", "landcover"), false, parallelism);
    }
}
