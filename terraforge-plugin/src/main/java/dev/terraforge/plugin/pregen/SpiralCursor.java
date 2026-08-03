package dev.terraforge.plugin.pregen;

import java.util.ArrayList;
import java.util.List;

/** Deterministic square spiral; ordinal progress is sufficient for durable resume. */
public record SpiralCursor(long ordinal) {
    public SpiralCursor { if (ordinal < 0) throw new IllegalArgumentException("ordinal must not be negative"); }
    public static SpiralCursor from(long ordinal) { return new SpiralCursor(ordinal); }
    public List<Chunk> next(int count) {
        if (count < 0) throw new IllegalArgumentException("count must not be negative");
        List<Chunk> result = new ArrayList<>(count);
        for (long index = ordinal; result.size() < count; index++) result.add(at(index));
        return List.copyOf(result);
    }
    public SpiralCursor advance(long count) { return new SpiralCursor(Math.addExact(ordinal, count)); }
    public static Chunk at(long ordinal) {
        if (ordinal == 0) return new Chunk(0, 0);
        long ring = (long) Math.ceil((Math.sqrt(ordinal + 1) - 1) / 2.0);
        long side = ring * 2;
        long start = (2 * ring - 1) * (2 * ring - 1);
        long offset = ordinal - start;
        if (offset < side) return new Chunk((int) ring, (int) (-ring + 1 + offset));
        if (offset < side * 2) return new Chunk((int) (ring - 1 - (offset - side)), (int) ring);
        if (offset < side * 3) return new Chunk((int) -ring, (int) (ring - 1 - (offset - side * 2)));
        return new Chunk((int) (-ring + 1 + (offset - side * 3)), (int) -ring);
    }
    public record Chunk(int x, int z) {}
}
