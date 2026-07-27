package dev.terraforge.towny;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * One town's geography, already detached from Towny and Bukkit.
 *
 * <p>This is the only thing that crosses from the server thread to the writer thread. Passing a
 * {@code Town} instead would mean reading Towny's mutable state off the main thread, which is
 * exactly the bug this record exists to prevent.
 *
 * @param townUuid  Towny's stable town id
 * @param townName  display name at the time of the snapshot
 * @param latitude  spawn latitude
 * @param longitude spawn longitude
 * @param elevation real elevation in metres, {@code NaN} when no DEM covers the point
 * @param countryId prepared country id, or {@code null} when the spawn is outside every boundary
 * @param regionId  prepared region id, or {@code null}
 */
public record TownGeography(UUID townUuid, String townName, double latitude, double longitude,
                            double elevation, Integer countryId, Integer regionId) {

    public TownGeography {
        Objects.requireNonNull(townUuid, "townUuid");
        Objects.requireNonNull(townName, "townName");
    }

    public OptionalInt country() {
        return countryId == null ? OptionalInt.empty() : OptionalInt.of(countryId);
    }

    public OptionalInt region() {
        return regionId == null ? OptionalInt.empty() : OptionalInt.of(regionId);
    }
}
