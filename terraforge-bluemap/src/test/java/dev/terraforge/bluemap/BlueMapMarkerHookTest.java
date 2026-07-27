package dev.terraforge.bluemap;

import static org.assertj.core.api.Assertions.assertThat;

import de.bluecolored.bluemap.api.markers.MarkerSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The refresh path replaces TerraForge's sets on every BlueMap reload. What it must never do is
 * touch anybody else's -- Towny publishes town and nation sets to the same maps.
 */
class BlueMapMarkerHookTest {

    private static Map<String, MarkerSet> sets(String... ids) {
        Map<String, MarkerSet> sets = new LinkedHashMap<>();
        for (String id : ids) {
            sets.put(id, MarkerSet.builder().label(id).build());
        }
        return sets;
    }

    @Test
    void removesEverySetTerraForgeOwns() {
        Map<String, MarkerSet> sets = sets(BlueMapMarkerSets.CITY_SET, BlueMapMarkerSets.COUNTRY_SET,
                BlueMapMarkerSets.POI_SET);

        BlueMapMarkerHook.removeOwnSets(sets);

        assertThat(sets).isEmpty();
    }

    @Test
    void leavesTownyAndOtherPluginsAlone() {
        Map<String, MarkerSet> sets = sets(BlueMapMarkerSets.CITY_SET, "towny.markerset",
                "nations", "myplugin-pois");

        BlueMapMarkerHook.removeOwnSets(sets);

        assertThat(sets).containsOnlyKeys("towny.markerset", "nations", "myplugin-pois");
    }

    @Test
    void aSetMerelyMentioningTerraForgeIsNotOurs() {
        Map<String, MarkerSet> sets = sets("someplugin-terraforge-cities", "cities-terraforge");

        BlueMapMarkerHook.removeOwnSets(sets);

        assertThat(sets).containsOnlyKeys("someplugin-terraforge-cities", "cities-terraforge");
    }

    @Test
    void anEmptyMapIsNotAnError() {
        Map<String, MarkerSet> sets = sets();

        BlueMapMarkerHook.removeOwnSets(sets);

        assertThat(sets).isEmpty();
    }
}
