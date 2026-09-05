package dev.terraforge.generator.vegetation;

import dev.terraforge.generator.vegetation.TreeBlueprint.Kind;
import dev.terraforge.generator.vegetation.TreeBlueprint.Voxel;
import dev.terraforge.generator.vegetation.TreeBlueprint.Wood;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The procedural trees TerraForge draws itself.
 *
 * <p>Every shape is a pure function of a {@link Random}: the same draw gives the same tree. Shapes
 * are bounded -- no tree reaches more than {@link #MAX_RADIUS} blocks sideways or
 * {@link #MAX_HEIGHT} up -- so a tree started inside a chunk always fits the populator's
 * one-chunk buffer around it.
 */
public final class TreeShapes {

    /** No shape reaches further sideways than this. */
    public static final int MAX_RADIUS = 7;
    /** No shape reaches higher than this. */
    public static final int MAX_HEIGHT = 24;

    private TreeShapes() {
    }

    /** The shapes, each a species-neutral silhouette; the wood decides the species. */
    public enum Shape {
        /** A broad crown with leaf curtains trailing to the ground: river banks and marshes. */
        WILLOW,
        /** A leaning trunk under a spray of fronds: warm coasts and oases. */
        PALM,
        /** A massive short trunk under a flat, wide disc of leaves: the savanna. */
        BAOBAB,
        /** A tall bare trunk with a narrow crown high up: the taiga. */
        TALL_PINE,
        /** A narrow column of foliage from the ground to a point: Mediterranean shrubland. */
        CYPRESS,
        /** A trunk and a few bare branches, no leaves: steppe, semi-desert, burnt ground. */
        DEAD_TREE,
        /** A trunk on its side, mossed and mushroomed: the forest floor. */
        FALLEN_LOG,
        /** A leaf blob on one log, head-high: shrubland and savanna. */
        SHRUB
    }

    public static TreeBlueprint build(Shape shape, Wood wood, Random random) {
        Sketch sketch = new Sketch();
        switch (shape) {
            case WILLOW -> willow(sketch, random);
            case PALM -> palm(sketch, random);
            case BAOBAB -> baobab(sketch, random);
            case TALL_PINE -> tallPine(sketch, random);
            case CYPRESS -> cypress(sketch, random);
            case DEAD_TREE -> deadTree(sketch, random);
            case FALLEN_LOG -> fallenLog(sketch, random);
            case SHRUB -> shrub(sketch, random);
        }
        return new TreeBlueprint(wood, sketch.voxels());
    }

    private static void willow(Sketch s, Random random) {
        int height = 5 + random.nextInt(3);
        s.trunk(height);
        // Three or four branches leave the trunk just under the crown.
        int branches = 3 + random.nextInt(2);
        for (int i = 0; i < branches; i++) {
            int[] dir = AXIS_DIRS[random.nextInt(4)];
            Kind kind = dir[0] != 0 ? Kind.LOG_X : Kind.LOG_Z;
            s.add(dir[0], height - 1, dir[1], kind);
            s.add(dir[0] * 2, height, dir[1] * 2, kind);
        }
        int rx = 3 + random.nextInt(2);
        int ry = 2;
        s.ellipsoid(0, height, 0, rx, ry, rx, random, 0.1);
        // Curtains: from the crown's rim, leaves hang two to four blocks towards the ground.
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dz = -rx; dz <= rx; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < rx - 1.0 || d > rx + 0.3 || random.nextDouble() < 0.35) {
                    continue;
                }
                int drop = 2 + random.nextInt(3);
                for (int i = 1; i <= drop; i++) {
                    s.add(dx, height - ry - i + 1, dz, Kind.LEAVES);
                }
            }
        }
    }

    private static void palm(Sketch s, Random random) {
        int height = 6 + random.nextInt(5);
        int[] lean = AXIS_DIRS[random.nextInt(4)];
        int leanEvery = 3 + random.nextInt(2);
        int x = 0;
        int z = 0;
        for (int y = 1; y <= height; y++) {
            if (y % leanEvery == 0 && Math.abs(x + lean[0]) <= 2 && Math.abs(z + lean[1]) <= 2) {
                s.add(x, y, z, Kind.LOG_Y); // the step block, so the trunk has no gap
                x += lean[0];
                z += lean[1];
            }
            s.add(x, y, z, Kind.LOG_Y);
        }
        s.add(x, height + 1, z, Kind.LEAVES);
        // Fronds in six to eight of the eight compass directions, each arching out and down.
        int fronds = 6 + random.nextInt(3);
        for (int i = 0; i < ALL_DIRS.length; i++) {
            if (i >= fronds && random.nextBoolean()) {
                continue;
            }
            int length = 3 + random.nextInt(2);
            for (int step = 1; step <= length; step++) {
                int dy = step <= 2 ? 1 : step == 3 ? 0 : -1;
                s.add(x + ALL_DIRS[i][0] * step, height + dy, z + ALL_DIRS[i][1] * step, Kind.LEAVES);
            }
        }
    }

    private static void baobab(Sketch s, Random random) {
        int trunk = 7 + random.nextInt(4);
        // A plus-shaped trunk, three blocks across, tapering to the centre near the top.
        for (int y = 1; y <= trunk; y++) {
            s.add(0, y, 0, Kind.LOG_Y);
            if (y <= trunk - 2) {
                for (int[] dir : AXIS_DIRS) {
                    s.add(dir[0], y, dir[1], Kind.LOG_Y);
                }
            }
        }
        // Four limbs strike out and up from the top of the trunk.
        int reach = 2 + random.nextInt(2);
        for (int[] limb : AXIS_DIRS) {
            for (int step = 1; step <= reach; step++) {
                s.add(limb[0] * step, trunk + step, limb[1] * step, limb[0] != 0 ? Kind.LOG_X : Kind.LOG_Z);
            }
        }
        int crownY = trunk + reach;
        int radius = 5 + random.nextInt(2);
        s.disc(0, crownY, 0, radius, random, 0.15);
        s.disc(0, crownY + 1, 0, radius - 2, random, 0.25);
    }

    private static void tallPine(Sketch s, Random random) {
        int height = 12 + random.nextInt(7);
        s.trunk(height);
        s.add(0, height + 1, 0, Kind.LEAVES);
        int crownBase = height - height * 2 / 5;
        for (int y = crownBase; y <= height; y++) {
            int fromTop = height - y;
            int radius = fromTop < 2 ? 1 : (fromTop % 2 == 0 ? 2 : 1);
            s.ring(0, y, 0, radius, random, 0.1);
        }
        // A few sparse lower whorls to break the bare trunk.
        for (int y = crownBase - 4; y < crownBase; y += 2) {
            if (y > 2 && random.nextBoolean()) {
                s.ring(0, y, 0, 1, random, 0.5);
            }
        }
    }

    private static void cypress(Sketch s, Random random) {
        int height = 8 + random.nextInt(5);
        s.trunk(height);
        s.add(0, height + 1, 0, Kind.LEAVES);
        s.add(0, height + 2, 0, Kind.LEAVES);
        for (int y = 2; y <= height; y++) {
            s.ring(0, y, 0, 1, random, 0.0);
            boolean wide = y > 3 && y < height - 2;
            if (wide && random.nextDouble() < 0.5) {
                for (int[] dir : AXIS_DIRS) {
                    s.add(dir[0] * 2, y, dir[1] * 2, Kind.LEAVES);
                }
            }
        }
    }

    private static void deadTree(Sketch s, Random random) {
        int height = 3 + random.nextInt(3);
        s.trunk(height);
        int branches = 2 + random.nextInt(2);
        for (int i = 0; i < branches; i++) {
            int y = Math.max(2, height - random.nextInt(2));
            int[] dir = AXIS_DIRS[random.nextInt(4)];
            int length = 1 + random.nextInt(2);
            for (int step = 1; step <= length; step++) {
                s.add(dir[0] * step, y, dir[1] * step, dir[0] != 0 ? Kind.LOG_X : Kind.LOG_Z);
            }
            if (random.nextBoolean()) {
                s.add(dir[0] * length, y + 1, dir[1] * length, Kind.LOG_Y);
            }
        }
    }

    private static void fallenLog(Sketch s, Random random) {
        int length = 3 + random.nextInt(3);
        boolean alongX = random.nextBoolean();
        for (int i = 0; i < length; i++) {
            int dx = alongX ? i : 0;
            int dz = alongX ? 0 : i;
            s.add(dx, 1, dz, alongX ? Kind.LOG_X : Kind.LOG_Z);
            double top = random.nextDouble();
            if (top < 0.35) {
                s.add(dx, 2, dz, Kind.MOSS);
            } else if (top < 0.45) {
                s.add(dx, 2, dz, Kind.MUSHROOM);
            }
        }
    }

    private static void shrub(Sketch s, Random random) {
        s.add(0, 1, 0, Kind.LOG_Y);
        int radius = random.nextDouble() < 0.3 ? 2 : 1;
        s.ellipsoid(0, 1, 0, radius, 1, radius, random, 0.2);
        s.add(0, 2, 0, Kind.LEAVES);
        if (radius == 2) {
            s.ring(0, 2, 0, 1, random, 0.3);
        }
    }

    private static final int[][] AXIS_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] ALL_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};

    /** Collects voxels, one per position, with logs winning over leaves. */
    private static final class Sketch {
        private final Map<Long, Voxel> voxels = new HashMap<>();
        private final List<Long> order = new ArrayList<>();

        void add(int dx, int dy, int dz, Kind kind) {
            if (Math.abs(dx) > MAX_RADIUS || Math.abs(dz) > MAX_RADIUS || dy < 1 || dy > MAX_HEIGHT) {
                return;
            }
            long key = ((long) (dx + 64) << 32) | ((long) (dy + 64) << 16) | (dz + 64);
            Voxel existing = voxels.get(key);
            if (existing == null) {
                voxels.put(key, new Voxel(dx, dy, dz, kind));
                order.add(key);
            } else if (kind.isLog() && !existing.kind().isLog()) {
                voxels.put(key, new Voxel(dx, dy, dz, kind));
            }
        }

        void trunk(int height) {
            for (int y = 1; y <= height; y++) {
                add(0, y, 0, Kind.LOG_Y);
            }
        }

        /** Leaves within an ellipsoid, with a share of the rim left out so no two crowns match. */
        void ellipsoid(int cx, int cy, int cz, int rx, int ry, int rz, Random random, double rimGaps) {
            for (int dy = -ry; dy <= ry; dy++) {
                for (int dx = -rx; dx <= rx; dx++) {
                    for (int dz = -rz; dz <= rz; dz++) {
                        double d = sq(dx / (double) rx) + sq(dy / (double) ry) + sq(dz / (double) rz);
                        if (d > 1.0 || (d > 0.7 && random.nextDouble() < rimGaps)) {
                            continue;
                        }
                        add(cx + dx, cy + dy, cz + dz, Kind.LEAVES);
                    }
                }
            }
        }

        /** A flat disc of leaves. */
        void disc(int cx, int cy, int cz, int radius, Random random, double rimGaps) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > radius + 0.3 || (d > radius - 1.0 && random.nextDouble() < rimGaps)) {
                        continue;
                    }
                    add(cx + dx, cy, cz + dz, Kind.LEAVES);
                }
            }
        }

        /** A one-block-thick square ring of leaves around a point, corners thinned. */
        void ring(int cx, int cy, int cz, int radius, Random random, double cornerGaps) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius;
                    if (corner && (radius > 1 || random.nextDouble() < cornerGaps)) {
                        continue;
                    }
                    add(cx + dx, cy, cz + dz, Kind.LEAVES);
                }
            }
        }

        List<Voxel> voxels() {
            List<Voxel> out = new ArrayList<>(order.size());
            for (Long key : order) {
                out.add(voxels.get(key));
            }
            return List.copyOf(out);
        }

        private static double sq(double v) {
            return v * v;
        }
    }
}
