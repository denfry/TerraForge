package dev.terraforge.core.geo;

import dev.terraforge.core.coord.GeoBounds;

/**
 * A sovereign state or dependent territory from the boundary dataset.
 *
 * <p>The polygon itself is not held here: geometry lives in the spatial index of the geo module and
 * is loaded on demand, so a country list can be kept in memory cheaply.
 *
 * @param id       database id
 * @param isoCode  ISO 3166-1 alpha-2 code (uppercase)
 * @param name     English display name
 * @param bounds   bounding box of all polygons
 */
public record Country(int id, String isoCode, String name, GeoBounds bounds) {
}
