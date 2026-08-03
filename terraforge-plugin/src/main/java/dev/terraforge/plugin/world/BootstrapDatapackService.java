package dev.terraforge.plugin.world;

import io.papermc.paper.datapack.DatapackRegistrar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fail-closed bootstrap datapack discovery; no manifest means an inert installation. */
public final class BootstrapDatapackService {
    public void discover(Path pluginDataDirectory, DatapackRegistrar registrar) throws IOException {
        var manifest = new ManagedWorldManifestStore(pluginDataDirectory).load();
        if (manifest.isEmpty()) return;
        ManagedWorldManifest state = manifest.get();
        if (state.state() == ManagedWorldState.INVALID || state.state() == ManagedWorldState.ABSENT)
            throw new IOException("managed Earth manifest is not eligible for datapack discovery");
        Path pack = pluginDataDirectory.resolve("datapack/terraforge-world-height").normalize();
        if (!pack.startsWith(pluginDataDirectory.toAbsolutePath().normalize()) || !Files.isRegularFile(pack.resolve("pack.mcmeta")))
            throw new IOException("managed Earth datapack is missing or outside the plugin directory");
        registrar.discoverPack(pack, "earth-height", configurer -> configurer.autoEnableOnServerStart(true));
    }
}
