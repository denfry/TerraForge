package dev.terraforge.core.terrain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.core.data.LandcoverProvider.LandcoverClass;
import dev.terraforge.core.data.WaterProvider.WaterType;
import org.junit.jupiter.api.Test;

class ClimateBiomeResolverTest {

    private final ClimateBiomeResolver resolver = new ClimateBiomeResolver();

    @Test
    void theSameTreeCoverIsADifferentForestAtDifferentLatitudes() {
        assertThat(land(5.0, 100.0, LandcoverClass.TREE_COVER)).isEqualTo(ClimateBiome.TROPICAL_RAINFOREST);
        assertThat(land(15.0, 100.0, LandcoverClass.TREE_COVER))
                .isEqualTo(ClimateBiome.TROPICAL_SEASONAL_FOREST);
        assertThat(land(48.0, 100.0, LandcoverClass.TREE_COVER)).isEqualTo(ClimateBiome.TEMPERATE_FOREST);
        assertThat(land(62.0, 100.0, LandcoverClass.TREE_COVER)).isEqualTo(ClimateBiome.BOREAL_FOREST);
        assertThat(land(70.0, 100.0, LandcoverClass.TREE_COVER)).isEqualTo(ClimateBiome.TUNDRA);
    }

    @Test
    void climateIsSymmetricAboutTheEquator() {
        // Up to 60 degrees; beyond it the south is the Antarctic ice sheet, see AntarcticaTest.
        for (double latitude : new double[] {5.0, 30.0, 48.0, 58.0}) {
            assertThat(land(-latitude, 100.0, LandcoverClass.TREE_COVER))
                    .isEqualTo(land(latitude, 100.0, LandcoverClass.TREE_COVER));
        }
    }

    @Test
    void aMountainPassesThroughForestMeadowRockAndIce() {
        double latitude = 46.0; // the Alps
        assertThat(land(latitude, 600.0, LandcoverClass.TREE_COVER)).isEqualTo(ClimateBiome.TEMPERATE_FOREST);
        assertThat(land(latitude, 1200.0, LandcoverClass.TREE_COVER)).isEqualTo(ClimateBiome.MOUNTAIN_FOREST);
        assertThat(land(latitude, 2000.0, LandcoverClass.TREE_COVER)).isEqualTo(ClimateBiome.ALPINE);
        assertThat(land(latitude, 4000.0, LandcoverClass.TREE_COVER)).isEqualTo(ClimateBiome.GLACIER);
    }

    @Test
    void theTreelineAndSnowlineFallTowardThePoles() {
        assertThat(ClimateBiomeResolver.treelineMeters(0.0))
                .isGreaterThan(ClimateBiomeResolver.treelineMeters(46.0));
        assertThat(ClimateBiomeResolver.treelineMeters(46.0))
                .isGreaterThan(ClimateBiomeResolver.treelineMeters(70.0));
        assertThat(ClimateBiomeResolver.snowlineMeters(46.0))
                .isGreaterThan(ClimateBiomeResolver.treelineMeters(46.0));
        assertThat(ClimateBiomeResolver.treelineMeters(89.0)).isZero();
    }

    @Test
    void oceanDepthAndLatitudeDecideTheOceanVariety() {
        assertThat(water(10.0, -50.0, WaterType.OCEAN)).isEqualTo(ClimateBiome.WARM_OCEAN);
        assertThat(water(45.0, -50.0, WaterType.OCEAN)).isEqualTo(ClimateBiome.OCEAN);
        assertThat(water(45.0, -3000.0, WaterType.OCEAN)).isEqualTo(ClimateBiome.DEEP_OCEAN);
        assertThat(water(75.0, -50.0, WaterType.OCEAN)).isEqualTo(ClimateBiome.FROZEN_OCEAN);
        assertThat(water(45.0, 300.0, WaterType.RIVER)).isEqualTo(ClimateBiome.RIVER);
    }

    @Test
    void lowLandIsShoreAndPolarShoreIsStony() {
        assertThat(land(45.0, 1.0, LandcoverClass.GRASSLAND)).isEqualTo(ClimateBiome.BEACH);
        assertThat(land(80.0, 1.0, LandcoverClass.GRASSLAND)).isEqualTo(ClimateBiome.STONY_SHORE);
    }

    @Test
    void builtUpLandBecomesWhatSurroundsIt() {
        // The dataset saw a city; TerraForge generates the landscape it stands on and nothing else.
        assertThat(land(48.0, 100.0, LandcoverClass.BUILT_UP)).isEqualTo(ClimateBiome.TEMPERATE_FOREST);
    }

    @Test
    void unknownLandcoverFallsBackToTheLatitudeBand() {
        assertThat(land(48.0, 100.0, LandcoverClass.UNKNOWN)).isEqualTo(ClimateBiome.TEMPERATE_FOREST);
        assertThat(land(5.0, 100.0, LandcoverClass.UNKNOWN)).isEqualTo(ClimateBiome.TROPICAL_RAINFOREST);
    }

    @Test
    void snowAndIceCoverIsGlacierAtAnyElevation() {
        assertThat(land(70.0, 50.0, LandcoverClass.SNOW_ICE)).isEqualTo(ClimateBiome.GLACIER);
    }

    @Test
    void everyClimateBiomeIsReachableExceptTheOnesPhaseSixUnlocks() {
        // SEMI_DESERT, WETLAND, MANGROVE and the deserts need real land cover; they are reachable
        // as soon as the data provides the class, which this asserts.
        assertThat(land(30.0, 200.0, LandcoverClass.BARE_SPARSE)).isEqualTo(ClimateBiome.DESERT);
        assertThat(land(45.0, 200.0, LandcoverClass.HERBACEOUS_WETLAND)).isEqualTo(ClimateBiome.WETLAND);
        assertThat(land(5.0, 200.0, LandcoverClass.MANGROVES)).isEqualTo(ClimateBiome.MANGROVE);
        assertThat(land(10.0, 200.0, LandcoverClass.GRASSLAND)).isEqualTo(ClimateBiome.SAVANNA);
    }

    private ClimateBiome land(double latitude, double elevation, LandcoverClass cover) {
        return resolver.resolve(latitude, elevation, WaterType.NONE, cover);
    }

    private ClimateBiome water(double latitude, double elevation, WaterType type) {
        return resolver.resolve(latitude, elevation, type, LandcoverClass.PERMANENT_WATER);
    }
}
