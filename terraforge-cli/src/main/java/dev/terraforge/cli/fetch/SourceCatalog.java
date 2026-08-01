package dev.terraforge.cli.fetch;

import dev.terraforge.core.coord.GeoBounds;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where every source dataset comes from, and which files a bounding box needs.
 *
 * <p>Only mirrors that serve anonymous HTTP are listed. Copernicus DEM and ESA WorldCover are read
 * from their AWS Open Data buckets, Natural Earth from a pinned repository tag and GeoNames from its
 * own export. Nothing here needs an account, a token or a click-through, which is what makes
 * {@code terraforge setup} possible at all.
 *
 * <p>Natural Earth is pinned to a release tag rather than {@code master}: the same command must
 * prepare the same world next year. The rasters are versioned in their own paths ({@code v200},
 * {@code 2021}), so they are already fixed.
 *
 * <p>The layout produced here is exactly the one {@code prepare-region} reads -- one directory per
 * dataset -- so a fetched tree and a hand-assembled tree are indistinguishable.
 */
public final class SourceCatalog {

    /** Natural Earth release the vector layers are taken from. */
    public static final String NATURAL_EARTH_TAG = "v5.1.2";

    private static final String NATURAL_EARTH = "https://raw.githubusercontent.com/nvkelso/"
            + "natural-earth-vector/" + NATURAL_EARTH_TAG + "/geojson/";

    private static final String GEONAMES = "https://download.geonames.org/export/dump/";

    /** HydroRIVERS v1 global Shapefile, published anonymously by HydroSHEDS. */
    private static final URI HYDRORIVERS = URI.create(
            "https://data.hydrosheds.org/file/HydroRIVERS/HydroRIVERS_v10_shp.zip");

    private static final String WORLD_COVER =
            "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/";

    private static final String GEBCO_2024 = "https://dap.ceda.ac.uk/bodc/gebco/global/gebco_2024/"
            + "ice_surface_elevation/geotiff/";

    /** ESA WorldCover is published in three-degree tiles, between 60S and 84N. */
    private static final int WORLD_COVER_TILE_DEGREES = 3;
    private static final int WORLD_COVER_MIN_LAT = -60;
    private static final int WORLD_COVER_MAX_LAT = 84;

    private SourceCatalog() {
    }

    /**
     * One file to fetch.
     *
     * @param dataset  source sub-directory, matching what {@code prepare-region} scans
     * @param fileName name on disk, kept as the publisher named it so provenance survives
     */
    public record Download(String dataset, String fileName, URI uri) {

        /** Label used in progress output. */
        public String label() {
            return dataset + "/" + fileName;
        }
    }

    /** Copernicus DEM variants, both public on AWS Open Data. */
    public enum DemResolution {
        /** GLO-30: 30 m, about 40 MiB per degree tile. */
        GLO_30("copernicus-dem-30m", 10, "30 m"),
        /** GLO-90: 90 m, about a quarter of the download, ample at 1 block/km. */
        GLO_90("copernicus-dem-90m", 30, "90 m");

        private final String bucket;
        private final int cogCode;
        private final String description;

        DemResolution(String bucket, int cogCode, String description) {
            this.bucket = bucket;
            this.cogCode = cogCode;
            this.description = description;
        }

        public String description() {
            return description;
        }

