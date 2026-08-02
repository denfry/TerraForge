package dev.terraforge.core.api;

/**
 * A country identified at a point.
 *
 * @param isoCode ISO 3166-1 alpha-2 code (uppercase)
 * @param name    English display name
 */
public record CountryHit(String isoCode, String name) {
}
