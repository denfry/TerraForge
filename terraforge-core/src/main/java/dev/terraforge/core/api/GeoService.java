package dev.terraforge.core.api;

import dev.terraforge.core.coord.GeoBounds;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.core.geo.City;
import dev.terraforge.core.geo.Country;
import dev.terraforge.core.geo.Region;
import java.util.List;
import java.util.Optional;

/**
 * Public API for administrative and gazetteer lookups.
 *
 * <p>All methods are backed by a spatial index and in-memory caches and are safe to call from any
 * thread. Lookups are designed to be cheap enough for command handlers; they are still far too
 * expensive to call per block, so the generator resolves them per chunk.
 */
public interface GeoService {

    Optional<Country> getCountry(double latitude, double longitude);

    Optional<Region> getRegion(double latitude, double longitude);

    Optional<City> getNearestCity(double latitude, double longitude);

    /** Nearest cities ordered by geodesic distance, limited to {@code radiusKm}. */
    List<City> getNearestCities(double latitude, double longitude, double radiusKm, int limit);

    Optional<Country> findCountryByName(String name);

    Optional<Country> findCountryByIsoCode(String isoCode);

    /** Case-insensitive prefix search, used by command tab completion. */
    List<Country> searchCountries(String prefix, int limit);

    Optional<City> findCityByName(String name);

    List<City> searchCities(String prefix, int limit);

    /** Representative point to teleport to for a country (its capital, else polygon centroid). */
    Optional<GeoPoint> getCountryAnchor(Country country);

    /** Area the loaded geodata actually covers -- the test region, or the whole world. */
    GeoBounds coverage();
}
