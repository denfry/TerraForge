package dev.terraforge.towny;

import com.palmergames.bukkit.towny.object.Town;
import dev.terraforge.core.coord.EarthLocation;
import dev.terraforge.core.geo.Country;
import dev.terraforge.core.geo.Region;
import java.util.Optional;

/**
 * Real-world geography for Towny towns.
 *
 * <p>Strictly one-directional: TerraForge reads Towny's data and annotates it with geography. It
 * never creates towns, claims plots or places blocks -- settlements are a player activity and Towny
 * remains their sole owner.
 *
 * <p>Available only when Towny is installed; the module is not loaded otherwise.
 */
public interface TownGeoService {

    /** Geographic location of a town's spawn/home block. */
    Optional<EarthLocation> getEarthLocation(Town town);

    Optional<Country> getCountry(Town town);

    Optional<Region> getRegion(Town town);

    /** Real-world distance in metres between two towns (WGS84 geodesic, not block distance). */
    double getDistance(Town a, Town b);

    /** Real-world distance in metres from a town to a geographic point. */
    double getDistanceToCoordinates(Town town, double latitude, double longitude);

    /** Recomputes and persists the cached geography of a town, e.g. after its spawn moved. */
    void refresh(Town town);
}
