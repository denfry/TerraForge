package dev.terraforge.cli.geo;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKBWriter;

/**
 * Offline reader for the polygon layers of the WOKAM Shapefile release.
 *
 * <p>A sibling of {@link HydroRiversShapefileImporter}, which reads the polyline geometry the same
 * release format stores differently. Only the {@code .shp} is read: WOKAM's attributes classify the
 * rock (carbonate, evaporite) but every class in the map is soluble, so none of them changes whether
 * a polygon is imported, and reading the {@code .dbf} would only add a way to fail.
 *
 * <p><strong>These are not caves.</strong> WOKAM maps where caves can form. No open global dataset
 * of surveyed cave geometry exists, so what the generator puts inside these polygons is generated.
 */
public final class KarstShapefileImporter {

    /** Shapefile shape types this reads; everything else in a mixed archive is counted and skipped. */
    private static final int POLYGON = 5;
    private static final int POLYGON_Z = 15;
    private static final int POLYGON_M = 25;

    private static final int HEADER_BYTES = 100;

    private static final GeometryFactory FACTORY = new GeometryFactory();

    private KarstShapefileImporter() {
    }

    /** @param skipped records that were not polygons, or fell outside the clip */
    public record Result(int imported, int skipped) {
    }

    public static Result importFile(Path shape, Connection connection, Envelope clip)
            throws IOException, SQLException {
        Clip selected = Clip.of(clip);
        try (RandomAccessFile file = new RandomAccessFile(shape.toFile(), "r");
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO karst_areas (min_lat, min_lon, max_lat, max_lon, geometry)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            if (file.length() < HEADER_BYTES) {
                throw new IOException(shape + " is not a Shapefile");
            }
            file.seek(HEADER_BYTES);
            int imported = 0;
            int skipped = 0;
            while (file.getFilePointer() < file.length()) {
                byte[] record = nextRecord(file, shape);
                Geometry geometry = polygon(record);
                if (geometry == null || !Clip.keeps(selected, geometry)) {
                    skipped++;
                    continue;
                }
                if (clip != null && !clip.covers(geometry.getEnvelopeInternal())) {
                    geometry = geometry.intersection(FACTORY.toGeometry(clip));
                }
                // A karst polygon clipped to a corner of the box can degenerate to a line; the
                // dimension check is what keeps those out of a table of areas.
                if (geometry.isEmpty() || !geometry.isValid() || geometry.getDimension() != 2) {
                    skipped++;
                    continue;
                }
                Envelope bounds = geometry.getEnvelopeInternal();
                insert.setDouble(1, bounds.getMinY());
                insert.setDouble(2, bounds.getMinX());
                insert.setDouble(3, bounds.getMaxY());
                insert.setDouble(4, bounds.getMaxX());
                insert.setBytes(5, new WKBWriter().write(geometry));
                insert.executeUpdate();
                imported++;
            }
            return new Result(imported, skipped);
        }
    }

    private static byte[] nextRecord(RandomAccessFile file, Path shape) throws IOException {
        try {
            file.readInt();
            int words = file.readInt();
            if (words < 2) {
                throw new IOException(shape + " has invalid record length");
            }
            byte[] record = bytes(file, words * 2);
            if (record.length != words * 2) {
                throw new EOFException();
            }
            return record;
        } catch (EOFException exception) {
            throw new IOException(shape + " ends inside a record", exception);
        }
    }

    /**
     * One record as a polygon, or {@code null} when it holds anything else.
     *
     * <p>Returning {@code null} rather than throwing is deliberate: the archive is extracted whole,
     * so a points or lines layer sitting beside the karst layer must be skipped quietly instead of
     * failing an import that is otherwise correct.
     */
    private static Geometry polygon(byte[] record) throws IOException {
        if (record.length < 44) {
            return null;
        }
        int type = littleInt(record, 0);
        if (type != POLYGON && type != POLYGON_Z && type != POLYGON_M) {
            return null;
        }
        int parts = littleInt(record, 36);
        int points = littleInt(record, 40);
        int offsets = 44;
        int coordinates = offsets + parts * 4;
        if (parts < 1 || points < 4 || coordinates + points * 16 > record.length) {
            throw new IOException("WOKAM polygon is malformed");
        }

        // Shapefile stores outer rings clockwise and holes counter-clockwise, with each hole
        // following the ring it belongs to. That ordering is the only thing tying them together --
        // the format records no parent -- so the rings are walked in order and each hole is
        // attached to the shell that most recently opened.
        List<LinearRing> shells = new ArrayList<>();
        List<List<LinearRing>> holes = new ArrayList<>();
        for (int part = 0; part < parts; part++) {
            int first = littleInt(record, offsets + part * 4);
            int end = part + 1 == parts ? points : littleInt(record, offsets + (part + 1) * 4);
            if (first < 0 || end - first < 4 || end > points) {
                throw new IOException("WOKAM ring is malformed");
            }
            Coordinate[] ring = ring(record, coordinates, first, end);
            if (Orientation.isCCW(ring)) {
                if (!shells.isEmpty()) {
                    holes.get(holes.size() - 1).add(FACTORY.createLinearRing(ring));
                }
                // A hole before any shell is malformed data, not a shape; dropping it is the only
                // reading of it that produces a valid geometry.
            } else {
                shells.add(FACTORY.createLinearRing(ring));
                holes.add(new ArrayList<>());
            }
        }
        if (shells.isEmpty()) {
            return null;
        }
        Polygon[] polygons = new Polygon[shells.size()];
        for (int i = 0; i < shells.size(); i++) {
            List<LinearRing> inner = holes.get(i);
            polygons[i] = FACTORY.createPolygon(shells.get(i), inner.toArray(new LinearRing[0]));
        }
        return polygons.length == 1 ? polygons[0] : FACTORY.createMultiPolygon(polygons);
    }

    private static Coordinate[] ring(byte[] record, int base, int first, int end) throws IOException {
        Coordinate[] coordinates = new Coordinate[end - first];
        for (int i = first; i < end; i++) {
            double x = littleDouble(record, base + i * 16);
            double y = littleDouble(record, base + i * 16 + 8);
            if (!Double.isFinite(x) || !Double.isFinite(y)
                    || x < -180 || x > 180 || y < -90 || y > 90) {
                throw new IOException("WOKAM coordinates are not WGS84");
            }
            coordinates[i - first] = new Coordinate(x, y);
        }
        // JTS requires a closed ring and the format does not guarantee one byte-for-byte.
        if (!coordinates[0].equals2D(coordinates[coordinates.length - 1])) {
            Coordinate[] closed = java.util.Arrays.copyOf(coordinates, coordinates.length + 1);
            closed[coordinates.length] = new Coordinate(coordinates[0]);
            return closed;
        }
        return coordinates;
    }

    private static int littleInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static double littleDouble(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
    }

    private static byte[] bytes(RandomAccessFile file, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = file.read(result, offset, length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return offset == length ? result : java.util.Arrays.copyOf(result, offset);
    }
}