        public static DemResolution parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                    .replace("glo-", "").replace("glo", "").replace("m", "");
            return switch (normalized) {
                case "30" -> GLO_30;
                case "90" -> GLO_90;
                default -> throw new IllegalArgumentException(
                        "Unknown DEM resolution '" + value + "'; expected 30 or 90");
            };
        }
    }

    /** One Copernicus tile per degree cell the box touches. Ocean-only cells simply 404. */
    public static List<Download> dem(GeoBounds bounds, DemResolution resolution) {
        List<Download> downloads = new ArrayList<>();
        for (int lat = floor(bounds.minLatitude()); lat < bounds.maxLatitude() && lat < 90; lat++) {
            for (int lon = floor(bounds.minLongitude()); lon < bounds.maxLongitude() && lon < 180; lon++) {
                String cell = String.format(Locale.ROOT, "%s_00_%s_00",
                        latitudeLabel(lat, 2), longitudeLabel(lon, 3));
                String name = "Copernicus_DSM_COG_" + resolution.cogCode + "_" + cell + "_DEM";
                downloads.add(new Download("dem", name + ".tif",
                        URI.create("https://" + resolution.bucket + ".s3.amazonaws.com/"
                                + name + "/" + name + ".tif")));
            }
        }
        return List.copyOf(downloads);
    }

    /**
     * GEBCO 2024 is published as eight anonymous 90 by 90 degree GeoTIFFs. They are deliberately
     * kept as source rasters; preparation slices just the requested one-degree cells at 15 arc-second
     * resolution instead of expanding the ocean to the land DEM grid.
     */
    public static List<Download> bathymetry(GeoBounds bounds) {
        List<Download> downloads = new ArrayList<>();
        for (int south = Math.floorDiv(floor(bounds.minLatitude()), 90) * 90;
             south < bounds.maxLatitude(); south += 90) {
            int north = south + 90;
            for (int west = Math.floorDiv(floor(bounds.minLongitude()), 90) * 90;
                 west < bounds.maxLongitude(); west += 90) {
                int east = west + 90;
                String name = String.format(Locale.ROOT, "gebco_2024_n%.1f_s%.1f_w%.1f_e%.1f.tif",
                        (double) north, (double) south, (double) west, (double) east);
                downloads.add(new Download("bathymetry", name, URI.create(GEBCO_2024 + name)));
            }
        }
        return List.copyOf(downloads);
    }

    /**
     * One WorldCover tile per three-degree cell the box touches.
     *
     * <p>A box outside 60S..84N yields nothing: the dataset genuinely stops there, and the runtime's
     * climate fallback covers what it does not reach.
     */
    public static List<Download> landcover(GeoBounds bounds) {
        List<Download> downloads = new ArrayList<>();
        for (int lat = floorToTile(bounds.minLatitude()); lat < bounds.maxLatitude();
             lat += WORLD_COVER_TILE_DEGREES) {
            if (lat < WORLD_COVER_MIN_LAT || lat >= WORLD_COVER_MAX_LAT) {
                continue;
            }
            for (int lon = floorToTile(bounds.minLongitude()); lon < bounds.maxLongitude();
                 lon += WORLD_COVER_TILE_DEGREES) {
                if (lon < -180 || lon >= 180) {
                    continue;
                }
                String name = "ESA_WorldCover_10m_2021_v200_"
                        + latitudeLabel(lat, 2) + longitudeLabel(lon, 3) + "_Map.tif";
                downloads.add(new Download("landcover", name, URI.create(WORLD_COVER + name)));
            }
        }
        return List.copyOf(downloads);
    }

    /** Country and first-level region polygons. Global files, clipped later by the importer. */
    public static List<Download> boundaries() {
        return List.of(
                naturalEarth("boundaries", "ne_10m_admin_0_countries.geojson"),
                naturalEarth("boundaries", "ne_10m_admin_1_states_provinces.geojson"));
    }

    /** Natural water polygons plus the HydroRIVERS line network (CC BY 4.0). */
    public static List<Download> water() {
        return List.of(naturalEarth("water", "ne_10m_lakes.geojson"),
                new Download("water", "HydroRIVERS_v10_shp.zip", HYDRORIVERS));
    }

    /**
     * GeoNames populated places, distributed as a zip holding one tab-separated file.
     *
     * @param dataset {@code cities500}, {@code cities1000}, {@code cities5000} or {@code cities15000}
     */
    public static Download cities(String dataset) {
        String name = dataset == null ? "" : dataset.trim().toLowerCase(Locale.ROOT);
        if (!name.matches("cities(500|1000|5000|15000)")) {
            throw new IllegalArgumentException("Unknown gazetteer '" + dataset
                    + "'; expected cities500, cities1000, cities5000 or cities15000");
        }
        return new Download("cities", name + ".zip", URI.create(GEONAMES + name + ".zip"));
    }

    private static Download naturalEarth(String dataset, String fileName) {
        return new Download(dataset, fileName, URI.create(NATURAL_EARTH + fileName));
    }

    // --- tile naming --------------------------------------------------------

    private static String latitudeLabel(int latitude, int digits) {
        return (latitude < 0 ? "S" : "N") + pad(Math.abs(latitude), digits);
    }

    private static String longitudeLabel(int longitude, int digits) {
        return (longitude < 0 ? "W" : "E") + pad(Math.abs(longitude), digits);
    }

    private static String pad(int value, int digits) {
        return String.format(Locale.ROOT, "%0" + digits + "d", value);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    /** Largest three-degree tile origin at or below {@code value}, correct for negatives too. */
    private static int floorToTile(double value) {
        return Math.floorDiv(floor(value), WORLD_COVER_TILE_DEGREES) * WORLD_COVER_TILE_DEGREES;
    }
}
