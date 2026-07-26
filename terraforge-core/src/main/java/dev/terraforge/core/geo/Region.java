package dev.terraforge.core.geo;

import dev.terraforge.core.coord.GeoBounds;

/**
 * A first-level administrative division (state, land, province, oblast).
 *
 * @param id        database id
 * @param countryId owning {@link Country#id()}
 * @param name      English display name
 * @param bounds    bounding box of all polygons
 */
public record Region(int id, int countryId, String name, GeoBounds bounds) {
}
