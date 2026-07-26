package dev.terraforge.core.coord;

import java.util.Optional;

/**
 * A fully resolved point on Earth together with its Minecraft counterpart.
 *
 * <p>Produced by {@code EarthService}; the geographic attributes ({@code country}, {@code region},
 * {@code biome}) are resolved lazily by the geo layer and may be absent when no dataset covers the
 * point -- callers must handle that instead of assuming global coverage.
 *
 * @param position   geographic position (WGS84)
 * @param elevation  terrain elevation in metres above sea level (negative below)
 * @param country    ISO/display country name, if a boundary dataset resolved it
 * @param region     first-level administrative region, if resolved
 * @param biome      resolved climate biome key, if a landcover dataset resolved it
 * @param minecraftX Minecraft block X
 * @param minecraftY Minecraft block Y
 * @param minecraftZ Minecraft block Z
 */
public record EarthLocation(
        GeoPoint position,
        double elevation,
        String country,
        String region,
        String biome,
        int minecraftX,
        int minecraftY,
        int minecraftZ) {

    public double latitude() {
        return position.latitude();
    }

    public double longitude() {
        return position.longitude();
    }

    public Optional<String> countryName() {
        return Optional.ofNullable(country);
    }

    public Optional<String> regionName() {
        return Optional.ofNullable(region);
    }

    public Optional<String> biomeName() {
        return Optional.ofNullable(biome);
    }

    public static Builder builder(GeoPoint position) {
        return new Builder(position);
    }

    /** Mutable builder; the geo layer fills the optional attributes as they are resolved. */
    public static final class Builder {
        private final GeoPoint position;
        private double elevation;
        private String country;
        private String region;
        private String biome;
        private int minecraftX;
        private int minecraftY;
        private int minecraftZ;

        private Builder(GeoPoint position) {
            this.position = position;
        }

        public Builder elevation(double elevation) {
            this.elevation = elevation;
            return this;
        }

        public Builder country(String country) {
            this.country = country;
            return this;
        }

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Builder biome(String biome) {
            this.biome = biome;
            return this;
        }

        public Builder minecraft(int x, int y, int z) {
            this.minecraftX = x;
            this.minecraftY = y;
            this.minecraftZ = z;
            return this;
        }

        public EarthLocation build() {
            return new EarthLocation(position, elevation, country, region, biome, minecraftX, minecraftY, minecraftZ);
        }
    }
}
