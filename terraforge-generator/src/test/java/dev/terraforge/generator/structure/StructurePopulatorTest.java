package dev.terraforge.generator.structure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.terraforge.core.terrain.ClimateBiome;
import org.junit.jupiter.api.Test;

class StructurePopulatorTest {

    @Test
    void eachSquareHoldsAtMostOneSiteAwayFromItsEdge() {
        int spacing = 12;
        StructurePopulator populator = new StructurePopulator(null, spacing, 0.6);
        int squares = 0;
        int sites = 0;
        for (int squareZ = -10; squareZ < 10; squareZ++) {
            for (int squareX = -10; squareX < 10; squareX++) {
                squares++;
                int inSquare = 0;
                for (int z = 0; z < spacing; z++) {
                    for (int x = 0; x < spacing; x++) {
                        if (populator.isSite(squareX * spacing + x, squareZ * spacing + z)) {
                            inSquare++;
                            assertThat(x).isBetween(1, spacing - 2);
                            assertThat(z).isBetween(1, spacing - 2);
                        }
                    }
                }
                assertThat(inSquare).isLessThanOrEqualTo(1);
                sites += inSquare;
            }
        }
        assertThat((double) sites / squares).isBetween(0.5, 0.7);
    }

    @Test
    void theSameChunkIsAlwaysASite() {
        StructurePopulator a = new StructurePopulator(null, 12, 0.6);
        StructurePopulator b = new StructurePopulator(null, 12, 0.6);
        for (int z = -50; z < 50; z++) {
            for (int x = -50; x < 50; x++) {
                assertThat(a.isSite(x, z)).isEqualTo(b.isSite(x, z));
            }
        }
    }

    @Test
    void noneWithZeroChance() {
        StructurePopulator none = new StructurePopulator(null, 12, 0.0);
        for (int z = -30; z < 30; z++) {
            for (int x = -30; x < 30; x++) {
                assertThat(none.isSite(x, z)).isFalse();
            }
        }
    }

    @Test
    void rejectsASpacingWithNoInterior() {
        assertThatThrownBy(() -> new StructurePopulator(null, 2, 0.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStructureFitsTheClimate() {
        assertThat(StructureKind.forSite(ClimateBiome.BEACH, true, 0.3)).isEqualTo(StructureKind.SHIPWRECK);
        assertThat(StructureKind.forSite(ClimateBiome.DESERT, false, 0.1)).isEqualTo(StructureKind.DESERT_RUIN);
        assertThat(StructureKind.forSite(ClimateBiome.TROPICAL_RAINFOREST, false, 0.1))
                .isEqualTo(StructureKind.JUNGLE_SHRINE);
        assertThat(StructureKind.forSite(ClimateBiome.TUNDRA, false, 0.1)).isEqualTo(StructureKind.SNOW_SHELTER);
        assertThat(StructureKind.forSite(ClimateBiome.ALPINE, false, 0.1)).isEqualTo(StructureKind.MOUNTAIN_HUT);
        assertThat(StructureKind.forSite(ClimateBiome.OCEAN, true, 0.1)).isNull();
        assertThat(StructureKind.forSite(ClimateBiome.RIVER, false, 0.1)).isNull();
    }

    @Test
    void everyStructureFitsInsideItsChunk() {
        for (StructureKind kind : StructureKind.values()) {
            // Centred on block 8: the footprint plus one block of rubble stays within 0..15.
            assertThat(8 - kind.radius - 1).isGreaterThanOrEqualTo(0);
            assertThat(8 + kind.radius + 1).isLessThanOrEqualTo(15);
        }
    }
}
