package dev.terraforge.core.api;

import java.util.Collection;
import java.util.List;

/**
 * Projects the union of country boundaries into Minecraft block coordinates.
 *
 * <p>This is the bridge a consumer plugin (e.g. NewTowny) uses to turn TerraForge's WGS84
 * boundary polygons into WorldGuard {@code PolygonRegion}s without knowing anything about
 * projections, WKB or the geographic database.
 */
public interface GeoBoundaryProjector {

    /**
     * Unions the boundaries of the given countries, projects the result to Minecraft X/Z block
     * coordinates and simplifies each polygon ring to the given budget.
     *
     * <p>Returns one ring per disjoint polygon, ordered by projected area descending -- the first
     * ring is the mainland, the rest are islands. Rings are closed loops without a repeated
     * closing point, ready for a {@code PolygonRegion}.
     *
     * @param isoCodes ISO 3166-1 alpha-2 codes of the countries to union; unknown codes are skipped
     * @param options  simplification and vertex budget
     * @return projected rings, empty when none of the codes resolve
     */
    List<ProjectedRing> projectCountryUnion(Collection<String> isoCodes, RegionOptions options);
}
