package dev.terraforge.core.data;

import dev.terraforge.core.geo.Country;
import dev.terraforge.core.geo.Region;
import java.util.Optional;

/**
 * Resolves administrative metadata for a point.
 *
 * <p>Boundaries are metadata only: they are never rendered into the world as walls, markers or
 * blocks. Implementations must be backed by a spatial index -- a linear scan over every country
 * polygon per query is explicitly out of budget (see docs/performance.md).
 */
public interface CountryProvider extends DataProvider {

    Optional<Country> countryAt(double latitude, double longitude);

    Optional<Region> regionAt(double latitude, double longitude);
}
