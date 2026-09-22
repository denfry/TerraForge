package dev.terraforge.plugin.world;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Regression for the production world that 1.16 players could not play: -512..512 behind
 * ViaBackwards, so everything below y=0 -- the sea beds and every diamond -- was void to them, and
 * nothing in the log said so.
 */
class LegacyClientFitTest {

    @Test
    void aWorldReachingBelowZeroIsReportedWhenOldClientsCanJoin() {
        String warning = LegacyClientFit.warning(-512, 512, true);

        assertThat(warning).contains("-512..511").contains("y 0..255")
                .contains("terrain.min-y: 0").contains("terrain.max-y: 256");
    }

    @Test
    void theVanillaModernHeightIsAlsoTooTallForA116Client() {
        assertThat(LegacyClientFit.warning(-64, 320, true)).isNotNull();
    }

    @Test
    void theLegacyFrameAndServersWithoutViaBackwardsAreQuiet() {
        assertThat(LegacyClientFit.warning(0, 256, true)).isNull();
        assertThat(LegacyClientFit.warning(-512, 512, false)).isNull();
    }
}
