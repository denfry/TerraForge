package dev.terraforge.cli.geo;

import dev.terraforge.core.data.WaterProvider.WaterType;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.io.WKBWriter;

/** Offline reader for the two small binary parts of the official HydroRIVERS Shapefile release. */
public final class HydroRiversShapefileImporter {
    private static final GeometryFactory FACTORY = new GeometryFactory();

    private HydroRiversShapefileImporter() { }

    public record Result(int imported, int skipped) { }

    public static Result importFile(Path shape, Connection connection, Envelope clip, double blocksPerKm)
            throws IOException, SQLException {
        Path dbf = shape.resolveSibling(shape.getFileName().toString().replaceFirst("(?i)\\.shp$", ".dbf"));
        try (DbfRows attributes = new DbfRows(dbf); RandomAccessFile file = new RandomAccessFile(shape.toFile(), "r");
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO water_bodies (name, water_type, min_lat, min_lon, max_lat, max_lon, river_bed_depth_m, geometry)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            if (file.length() < 100) throw new IOException(shape + " is not a Shapefile");
            file.seek(100);
            int imported = 0, skipped = 0;
            while (file.getFilePointer() < file.length()) {
                try { file.readInt(); int words = file.readInt();
                    if (words < 2) throw new IOException(shape + " has invalid record length");
                    byte[] record = bytes(file, words * 2); if (record.length != words * 2) throw new EOFException();
                    Map<String, String> row = attributes.next();
                    if (row == null) throw new IOException(shape + " and its DBF have different record counts");
                    if (manMade(row)) { skipped++; continue; }
                    Geometry line = polyline(record);
                    if (!Clip.keeps(Clip.of(clip), line)) { skipped++; continue; }
                    double width = RiverWidth.metres(number(row, "DIS_AV_CMS"), blocksPerKm);
                    Geometry polygon = WaterGeoJsonImporter.riverPolygon(WaterGeoJsonImporter.splitAntimeridian(line), width);
                    if (clip != null && !clip.covers(polygon.getEnvelopeInternal())) polygon = polygon.intersection(FACTORY.toGeometry(clip));
                    if (polygon.isEmpty() || !polygon.isValid() || polygon.getArea() == 0) { skipped++; continue; }
                    Envelope bounds = polygon.getEnvelopeInternal();
                    insert.setString(1, blankToNull(row.get("NAME"))); insert.setString(2, WaterType.RIVER.name());
                    insert.setDouble(3, bounds.getMinY()); insert.setDouble(4, bounds.getMinX());
                    insert.setDouble(5, bounds.getMaxY()); insert.setDouble(6, bounds.getMaxX());
                    insert.setDouble(7, RiverWidth.bedDepthMetres(width)); insert.setBytes(8, new WKBWriter().write(polygon));
                    insert.executeUpdate(); imported++;
                } catch (EOFException exception) { throw new IOException(shape + " ends inside a record", exception); }
            }
            return new Result(imported, skipped);
        }
    }

    private static Geometry polyline(byte[] record) throws IOException {
        if (record.length < 44 || littleInt(record, 0) == 0) throw new IOException("HydroRIVERS record is not a polyline");
        int type = littleInt(record, 0); if (type != 3 && type != 13) throw new IOException("HydroRIVERS record has unsupported shape type " + type);
        int parts = littleInt(record, 36), points = littleInt(record, 40); int offsets = 44, coordinates = offsets + parts * 4;
        if (parts < 1 || points < 2 || coordinates + points * 16 > record.length) throw new IOException("HydroRIVERS polyline is malformed");
        LineString[] lines = new LineString[parts];
        for (int part = 0; part < parts; part++) {
            int first = littleInt(record, offsets + part * 4), end = part + 1 == parts ? points : littleInt(record, offsets + (part + 1) * 4);
            if (first < 0 || end - first < 2 || end > points) throw new IOException("HydroRIVERS part is malformed");
            Coordinate[] coords = new Coordinate[end - first];
            for (int i = first; i < end; i++) { double x = littleDouble(record, coordinates + i * 16), y = littleDouble(record, coordinates + i * 16 + 8);
                if (!Double.isFinite(x) || !Double.isFinite(y) || x < -180 || x > 180 || y < -90 || y > 90) throw new IOException("HydroRIVERS coordinates are not WGS84");
                coords[i - first] = new Coordinate(x, y); }
            lines[part] = FACTORY.createLineString(coords);
        }
        return parts == 1 ? lines[0] : FACTORY.createMultiLineString(lines);
    }
    private static int littleInt(byte[] bytes, int offset) { return java.nio.ByteBuffer.wrap(bytes, offset, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(); }
    private static double littleDouble(byte[] bytes, int offset) { return java.nio.ByteBuffer.wrap(bytes, offset, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getDouble(); }
    private static byte[] bytes(RandomAccessFile file, int length) throws IOException { byte[] result = new byte[length]; int offset = 0; while (offset < length) { int read = file.read(result, offset, length - offset); if (read < 0) break; offset += read; } return offset == length ? result : java.util.Arrays.copyOf(result, offset); }
    private static boolean manMade(Map<String, String> row) { return row.values().stream().map(v -> v.toLowerCase(Locale.ROOT)).anyMatch(v -> v.contains("canal") || v.contains("reservoir")); }
    private static double number(Map<String, String> row, String name) { try { return Double.parseDouble(row.getOrDefault(name, "0").trim()); } catch (NumberFormatException ignored) { return 0; } }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static final class DbfRows implements AutoCloseable {
        private final RandomAccessFile file; private final int recordLength; private final Field[] fields;
        DbfRows(Path path) throws IOException { file = new RandomAccessFile(path.toFile(), "r"); byte[] header = bytes(file, 32); if (header.length != 32) throw new IOException(path + " is not a DBF");
            int headerLength = unsignedShort(header, 8), length = unsignedShort(header, 10); if (headerLength < 33 || length < 2) throw new IOException(path + " has invalid DBF header");
            fields = new Field[(headerLength - 33) / 32]; for (int i=0;i<fields.length;i++) { byte[] descriptor=bytes(file, 32); fields[i]=new Field(new String(descriptor,0,11,StandardCharsets.ISO_8859_1).trim(), descriptor[16]&255); } file.readByte(); recordLength=length; }
        Map<String,String> next() throws IOException { byte[] record=bytes(file, recordLength); if (record.length==0) return null; if(record.length!=recordLength) throw new EOFException(); Map<String,String> values=new HashMap<>(); int offset=1; for(Field field:fields){values.put(field.name(),new String(record,offset,field.length(),StandardCharsets.ISO_8859_1).trim());offset+=field.length();} return values; }
        @Override public void close() throws IOException { file.close(); }
        private static int unsignedShort(byte[] bytes,int offset){return (bytes[offset]&255)|((bytes[offset+1]&255)<<8);} private record Field(String name,int length) { }
    }
}
