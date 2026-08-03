package dev.terraforge.plugin.world;

import dev.terraforge.core.config.VerticalProfile;
import dev.terraforge.core.world.TerraForgeDatapack;
import io.papermc.paper.datapack.DatapackRegistrar;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed bootstrap datapack discovery; no manifest means an inert installation.
 * <p>
 * The plugin data directory Paper hands to bootstrappers is always {@code <serverRoot>/plugins/TerraForge} —
 * this is Paper's documented plugin-data-directory convention, not something independently corroborated
 * elsewhere in this codebase — so the server root is derived from it. The height datapack itself is staged by
 * {@link ManagedWorldService#stage} under {@code <worldContainer>/earth/datapacks/terraforge-earth-height};
 * since Paper's default world container is the server root, that resolves here to
 * {@code <serverRoot>/earth/datapacks/terraforge-earth-height} — the same path {@link ManagedWorldService}
 * writes to for the common, un-customised {@code world-container} case. A server started with a customized
 * {@code -world-container} (or {@code level-name}) flag is not currently supported: {@link
 * ManagedWorldEnvironment#worldContainer()} is not available at bootstrap time, so this class cannot detect
 * or correct for that case and will (incorrectly) report the pack as missing.
 */
public final class BootstrapDatapackService {
    static final String DATAPACK_DIRECTORY_NAME = "terraforge-earth-height";

    /**
     * The id passed to {@link DatapackRegistrar#discoverPack}; Paper combines it with the plugin name
     * to form the datapack's registered name, {@code "TerraForge/" + DATAPACK_ID}. {@link
     * dev.terraforge.plugin.world.ManagedWorldStartupVerifier} looks the running pack up by that
     * combined name via {@code Server#getDatapackManager()}.
     */
    public static final String DATAPACK_ID = "earth-height";

    public void discover(Path pluginDataDirectory, DatapackRegistrar registrar) throws IOException {
        var manifest = new ManagedWorldManifestStore(pluginDataDirectory).load();
        if (manifest.isEmpty()) return;
        ManagedWorldManifest state = manifest.get();
        if (state.state() == ManagedWorldState.INVALID || state.state() == ManagedWorldState.ABSENT)
            throw new IOException("managed Earth manifest is not eligible for datapack discovery");

        Path serverRoot = pluginDataDirectory.toAbsolutePath().normalize().getParent().getParent();
        Path pack = serverRoot.resolve("earth/datapacks/" + DATAPACK_DIRECTORY_NAME).normalize();
        if (!pack.startsWith(serverRoot) || !Files.isRegularFile(pack.resolve("pack.mcmeta")))
            throw new IOException("managed Earth datapack is missing or outside the server root "
                    + "(note: a customized world-container/level-name is not currently supported)");

        verifyFingerprint(pack, state);

        registrar.discoverPack(pack, DATAPACK_ID, configurer -> configurer.autoEnableOnServerStart(true));
    }

    /**
     * Recomputes the on-disk pack's fingerprint the same way {@link TerraForgeDatapack#render} does — SHA-256
     * over sorted relative paths and bytes — and fails closed on any mismatch against the manifest's recorded
     * {@code datapackFingerprint}. The manifest's {@code minY}/{@code maxY} are enough to know exactly which
     * relative paths and byte content a legitimate pack must contain, since those are the only inputs that
     * affect {@link TerraForgeDatapack#render}'s output.
     * <p>
     * The on-disk pack directory is walked recursively so that files added beyond the expected set (e.g. a
     * malicious {@code .mcfunction} smuggled into {@code data/minecraft/tags/function/}) are also detected:
     * the set of relative paths actually present must be exactly the set the manifest expects, no more and
     * no fewer, before any byte content is compared.
     */
    private static void verifyFingerprint(Path pack, ManagedWorldManifest state) throws IOException {
        VerticalProfile profile = new VerticalProfile(state.minY() + 1, state.minY(), state.maxY(), 1.0);
        TerraForgeDatapack.RenderedPack expected = TerraForgeDatapack.render(profile);

        Set<String> onDisk = new LinkedHashSet<>();
        Files.walkFileTree(pack, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                onDisk.add(pack.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
        if (!onDisk.equals(expected.files().keySet()))
            throw new IOException("managed Earth datapack contains unexpected or missing files");

        Map<String, byte[]> actual = new LinkedHashMap<>();
        for (String relativePath : expected.files().keySet()) {
            Path file = pack.resolve(relativePath);
            if (!Files.isRegularFile(file)) throw new IOException("managed Earth datapack is missing " + relativePath);
            actual.put(relativePath, Files.readAllBytes(file));
        }
        String actualFingerprint = TerraForgeDatapack.fingerprint(actual);
        if (!actualFingerprint.equals(state.datapackFingerprint()))
            throw new IOException("managed Earth datapack fingerprint does not match the manifest");
    }
}
