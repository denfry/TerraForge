package dev.terraforge.plugin.world;

import dev.terraforge.core.config.VerticalProfile;
import dev.terraforge.core.world.TerraForgeDatapack;
import io.papermc.paper.datapack.DatapackRegistrar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fail-closed bootstrap datapack discovery; no manifest means an inert installation.
 * <p>
 * The plugin data directory Paper hands to bootstrappers is always {@code <serverRoot>/plugins/TerraForge}
 * (see {@link ManagedWorldStager}'s identical assumption for the backup root), so the server root is derived
 * from it. The height datapack itself is staged by {@link ManagedWorldService#stage} under
 * {@code <worldContainer>/earth/datapacks/terraforge-earth-height}; since Paper's default world container is
 * the server root, that resolves here to {@code <serverRoot>/earth/datapacks/terraforge-earth-height} — the
 * same path {@link ManagedWorldService} writes to for the common, un-customised {@code world-container} case.
 */
public final class BootstrapDatapackService {
    static final String DATAPACK_DIRECTORY_NAME = "terraforge-earth-height";
    static final String DATAPACK_ID = "earth-height";

    public void discover(Path pluginDataDirectory, DatapackRegistrar registrar) throws IOException {
        var manifest = new ManagedWorldManifestStore(pluginDataDirectory).load();
        if (manifest.isEmpty()) return;
        ManagedWorldManifest state = manifest.get();
        if (state.state() == ManagedWorldState.INVALID || state.state() == ManagedWorldState.ABSENT)
            throw new IOException("managed Earth manifest is not eligible for datapack discovery");

        Path serverRoot = pluginDataDirectory.toAbsolutePath().normalize().getParent().getParent();
        Path pack = serverRoot.resolve("earth/datapacks/" + DATAPACK_DIRECTORY_NAME).normalize();
        if (!pack.startsWith(serverRoot) || !Files.isRegularFile(pack.resolve("pack.mcmeta")))
            throw new IOException("managed Earth datapack is missing or outside the server root");

        verifyFingerprint(pack, state);

        registrar.discoverPack(pack, DATAPACK_ID, configurer -> configurer.autoEnableOnServerStart(true));
    }

    /**
     * Recomputes the on-disk pack's fingerprint the same way {@link TerraForgeDatapack#render} does — SHA-256
     * over sorted relative paths and bytes — and fails closed on any mismatch against the manifest's recorded
     * {@code datapackFingerprint}. The manifest's {@code minY}/{@code maxY} are enough to know exactly which
     * relative paths and byte content a legitimate pack must contain, since those are the only inputs that
     * affect {@link TerraForgeDatapack#render}'s output.
     */
    private static void verifyFingerprint(Path pack, ManagedWorldManifest state) throws IOException {
        VerticalProfile profile = new VerticalProfile(state.minY() + 1, state.minY(), state.maxY(), 1.0);
        TerraForgeDatapack.RenderedPack expected = TerraForgeDatapack.render(profile);
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
