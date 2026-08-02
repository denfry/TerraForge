package dev.terraforge.cli.geo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.terraforge.geo.database.GeoDatabaseSchema;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBReader;

/**
 * The WOKAM polygon reader against the binary layout the BGR release actually ships.
 *
 * <p>The archive is extracted whole, so these cover both halves of that decision: the karst polygons
 * must survive the round trip, and a layer of something else sitting beside them must not fail the
 * import.
 */
class KarstShapefileImporterTest {

    /** Shapefile stores outer rings clockwise. */
    private static final double[][] SHELL = {{0, 0}, {0, 10}, {10, 10}, {10, 0}, {0, 0}};
    /** ...and holes counter-clockwise, following the ring they belong to. */
    private static final double[][] HOLE = {{2, 2}, {4, 2}, {4, 4}, {2, 4}, {2, 2}};

    @TempDir Path temporaryDirectory;

    private Connection connection;

    @BeforeEach
    void openDatabase() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite:"
                + temporaryDirectory.resolve("terraforge.db").toAbsolutePath());
        GeoDatabaseSchema.install(connection);
    }

    @AfterEach
    void closeDatabase() throws Exception {
        connection.close();
    }

    @Test
    void readsAPolygonAndKeepsItsHole() throws Exception {
        Path file = shapefile(polygon(SHELL, HOLE));

        var result = KarstShapefileImporter.importFile(file, connection, null);

        assertThat(result.imported()).isEqualTo(1);
        Geometry karst = onlyGeometry();
        // 10x10 less the 2x2 hole: the hole is the whole point, and a reader that mistook it for a
        // second shell would report 104.
        assertThat(karst.getArea()).isEqualTo(96.0);
        assertThat(karst.isValid()).isTrue();
    }

    @Test
    void readsSeveralShellsAsOneMultiPolygon() throws Exception {
        double[][] second = {{20, 20}, {20, 30}, {30, 30}, {30, 20}, {20, 20}};
        Path file = shapefile(polygon(SHELL, second));

        var result = KarstShapefileImporter.importFile(file, connection, null);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(onlyGeometry().getArea()).isEqualTo(200.0);
    }

    /**
     * The reason the reader returns {@code null} instead of throwing. WOKAM ships more than one
     * layer and every {@code .shp} in the archive is extracted, so the springs and caves layers
     * reach this importer too.
     */
    @Test
    void skipsNonPolygonLayersInsteadOfFailing() throws Exception {
        Path file = shapefile(point(5, 5), polygon(SHELL), point(6, 6));

        var result = KarstShapefileImporter.importFile(file, connection, null);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
    }

    @Test
    void clipsToTheRequestedBox() throws Exception {
        Path file = shapefile(polygon(SHELL));

        var result = KarstShapefileImporter.importFile(file, connection,
                new Envelope(0.0, 5.0, 0.0, 10.0));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(onlyGeometry().getArea()).isEqualTo(50.0);
    }

    @Test
    void dropsAPolygonOutsideTheBox() throws Exception {
        Path file = shapefile(polygon(SHELL));

        var result = KarstShapefileImporter.importFile(file, connection,
                new Envelope(100.0, 110.0, 40.0, 50.0));

        assertThat(result.imported()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
    }

    private Geometry onlyGeometry() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT geometry FROM karst_areas")) {
            assertThat(rows.next()).isTrue();
            return new WKBReader().read(rows.getBytes(1));
        }
    }

    /** A {@code .shp} carrying {@code records}; only the header's length is ever read. */
    private Path shapefile(byte[]... records) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[100]);
        int number = 1;
        for (byte[] record : records) {
            bytes.write(bigEndian(number++));
            bytes.write(bigEndian(record.length / 2));
            bytes.write(record);
        }
        Path file = temporaryDirectory.resolve("wokam_karst.shp");
        Files.write(file, bytes.toByteArray());
        return file;
    }

    private static byte[] polygon(double[][]... rings) {
        int points = 0;
        for (double[][] ring : rings) {
            points += ring.length;
        }
        ByteBuffer content = ByteBuffer.allocate(44 + rings.length * 4 + points * 16)
                .order(ByteOrder.LITTLE_ENDIAN);
        content.putInt(5);
        content.putDouble(0).putDouble(0).putDouble(0).putDouble(0);
        content.putInt(rings.length);
        content.putInt(points);
        int offset = 0;
        for (double[][] ring : rings) {
            content.putInt(offset);
            offset += ring.length;
        }
        for (double[][] ring : rings) {
            for (double[] point : ring) {
                content.putDouble(point[0]).putDouble(point[1]);
            }
        }
        return content.array();
    }

    private static byte[] point(double x, double y) {
        return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1).putDouble(x).putDouble(y).array();
    }

    private static byte[] bigEndian(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }
}
