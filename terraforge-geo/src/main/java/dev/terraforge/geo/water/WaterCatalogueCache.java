package dev.terraforge.geo.water;

import dev.terraforge.core.data.WaterProvider.WaterType;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;
import org.locationtech.jts.geom.Envelope;

/**
 * The water catalogue of one prepared database, saved beside the plugin so a restart need not rescan
 * the database for it.
 *
 * <p>The catalogue is small -- a bounding box and four numbers per feature -- but reading it is not:
 * {@code discharge_cms}, {@code bed_depth_m} and {@code surface_elevation_m} were added to
 * {@code water_bodies} after {@code geometry}, so SQLite reaches them only by walking every row's
 * geometry overflow pages. Against a whole-Earth database that is the whole file, and it held the
 * server thread for fifteen seconds on every start. The database never changes between restarts, so
 * the answer is computed once and kept.
 *
 * <p>The saved catalogue is valid only for the exact database file and river threshold it was built
 * from: the key is the database's absolute path, size and modification time, plus the threshold's
 * bits. Anything that does not match, or does not read back cleanly to the last byte and checksum,
 * is ignored and the database is scanned as before -- the file can make a start faster, never make
 * it wrong. The database itself is never written.
 */
final class WaterCatalogueCache {

    private static final System.Logger LOG = System.getLogger("TerraForge-Water");

    static final String FILE_NAME = "water-catalogue.bin";

    /** "TFWC". */
    private static final int MAGIC = 0x54465743;

    /** Bump whenever the entry layout or {@link WaterType}'s constants change. */
    private static final int FORMAT_VERSION = 1;

    /** id, type, four envelope bounds, surface, bed depth, discharge. */
    private static final int ENTRY_BYTES = Long.BYTES + Byte.BYTES + 7 * Double.BYTES;

    /** The CRC-32 trailer, stored as a long. */
    private static final int TRAILER_BYTES = Long.BYTES;

    private static final WaterType[] TYPES = WaterType.values();

    private final Path file;

    WaterCatalogueCache(Path cacheDirectory) {
        this.file = cacheDirectory.resolve(FILE_NAME);
    }

    Path file() {
        return file;
    }

    /** What a saved catalogue must have been built from to be reused. */
    record Key(String database, long sizeBytes, long lastModifiedMillis, long thresholdBits) {

        static Key of(Path database, double minVisibleRiverDischargeCms) throws IOException {
            Path absolute = database.toAbsolutePath().normalize();
            return new Key(absolute.toString(), Files.size(absolute),
                    Files.getLastModifiedTime(absolute).toMillis(),
                    Double.doubleToLongBits(minVisibleRiverDischargeCms));
        }
    }

