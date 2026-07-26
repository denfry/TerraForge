package dev.terraforge.core.geo;

import dev.terraforge.core.coord.GeoPoint;
import java.util.OptionalLong;

/**
 * A populated place from the gazetteer -- a <strong>name and a coordinate only</strong>.
 *
 * <p>Cities are never built into the world. They exist so players can teleport to "Frankfurt",
 * so {@code /earth whereami} can report the nearest place, and so BlueMap can show a label. Any
 * actual settlement is built by players and managed by Towny.
 *
 * @param id         database id
 * @param name       display name
 * @param position   geographic position
 * @param population inhabitants, when the dataset provides it
 * @param countryId  owning {@link Country#id()}, or -1 when unresolved
 * @param capital    true for national capitals
 */
public record City(int id, String name, GeoPoint position, long population, int countryId, boolean capital) {

    public OptionalLong populationIfKnown() {
        return population > 0 ? OptionalLong.of(population) : OptionalLong.empty();
    }
}
