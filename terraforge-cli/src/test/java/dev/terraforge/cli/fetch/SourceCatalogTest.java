package dev.terraforge.cli.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.cli.fetch.SourceCatalog.DemResolution;
import dev.terraforge.cli.fetch.SourceCatalog.Download;
import dev.terraforge.core.coord.GeoBounds;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceCatalogTest {

    @Test
    void asksForOneDemTilePerDegreeCellTheBoxTouches() {
        List<Download> tiles = SourceCatalog.dem(new GeoBounds(47.2, 8.9, 48.0, 10.1), DemResolution.GLO_90);

        // 47 alone: the box ends exactly at 48, so the cell starting there is not needed.
        assertThat(tiles).extracting(Download::fileName).containsExactly(
                "Copernicus_DSM_COG_30_N47_00_E008_00_DEM.tif",
                "Copernicus_DSM_COG_30_N47_00_E009_00_DEM.tif",
                "Copernicus_DSM_COG_30_N47_00_E010_00_DEM.tif");
        assertThat(tiles.get(0).uri()).hasToString("https://copernicus-dem-90m.s3.amazonaws.com/"
                + "Copernicus_DSM_COG_30_N47_00_E008_00_DEM/Copernicus_DSM_COG_30_N47_00_E008_00_DEM.tif");
        assertThat(tiles).allMatch(tile -> tile.dataset().equals("dem"));
    }

    @Test
    void namesSouthernAndWesternTilesTheWayThePublisherDoes() {
        List<Download> tiles = SourceCatalog.dem(new GeoBounds(-34.7, -58.5, -34.2, -58.2), DemResolution.GLO_30);

        assertThat(tiles).extracting(Download::fileName)
                .containsExactly("Copernicus_DSM_COG_10_S35_00_W059_00_DEM.tif");
    }

    @Test
    void selectsTheSingleGebcoQuadrantContainingACoastalBox() {
        List<Download> tiles = SourceCatalog.bathymetry(new GeoBounds(43.0, 6.0, 44.0, 7.0));

        assertThat(tiles).singleElement().satisfies(tile -> {
            assertThat(tile.dataset()).isEqualTo("bathymetry");
            assertThat(tile.fileName()).isEqualTo("gebco_2024_n90.0_s0.0_w0.0_e90.0.tif");
            assertThat(tile.uri()).hasToString("https://dap.ceda.ac.uk/bodc/gebco/global/gebco_2024/"
                    + "ice_surface_elevation/geotiff/gebco_2024_n90.0_s0.0_w0.0_e90.0.tif");
        });
    }

    @Test
    void alignsLandCoverToTheThreeDegreeGridItIsPublishedOn() {
        List<Download> tiles = SourceCatalog.landcover(new GeoBounds(47.0, 8.0, 48.0, 9.0));

        // 47N 8E sits inside the tile whose south-west corner is 45N 6E.
        assertThat(tiles).extracting(Download::fileName)
                .containsExactly("ESA_WorldCover_10m_2021_v200_N45E006_Map.tif");
    }

    @Test
    void roundsLandCoverTilesOutwardsAcrossTheEquatorAndPrimeMeridian() {
        List<Download> tiles = SourceCatalog.landcover(new GeoBounds(-1.0, -1.0, 1.0, 1.0));

        assertThat(tiles).extracting(Download::fileName).containsExactly(
                "ESA_WorldCover_10m_2021_v200_S03W003_Map.tif",
                "ESA_WorldCover_10m_2021_v200_S03E000_Map.tif",
                "ESA_WorldCover_10m_2021_v200_N00W003_Map.tif",
                "ESA_WorldCover_10m_2021_v200_N00E000_Map.tif");
    }

    @Test
    void asksForNoLandCoverWhereTheDatasetDoesNotReach() {
        assertThat(SourceCatalog.landcover(new GeoBounds(-85.0, 10.0, -70.0, 20.0))).isEmpty();
    }

    @Test
    void pinsNaturalEarthToAReleaseSoTheSameCommandPreparesTheSameWorld() {
        assertThat(SourceCatalog.boundaries()).extracting(download -> download.uri().toString())
                .allMatch(uri -> uri.contains("/" + SourceCatalog.NATURAL_EARTH_TAG + "/"));
    }

    @Test
    void includesTheAnonymousHydroRiversDownloadWithWater() {
        assertThat(SourceCatalog.water()).extracting(Download::uri)
                .anyMatch(uri -> uri.toString().contains("HydroRIVERS_v10_shp.zip"));
    }

    @Test
    void rejectsAGazetteerThatIsNotPublished() {
        assertThat(SourceCatalog.cities("cities5000").fileName()).isEqualTo("cities5000.zip");
        assertThatThrownBy(() -> SourceCatalog.cities("cities42"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cities15000");
    }

    @Test
    void readsTheDemResolutionTheWayAnOperatorWouldWriteIt() {
        assertThat(DemResolution.parse("30")).isEqualTo(DemResolution.GLO_30);
        assertThat(DemResolution.parse("90m")).isEqualTo(DemResolution.GLO_90);
        assertThat(DemResolution.parse("GLO-30")).isEqualTo(DemResolution.GLO_30);
        assertThatThrownBy(() -> DemResolution.parse("10")).isInstanceOf(IllegalArgumentException.class);
    }
}