    /**
     * The saved catalogue for {@code key}, or empty when there is none, it was built from something
     * else, or it does not read back intact. Never throws: a bad cache is a slower start, not a
     * failed one.
     */
    Optional<List<LazySqliteWaterProvider.CatalogEntry>> load(Key key) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            long length = Files.size(file);
            CRC32 crc = new CRC32();
            try (InputStream raw = Files.newInputStream(file);
                 BufferedInputStream buffered = new BufferedInputStream(raw, 1 << 16);
                 CheckedInputStream checked = new CheckedInputStream(buffered, crc);
                 DataInputStream in = new DataInputStream(checked)) {
                if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) {
                    LOG.log(System.Logger.Level.INFO, "Water catalogue cache {0} is from another format; "
                            + "rebuilding it from the database.", file);
                    return Optional.empty();
                }
                String database = in.readUTF();
                long size = in.readLong();
                long modified = in.readLong();
                long threshold = in.readLong();
                if (!key.equals(new Key(database, size, modified, threshold))) {
                    LOG.log(System.Logger.Level.INFO, "Water catalogue cache {0} was built from a different "
                            + "database or river threshold; rebuilding it.", file);
                    return Optional.empty();
                }
                int count = in.readInt();
                long headerBytes = 4 + 4 + 2 + utfLength(database) + 8 + 8 + 8 + 4;
                // The length must account for every byte before a single entry is allocated, so a
                // truncated or garbage count cannot ask for an unbounded list.
                if (count < 0 || length != headerBytes + (long) count * ENTRY_BYTES + TRAILER_BYTES) {
                    return corrupt("its length does not match its entry count");
                }
                List<LazySqliteWaterProvider.CatalogEntry> entries = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    long id = in.readLong();
                    int type = in.readByte();
                    if (type < 0 || type >= TYPES.length || TYPES[type] == WaterType.NONE) {
                        return corrupt("entry " + index + " has an invalid water type");
                    }
                    double minLon = in.readDouble();
                    double maxLon = in.readDouble();
                    double minLat = in.readDouble();
                    double maxLat = in.readDouble();
                    double surface = in.readDouble();
                    double bedDepth = in.readDouble();
                    double discharge = in.readDouble();
                    if (!(minLon <= maxLon) || !(minLat <= maxLat)) {
                        return corrupt("entry " + index + " has an inverted bounding box");
                    }
                    entries.add(new LazySqliteWaterProvider.CatalogEntry(id, TYPES[type],
                            new Envelope(minLon, maxLon, minLat, maxLat), surface, bedDepth, discharge));
                }
                long expected = crc.getValue();
                // The trailer is outside the checksum, so read it past the checked stream -- which
                // buffers nothing, so the buffered stream is exactly where the entries ended.
                long stored = new DataInputStream(buffered).readLong();
                if (stored != expected) {
                    return corrupt("its checksum does not match");
                }
                return Optional.of(entries);
            }
        } catch (EOFException exception) {
            return corrupt("it ends early");
        } catch (IOException | RuntimeException exception) {
            return corrupt(exception.toString());
        }
    }

    /**
     * Saves {@code entries} for {@code key}, atomically: a start that dies mid-write leaves the
     * previous file or none, never half of one. A failure is logged and otherwise ignored.
     */
    void save(Key key, List<LazySqliteWaterProvider.CatalogEntry> entries) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), FILE_NAME, ".tmp");
            CRC32 crc = new CRC32();
            try (OutputStream raw = Files.newOutputStream(temporary);
                 BufferedOutputStream buffered = new BufferedOutputStream(raw, 1 << 16);
                 CheckedOutputStream checked = new CheckedOutputStream(buffered, crc);
                 DataOutputStream out = new DataOutputStream(checked)) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeUTF(key.database());
                out.writeLong(key.sizeBytes());
                out.writeLong(key.lastModifiedMillis());
                out.writeLong(key.thresholdBits());
                out.writeInt(entries.size());
                for (LazySqliteWaterProvider.CatalogEntry entry : entries) {
                    Envelope box = entry.envelope();
                    out.writeLong(entry.id());
                    out.writeByte(entry.type().ordinal());
                    out.writeDouble(box.getMinX());
                    out.writeDouble(box.getMaxX());
                    out.writeDouble(box.getMinY());
                    out.writeDouble(box.getMaxY());
                    out.writeDouble(entry.surfaceElevationMetres());
                    out.writeDouble(entry.bedDepthMetres());
                    out.writeDouble(entry.dischargeCubicMetresPerSecond());
                }
                out.flush();
                // Written past the checksum stream so the trailer does not checksum itself.
                new DataOutputStream(buffered).writeLong(crc.getValue());
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException | RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "Cannot save the water catalogue cache " + file + ": "
                    + exception.getMessage() + "; the next start will scan the database again.");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best effort: a stray temp file in the cache directory is harmless.
                }
            }
        }
    }

    private Optional<List<LazySqliteWaterProvider.CatalogEntry>> corrupt(String why) {
        LOG.log(System.Logger.Level.WARNING, "Water catalogue cache " + file + " is unusable (" + why
                + "); rebuilding it from the database.");
        return Optional.empty();
    }

    /** Bytes {@link DataOutputStream#writeUTF} writes for {@code value}, excluding its length prefix. */
    private static int utfLength(String value) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c >= 0x0001 && c <= 0x007F) {
                bytes += 1;
            } else if (c > 0x07FF) {
                bytes += 3;
            } else {
                bytes += 2;
            }
        }
        return bytes;
    }
}
