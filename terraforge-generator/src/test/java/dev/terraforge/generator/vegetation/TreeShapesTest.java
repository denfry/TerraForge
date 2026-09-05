package dev.terraforge.generator.vegetation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.generator.vegetation.TreeBlueprint.Kind;
import dev.terraforge.generator.vegetation.TreeBlueprint.Voxel;
import dev.terraforge.generator.vegetation.TreeBlueprint.Wood;
import dev.terraforge.generator.vegetation.TreeShapes.Shape;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The procedural trees: bounded so they fit the populator's buffer, and deterministic per draw. */
class TreeShapesTest {

    @ParameterizedTest
    @EnumSource(Shape.class)
    void everyShapeFitsThePopulatorBufferAndStandsOnItsGround(Shape shape) {
        for (int seed = 0; seed < 200; seed++) {
            TreeBlueprint tree = TreeShapes.build(shape, Wood.OAK, new Random(seed));

            assertThat(tree.voxels()).isNotEmpty();
            assertThat(tree.radius()).isLessThanOrEqualTo(TreeShapes.MAX_RADIUS);
            assertThat(tree.height()).isLessThanOrEqualTo(TreeShapes.MAX_HEIGHT);
            assertThat(tree.voxels()).allMatch(v -> v.dy() >= 1, "nothing below the ground");
            assertThat(tree.voxels()).anyMatch(v -> v.dy() == 1 && v.kind().isLog(), "a trunk on the ground");
            Set<Voxel> positions = new HashSet<>();
            for (Voxel v : tree.voxels()) {
                assertThat(positions.add(new Voxel(v.dx(), v.dy(), v.dz(), Kind.LEAVES)))
                        .as("one block per position").isTrue();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void theSameDrawGivesTheSameTree(Shape shape) {
        for (int seed = 0; seed < 50; seed++) {
            assertThat(TreeShapes.build(shape, Wood.SPRUCE, new Random(seed)))
                    .isEqualTo(TreeShapes.build(shape, Wood.SPRUCE, new Random(seed)));
        }
        assertThat(TreeShapes.build(shape, Wood.SPRUCE, new Random(1)))
                .isNotEqualTo(TreeShapes.build(shape, Wood.SPRUCE, new Random(2)));
    }

    @Test
    void silhouettesAreWhatTheyClaim() {
        Random random = new Random(4);
        TreeBlueprint willow = TreeShapes.build(Shape.WILLOW, Wood.OAK, random);
        TreeBlueprint palm = TreeShapes.build(Shape.PALM, Wood.JUNGLE, random);
        TreeBlueprint baobab = TreeShapes.build(Shape.BAOBAB, Wood.ACACIA, random);
        TreeBlueprint pine = TreeShapes.build(Shape.TALL_PINE, Wood.SPRUCE, random);
        TreeBlueprint dead = TreeShapes.build(Shape.DEAD_TREE, Wood.OAK, random);
        TreeBlueprint fallen = TreeShapes.build(Shape.FALLEN_LOG, Wood.OAK, random);

        // A willow's curtains reach below its crown; some leaves hang within three blocks of the ground.
        assertThat(willow.voxels()).anyMatch(v -> v.kind() == Kind.LEAVES && v.dy() <= 3);
        // A palm has no leaves below its top and its fronds spread three blocks or more.
        int palmTop = palm.voxels().stream().filter(v -> v.kind().isLog()).mapToInt(Voxel::dy).max().orElseThrow();
        assertThat(palm.voxels()).filteredOn(v -> v.kind() == Kind.LEAVES).allMatch(v -> v.dy() >= palmTop - 1);
        assertThat(palm.radius()).isGreaterThanOrEqualTo(3);
        // A baobab is wider than it is tall.
        assertThat(baobab.radius() * 2 + 1).isGreaterThan(baobab.height() / 2);
        assertThat(baobab.voxels()).filteredOn(v -> v.dy() == 1 && v.kind().isLog()).hasSize(5);
        // A tall pine is tall and narrow.
        assertThat(pine.height()).isGreaterThanOrEqualTo(12);
        assertThat(pine.radius()).isLessThanOrEqualTo(2);
        // A dead tree has no leaves at all; a fallen log lies flat.
        assertThat(dead.voxels()).noneMatch(v -> v.kind() == Kind.LEAVES);
        assertThat(fallen.height()).isLessThanOrEqualTo(2);
        assertThat(fallen.voxels()).filteredOn(v -> v.kind().isLog()).allMatch(v -> v.kind() != Kind.LOG_Y);
    }
}
