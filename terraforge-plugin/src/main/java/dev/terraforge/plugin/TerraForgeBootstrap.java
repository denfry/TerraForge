package dev.terraforge.plugin;

import dev.terraforge.plugin.world.BootstrapDatapackService;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

/** Discovers the staged height pack before Paper creates the primary world. */
public final class TerraForgeBootstrap implements PluginBootstrap {
    @Override public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY,
                event -> { try { new BootstrapDatapackService().discover(context.getDataDirectory(), event.registrar()); }
                    catch (java.io.IOException exception) { throw new IllegalStateException("TerraForge managed datapack discovery failed", exception); } });
    }
}
