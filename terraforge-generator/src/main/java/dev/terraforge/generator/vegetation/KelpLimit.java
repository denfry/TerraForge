package dev.terraforge.generator.vegetation;

/**
 * How long a kelp stalk may be.
 *
 * <p>Oceans here are far deeper than vanilla's, so a stalk that follows the water column stands fifty
 * blocks tall, and breaking one drops the whole column a block per tick. Limiting the length also
 * generates the tip fully grown: a limit the plant could simply grow past would limit nothing.
 *
 * @param lengthFraction every stalk is cut to this share of its length, rounded up
 * @param maxHeight      no stalk is longer than this; {@code 0} means no limit
 */
public record KelpLimit(double lengthFraction, int maxHeight) {

    /** Stalks as tall as the water lets them be. */
    public static final KelpLimit NONE = new KelpLimit(1.0, 0);

    /** The length a stalk of {@code length} blocks is allowed to keep; at least one block. */
    public int apply(int length) {
        if (length <= 1) {
            return length;
        }
        // The epsilon keeps 0.5 of an even length exact: 0.5 * 56 must not round up to 29.
        int kept = (int) Math.clamp((long) Math.ceil(length * lengthFraction - 1e-9), 1L, length);
        return maxHeight > 0 ? Math.min(kept, maxHeight) : kept;
    }

    /** Whether a limited stalk's tip must be generated fully grown. */
    public boolean stopsGrowth() {
        return lengthFraction < 1.0 || maxHeight > 0;
    }
}
