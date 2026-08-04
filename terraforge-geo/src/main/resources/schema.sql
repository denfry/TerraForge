-- ===========================================================================
--  TerraForge geographic database (SQLite)
--  Written offline by `terraforge prepare-geo`, read-only at runtime.
--  Geometry is stored as WKB. Bounding-box columns (min_lat/min_lon/max_lat/max_lon)
--  are catalogued into an in-memory R-tree at startup and the geometry itself is decoded
--  from its row only on first lookup, then cached (see LazySqliteWaterProvider) --
--  a whole-Earth import is too many features to decode eagerly without blocking startup.
-- ===========================================================================

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS schema_version (
    version     INTEGER NOT NULL PRIMARY KEY,
    applied_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS countries (
    id          INTEGER PRIMARY KEY,
    iso_code    TEXT    NOT NULL UNIQUE,       -- ISO 3166-1 alpha-2
    iso_code_3  TEXT,                          -- ISO 3166-1 alpha-3
    name        TEXT    NOT NULL,
    min_lat     REAL    NOT NULL,
    min_lon     REAL    NOT NULL,
    max_lat     REAL    NOT NULL,
    max_lon     REAL    NOT NULL,
    geometry    BLOB    NOT NULL               -- WKB MultiPolygon, WGS84
);
CREATE INDEX IF NOT EXISTS idx_countries_name ON countries (name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_countries_bbox ON countries (min_lat, max_lat, min_lon, max_lon);

CREATE TABLE IF NOT EXISTS regions (
    id          INTEGER PRIMARY KEY,
    country_id  INTEGER NOT NULL REFERENCES countries (id) ON DELETE CASCADE,
    name        TEXT    NOT NULL,
    admin_level INTEGER NOT NULL DEFAULT 1,
    min_lat     REAL    NOT NULL,
    min_lon     REAL    NOT NULL,
    max_lat     REAL    NOT NULL,
    max_lon     REAL    NOT NULL,
    geometry    BLOB    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_regions_country ON regions (country_id);
CREATE INDEX IF NOT EXISTS idx_regions_name ON regions (name COLLATE NOCASE);

-- Populated places: a name and a coordinate. Never built into the world.
CREATE TABLE IF NOT EXISTS cities (
    id          INTEGER PRIMARY KEY,
    name        TEXT    NOT NULL,
    ascii_name  TEXT,
    latitude    REAL    NOT NULL,
    longitude   REAL    NOT NULL,
    population  INTEGER NOT NULL DEFAULT 0,
    country_id  INTEGER REFERENCES countries (id) ON DELETE SET NULL,
    region_id   INTEGER REFERENCES regions (id) ON DELETE SET NULL,
    capital     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_cities_name ON cities (name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_cities_position ON cities (latitude, longitude);
CREATE INDEX IF NOT EXISTS idx_cities_country ON cities (country_id);

-- Alternate and localised names, used by command tab completion.
CREATE TABLE IF NOT EXISTS geographic_names (
    id          INTEGER PRIMARY KEY,
    entity_type TEXT    NOT NULL CHECK (entity_type IN ('country', 'region', 'city')),
    entity_id   INTEGER NOT NULL,
    name        TEXT    NOT NULL,
    language    TEXT
);
CREATE INDEX IF NOT EXISTS idx_names_lookup ON geographic_names (name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_names_entity ON geographic_names (entity_type, entity_id);

-- Natural water polygons only: oceans, lakes, natural watercourses.
-- Man-made features are rejected during preparation, not filtered at runtime.
CREATE TABLE IF NOT EXISTS water_bodies (
    id          INTEGER PRIMARY KEY,
    name        TEXT,
    water_type  TEXT    NOT NULL CHECK (water_type IN ('OCEAN', 'LAKE', 'RIVER')),
    min_lat     REAL    NOT NULL,
    min_lon     REAL    NOT NULL,
    max_lat     REAL    NOT NULL,
    max_lon     REAL    NOT NULL,
    river_bed_depth_m REAL NOT NULL DEFAULT 0,
    geometry    BLOB    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_water_bbox ON water_bodies (min_lat, max_lat, min_lon, max_lon);
CREATE INDEX IF NOT EXISTS idx_water_type ON water_bodies (water_type);

-- WOKAM identifies soluble-rock areas where caves can form. It is not 3D cave geometry.
CREATE TABLE IF NOT EXISTS karst_areas (
    id INTEGER PRIMARY KEY, min_lat REAL NOT NULL, min_lon REAL NOT NULL,
    max_lat REAL NOT NULL, max_lon REAL NOT NULL, geometry BLOB NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_karst_bbox ON karst_areas (min_lat, max_lat, min_lon, max_lon);

-- OSM natural=cave_entrance only. These are real entrances, not surveyed passage geometry.
CREATE TABLE IF NOT EXISTS cave_entrances (
    id INTEGER PRIMARY KEY, latitude REAL NOT NULL, longitude REAL NOT NULL, name TEXT
);
CREATE INDEX IF NOT EXISTS idx_cave_entrance_position ON cave_entrances (latitude, longitude);

-- Inventory of prepared raster tiles, so the plugin can report coverage gaps
-- at startup instead of discovering them mid-generation.
CREATE TABLE IF NOT EXISTS data_tiles (
    id          INTEGER PRIMARY KEY,
    dataset     TEXT    NOT NULL,              -- 'dem', 'landcover', 'water'
    lat_degree  INTEGER NOT NULL,
    lon_degree  INTEGER NOT NULL,
    file_name   TEXT    NOT NULL,
    width       INTEGER NOT NULL,
    height      INTEGER NOT NULL,
    encoding    INTEGER NOT NULL,
    size_bytes  INTEGER NOT NULL,
    checksum    TEXT,
    prepared_at INTEGER NOT NULL,
    UNIQUE (dataset, lat_degree, lon_degree)
);

-- Provenance of every import: source, licence and attribution (see DATA_SOURCES.md).
CREATE TABLE IF NOT EXISTS cache_metadata (
    key         TEXT    PRIMARY KEY,
    value       TEXT    NOT NULL,
    updated_at  INTEGER NOT NULL
);

-- Geography of player-built Towny towns. Written by TerraForge-Towny, never by Towny itself.
CREATE TABLE IF NOT EXISTS town_geography (
    town_uuid   TEXT    PRIMARY KEY,
    town_name   TEXT    NOT NULL,
    latitude    REAL    NOT NULL,
    longitude   REAL    NOT NULL,
    elevation   REAL,
    country_id  INTEGER REFERENCES countries (id) ON DELETE SET NULL,
    region_id   INTEGER REFERENCES regions (id) ON DELETE SET NULL,
    updated_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_town_country ON town_geography (country_id);
