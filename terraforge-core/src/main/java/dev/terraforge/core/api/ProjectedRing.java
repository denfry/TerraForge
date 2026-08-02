package dev.terraforge.core.api;

import java.util.List;

/**
 * A closed polygon ring in Minecraft block coordinates (X/Z), without a repeated closing point.
 *
 * <p>Produced by {@link GeoBoundaryProjector} so a consumer plugin can turn it into a
 * WorldGuard {@code PolygonRegion} directly.
 *
 * @param points the ring vertices, ordered, first and last NOT equal
 */
public record ProjectedRing(List<BlockPoint> points) {

    public ProjectedRing {
        points = List.copyOf(points);
    }
}
