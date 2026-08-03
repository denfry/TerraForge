package dev.terraforge.plugin;

import java.util.Locale;
import java.util.List;
import java.util.stream.Stream;
import dev.terraforge.core.coord.GeoPoint;
import dev.terraforge.plugin.world.ManagedWorldManifestStore;
import dev.terraforge.core.geodesy.Geodesy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** Read-only player-facing geographic commands. */
final class EarthCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("info", "whereami", "coords", "distance",
            "country", "cache", "city", "teleport", "pregenerate", "towny", "debug", "reload", "world");

    /** Tab completion is a hint, not a search: a gazetteer has far too many names to list. */
    private static final int COMPLETION_LIMIT = 40;

    private final TerraForgePlugin plugin;

    EarthCommand(TerraForgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) return help(sender);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> info(sender);
            case "cache" -> cache(sender, args);
            case "whereami" -> playerCommand(sender, "terraforge.command.location", this::whereAmI);
            case "coords" -> args.length == 3
                    ? playerCommand(sender, "terraforge.command.location", player -> coordinates(player, args[1], args[2]))
                    : help(sender);
            case "distance" -> args.length == 3
                    ? playerCommand(sender, "terraforge.command.location", player -> distance(player, args[1], args[2]))
                    : help(sender);
            case "country" -> args.length >= 2
                    ? playerCommand(sender, "terraforge.command.location", player -> country(player, String.join(" ", List.of(args).subList(1, args.length))))
                    : help(sender);
            case "city" -> args.length >= 2
                    ? playerCommand(sender, "terraforge.command.location", player -> city(player, String.join(" ", List.of(args).subList(1, args.length))))
                    : help(sender);
            case "teleport" -> args.length >= 3 && args[1].equalsIgnoreCase("city")
                    ? playerCommand(sender, "terraforge.command.teleport", player -> teleportCity(player, String.join(" ", List.of(args).subList(2, args.length))))
                    : args.length >= 3 && args[1].equalsIgnoreCase("country")
                    ? playerCommand(sender, "terraforge.command.teleport", player -> teleportCountry(player, String.join(" ", List.of(args).subList(2, args.length))))
                    : help(sender);
            case "pregenerate" -> pregenerate(sender, args);
            case "towny" -> towny(sender, args);
            case "debug" -> args.length >= 2 && args[1].equalsIgnoreCase("overlay")
                    ? playerCommand(sender, "terraforge.command.debug", this::debugOverlay)
                    : playerCommand(sender, "terraforge.command.debug", this::debug);
            case "reload" -> reload(sender);
            case "world" -> world(sender, args);
            default -> help(sender);
        };
    }

    private boolean world(CommandSender sender, String[] args) {
        if (!sender.hasPermission("terraforge.command.world")) return denied(sender);
        if (args.length != 2 || !args[1].equalsIgnoreCase("status")) return help(sender);
        try {
            new ManagedWorldManifestStore(plugin.getDataFolder().toPath()).load().ifPresentOrElse(manifest ->
                    sender.sendMessage(Component.text("Managed Earth: " + manifest.state(), NamedTextColor.AQUA)),
                    () -> sender.sendMessage(Component.text("Managed Earth has not been staged.", NamedTextColor.GRAY)));
        } catch (java.io.IOException exception) {
            sender.sendMessage(Component.text("Managed Earth manifest is invalid; run diagnostics as an operator.", NamedTextColor.RED));
        }
        return true;
    }

    private boolean info(CommandSender sender) {
        if (!sender.hasPermission("terraforge.command.info")) return denied(sender);
        var config = plugin.config();
        sender.sendMessage(Component.text("TerraForge: " + config.world().name() + ", "
                + plugin.transformer().projection().description() + ", " + config.scale().blocksPerKm() + " blocks/km",
                NamedTextColor.AQUA));
        return true;
    }

    private boolean cache(CommandSender sender, String[] args) {
        if (!sender.hasPermission("terraforge.command.cache")) return denied(sender);
        if (args.length == 2 && args[1].equalsIgnoreCase("clear")) {
            plugin.cacheManager().invalidateAll();
            sender.sendMessage(Component.text("TerraForge caches cleared.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length > 1) return help(sender);
        var statistics = plugin.cacheManager().statistics();
        if (statistics.isEmpty()) sender.sendMessage(Component.text("No TerraForge caches are registered yet.", NamedTextColor.GRAY));
        else statistics.forEach(stat -> sender.sendMessage(Component.text(stat.format(), NamedTextColor.AQUA)));
        return true;
    }

    private boolean pregenerate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("terraforge.command.pregenerate")) return denied(sender);
        if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
            plugin.pregenerationStatus().ifPresentOrElse(status -> sender.sendMessage(Component.text(
                    "Pregeneration: " + status, NamedTextColor.AQUA)), () -> sender.sendMessage(Component.text(
                    "No pregeneration job is running.", NamedTextColor.GRAY)));
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("cancel")) {
            if (!plugin.cancelPregeneration()) sender.sendMessage(Component.text("No pregeneration job is running.", NamedTextColor.GRAY));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Console use requires a player centre; use /earth pregenerate <radius> in-game.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 2) return help(sender);
        try {
            int radius = Integer.parseInt(args[1]);
            if (radius < 0 || radius > 32) throw new IllegalArgumentException();
            var chunk = player.getLocation().getChunk();
            if (!plugin.startPregeneration(player.getWorld(), sender, chunk.getX(), chunk.getZ(), radius)) {
                sender.sendMessage(Component.text("A TerraForge pregeneration job is already running.", NamedTextColor.YELLOW));
                return true;
            }
            int total = Math.multiplyExact(radius * 2 + 1, radius * 2 + 1);
            sender.sendMessage(Component.text("Pregeneration started for " + total + " chunks; one chunk is requested per tick.", NamedTextColor.GREEN));
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(Component.text("Radius must be an integer from 0 to 32 chunks.", NamedTextColor.RED));
        }
        return true;
    }

    private boolean debug(Player player) {
        var location = player.getLocation();
        var minecraft = plugin.transformer().toMinecraft(location.getX(), location.getZ());
        var samples = plugin.terrain().chunkSampler().sample(minecraft.chunkX(), minecraft.chunkZ());
        var sample = samples.at(Math.floorMod(minecraft.blockX(), 16), Math.floorMod(minecraft.blockZ(), 16));
        var geographic = plugin.transformer().toGeographic(location.getX(), location.getZ());
        player.sendMessage(Component.text(String.format(Locale.ROOT,
                "Debug: geo %.5f, %.5f | elevation %.1f m | surface Y %d | water %s | biome %s%s",
                geographic.latitude(), geographic.longitude(), sample.elevationMeters(), sample.surfaceY(),
                sample.waterType(), sample.biome(), sample.fromFallback() ? " | fallback DEM" : ""), NamedTextColor.AQUA));
        return true;
    }

    /**
     * Toggles the live overlay, which is off unless the operator opted in.
     *
     * <p>Two separate switches on purpose: {@code debug.enabled} turns the subsystem on at all, and
     * {@code debug.per-player} decides whether a player may switch on a repeating task for
     * themselves.
     */
    private boolean debugOverlay(Player player) {
        var debug = plugin.config().debug();
        if (debug == null || !debug.enabled()) {
            player.sendMessage(Component.text("The debug overlay is off: set debug.enabled to true in "
                    + "terraforge.yml.", NamedTextColor.YELLOW));
            return true;
        }
        if (!debug.perPlayer()) {
            player.sendMessage(Component.text("The debug overlay is off: set debug.per-player to true in "
                    + "terraforge.yml.", NamedTextColor.YELLOW));
            return true;
        }
        boolean enabled = plugin.debugOverlay().toggle(player);
        player.sendMessage(Component.text(enabled
                        ? "Debug overlay on; it follows you in the action bar. Run the command again to stop it."
                        : "Debug overlay off.",
                enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("terraforge.command.reload")) return denied(sender);
        try {
            plugin.validateConfigurationForReload();
            plugin.cacheManager().invalidateAll();
            String geography = plugin.reloadGeography()
                    .orElse("no prepared database, geography unchanged");
            sender.sendMessage(Component.text("Configuration is valid and caches were cleared.",
                    NamedTextColor.GREEN));
            sender.sendMessage(Component.text("Geography reloaded: " + geography + ".", NamedTextColor.GREEN));
            sender.sendMessage(Component.text("Terrain settings (scale, projection, origin, water, biomes) still "
                    + "need a restart: swapping the generator now would mix old and new geometry at the seam.",
                    NamedTextColor.YELLOW));
        } catch (java.io.IOException | IllegalArgumentException exception) {
            sender.sendMessage(Component.text("Configuration reload validation failed: " + exception.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    /**
     * Re-annotates towns that already existed before TerraForge was installed, or whose annotation
     * was lost. New towns and moved spawns are handled by the Towny listener; this is the backfill.
     */
    private boolean towny(CommandSender sender, String[] args) {
        if (!sender.hasPermission("terraforge.command.towny")) return denied(sender);
        var service = plugin.townGeography();
        if (service.isEmpty()) {
            sender.sendMessage(Component.text(
                    "Towny geography is not active: Towny must be installed, enabled in terraforge.yml, "
                            + "and boundaries must be prepared.", NamedTextColor.YELLOW));
            return true;
        }
        var towny = service.get();
        if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(Component.text("Town geography: " + towny.pendingWrites()
                    + " write(s) pending.", NamedTextColor.AQUA));
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("refresh")) {
            int queued = towny.refreshAll();
            sender.sendMessage(Component.text("Queued " + queued + " town(s) for re-annotation.",
                    NamedTextColor.GREEN));
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("refresh")) {
            String name = String.join(" ", List.of(args).subList(2, args.length));
            return towny.refreshByName(name)
                    .map(resolved -> {
                        sender.sendMessage(Component.text("Queued " + resolved + " for re-annotation.",
                                NamedTextColor.GREEN));
                        return true;
                    })
                    .orElseGet(() -> {
                        sender.sendMessage(Component.text("Towny has no town named '" + name
                                + "', or it has no spawn yet.", NamedTextColor.RED));
                        return true;
                    });
        }
        sender.sendMessage(Component.text("Usage: /earth towny <refresh [town]|status>", NamedTextColor.YELLOW));
        return true;
    }

    private boolean playerCommand(CommandSender sender, String permission, java.util.function.Function<Player, Boolean> action) {
        if (!sender.hasPermission(permission)) return denied(sender);
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by a player.", NamedTextColor.RED));
            return true;
        }
        return action.apply(player);
    }

    private boolean denied(CommandSender sender) {
        sender.sendMessage(Component.text("You do not have permission for this command.", NamedTextColor.RED));
        return true;
    }

    private boolean help(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /earth <info|whereami|coords|distance|country|city|teleport city|country|cache>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Pregenerate: /earth pregenerate <radius 0-32|status|cancel>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Admin: /earth towny <refresh [town]|status>, /earth debug [overlay], /earth reload", NamedTextColor.YELLOW));
        return true;
    }

    private boolean whereAmI(Player player) {
        var location = player.getLocation();
        var geographic = plugin.transformer().toGeographic(location.getX(), location.getZ());
        String coordinates = String.format(Locale.ROOT, "%.5f, %.5f", geographic.latitude(), geographic.longitude());
        player.sendMessage(Component.text("Location: " + coordinates + elevationSuffix(geographic),
                NamedTextColor.AQUA));

        var boundaries = plugin.boundaries();
        if (boundaries.isEmpty()) {
            player.sendMessage(Component.text("Geography is not prepared; only coordinates are available.",
                    NamedTextColor.GRAY));
            return true;
        }
        var index = boundaries.get();
        String country = index.countryAt(geographic.latitude(), geographic.longitude())
                .map(value -> value.name() + " (" + value.isoCode() + ")").orElse("unknown country");
        String region = index.regionAt(geographic.latitude(), geographic.longitude())
                .map(value -> ", " + value.name()).orElse("");
        player.sendMessage(Component.text(country + region, NamedTextColor.GREEN));
        index.nearestCity(geographic.latitude(), geographic.longitude()).ifPresent(city -> {
            double metres = Geodesy.vincentyMeters(geographic, city.position());
            player.sendMessage(Component.text("Nearest place: " + city.name() + ", " + humanDistance(metres)
                    + " " + Geodesy.compassPoint(Geodesy.initialBearingDegrees(
                            geographic.latitude(), geographic.longitude(),
                            city.position().latitude(), city.position().longitude())),
                    NamedTextColor.GREEN));
        });
        return true;
    }

    /**
     * Real elevation at a point, from the prepared DEM rather than the player's Y.
     *
     * <p>The two differ by the vertical scale and by whatever the player is standing on, so
     * reporting the block Y here would be a different number that only looks like an answer.
     */
    private String elevationSuffix(GeoPoint point) {
        double metres = plugin.elevation().elevationAt(point.latitude(), point.longitude());
        if (dev.terraforge.core.data.ElevationProvider.isNoData(metres)) {
            return " — no elevation data here";
        }
        return String.format(Locale.ROOT, " — %.0f m above sea level", metres);
    }

    private static String humanDistance(double metres) {
        return metres >= 1_000.0
                ? String.format(Locale.ROOT, "%.1f km", metres / 1_000.0)
                : String.format(Locale.ROOT, "%.0f m", metres);
    }

    private boolean coordinates(Player player, String latitudeText, String longitudeText) {
        try {
            GeoPoint point = parsePoint(latitudeText, longitudeText);
            var minecraft = plugin.transformer().toMinecraft(point);
            String position = String.format(Locale.ROOT, "X %.2f, Z %.2f (chunk %d, %d)",
                    minecraft.x(), minecraft.z(), minecraft.chunkX(), minecraft.chunkZ());
            player.sendMessage(Component.text(position, NamedTextColor.AQUA));
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text("Coordinates must be finite WGS84 latitude and longitude values.",
                    NamedTextColor.RED));
        }
        return true;
    }

    private boolean distance(Player player, String latitudeText, String longitudeText) {
        try {
            GeoPoint target = parsePoint(latitudeText, longitudeText);
            var location = player.getLocation();
            GeoPoint origin = plugin.transformer().toGeographic(location.getX(), location.getZ());
            double metres = Geodesy.vincentyMeters(origin, target);
            double bearing = Geodesy.initialBearingDegrees(origin.latitude(), origin.longitude(), target.latitude(), target.longitude());
            String value = metres >= 1_000.0 ? String.format(Locale.ROOT, "%.2f km", metres / 1_000.0)
                    : String.format(Locale.ROOT, "%.0f m", metres);
            player.sendMessage(Component.text("Distance: " + value + " " + Geodesy.compassPoint(bearing),
                    NamedTextColor.AQUA));
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.text("Coordinates must be finite WGS84 latitude and longitude values.",
                    NamedTextColor.RED));
        }
        return true;
    }

    private boolean country(Player player, String query) {
        var boundaries = plugin.boundaries();
        if (boundaries.isEmpty()) {
            player.sendMessage(Component.text("Country boundaries are not prepared.", NamedTextColor.GRAY));
            return true;
        }
        boundaries.get().findCountry(query).ifPresentOrElse(country -> {
            var centre = country.bounds().center();
            String detail = String.format(Locale.ROOT, "%s (%s), centre %.4f, %.4f", country.name(),
                    country.isoCode(), centre.latitude(), centre.longitude());
            player.sendMessage(Component.text(detail, NamedTextColor.AQUA));
        }, () -> player.sendMessage(Component.text("Country not found: " + query, NamedTextColor.RED)));
        return true;
    }

    private boolean city(Player player, String query) {
        var boundaries = plugin.boundaries();
        if (boundaries.isEmpty()) {
            player.sendMessage(Component.text("City data is not prepared.", NamedTextColor.GRAY));
            return true;
        }
        boundaries.get().findCity(query).ifPresentOrElse(city -> {
            String detail = String.format(Locale.ROOT, "%s — %.4f, %.4f; population %,d%s", city.name(),
                    city.position().latitude(), city.position().longitude(), city.population(), city.capital() ? "; capital" : "");
            player.sendMessage(Component.text(detail, NamedTextColor.AQUA));
        }, () -> player.sendMessage(Component.text("City not found: " + query, NamedTextColor.RED)));
        return true;
    }

    private boolean teleportCity(Player player, String query) {
        var geography = plugin.boundaries();
        if (geography.isEmpty()) {
            player.sendMessage(Component.text("City data is not prepared.", NamedTextColor.GRAY));
            return true;
        }
        var city = geography.get().findCity(query);
        if (city.isEmpty()) {
            player.sendMessage(Component.text("City not found: " + query, NamedTextColor.RED));
            return true;
        }
        return teleportToPoint(player, city.get().position(), "Teleported to " + city.get().name() + ".");
    }

    private boolean teleportCountry(Player player, String query) {
        var geography = plugin.boundaries();
        if (geography.isEmpty()) {
            player.sendMessage(Component.text("Country boundaries are not prepared.", NamedTextColor.GRAY));
            return true;
        }
        var country = geography.get().findCountry(query);
        if (country.isEmpty()) {
            player.sendMessage(Component.text("Country not found: " + query, NamedTextColor.RED));
            return true;
        }
        var anchor = geography.get().getCountryAnchor(country.get());
        if (anchor.isEmpty()) {
            player.sendMessage(Component.text("Country has no usable teleport anchor: " + country.get().name(), NamedTextColor.RED));
            return true;
        }
        return teleportToPoint(player, anchor.get(), "Teleported to " + country.get().name() + ".");
    }

    private boolean teleportToPoint(Player player, GeoPoint point, String successMessage) {
        var minecraft = plugin.transformer().toMinecraft(point);
        int blockX = minecraft.blockX(), blockZ = minecraft.blockZ();
        var samples = plugin.terrain().chunkSampler().sample(minecraft.chunkX(), minecraft.chunkZ());
        var sample = samples.at(Math.floorMod(blockX, 16), Math.floorMod(blockZ, 16));
        int y = Math.max(sample.surfaceY(), sample.waterSurfaceY()) + 2;
        Location destination = new Location(player.getWorld(), minecraft.x() + 0.5, y, minecraft.z() + 0.5);
        player.teleportAsync(destination).thenAccept(success -> plugin.getServer().getScheduler().runTask(plugin,
                () -> player.sendMessage(Component.text(success ? successMessage : "Teleport was cancelled.",
                        success ? NamedTextColor.GREEN : NamedTextColor.RED))));
        return true;
    }

    private static GeoPoint parsePoint(String latitudeText, String longitudeText) {
        return new GeoPoint(Double.parseDouble(latitudeText), Double.parseDouble(longitudeText));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(SUBCOMMANDS.stream().filter(value -> sender.hasPermission(permissionFor(value))), args[0]);
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission(permissionFor(subcommand))) {
            return List.of();
        }
        return switch (subcommand) {
            case "cache" -> args.length == 2 ? matching(Stream.of("clear"), args[1]) : List.of();
            case "pregenerate" -> args.length == 2
                    ? matching(Stream.of("status", "cancel", "4", "8", "16", "32"), args[1])
                    : List.of();
            case "debug" -> args.length == 2 ? matching(Stream.of("overlay"), args[1]) : List.of();
            case "teleport" -> teleportCompletions(args);
            case "country" -> args.length == 2 ? countryNames(args[1]) : List.of();
            case "city" -> args.length == 2 ? cityNames(args[1]) : List.of();
            case "towny" -> townyCompletions(args);
            default -> List.of();
        };
    }

    private List<String> teleportCompletions(String[] args) {
        if (args.length == 2) {
            return matching(Stream.of("city", "country"), args[1]);
        }
        if (args.length != 3) {
            return List.of();
        }
        return args[1].equalsIgnoreCase("country") ? countryNames(args[2]) : cityNames(args[2]);
    }

    private List<String> townyCompletions(String[] args) {
        if (args.length == 2) {
            return matching(Stream.of("refresh", "status"), args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("refresh")) {
            return plugin.townGeography()
                    .map(service -> matching(service.townNames().stream(), args[2]))
                    .orElse(List.of());
        }
        return List.of();
    }

    /**
     * Only the first word of a name is completed.
     *
     * <p>Bukkit splits arguments on spaces, so offering "New York" for the second word would make
     * the client insert a duplicate. The commands themselves rejoin the remaining arguments, so
     * typing the rest by hand still works.
     */
    private List<String> countryNames(String prefix) {
        return plugin.boundaries()
                .map(index -> matching(index.searchCountries(prefix, COMPLETION_LIMIT).stream()
                        .flatMap(country -> Stream.of(country.name(), country.isoCode())), prefix))
                .orElse(List.of());
    }

    private List<String> cityNames(String prefix) {
        return plugin.boundaries()
                .map(index -> matching(index.searchCities(prefix, COMPLETION_LIMIT).stream()
                        .map(city -> city.name()), prefix))
                .orElse(List.of());
    }

    private static List<String> matching(Stream<String> candidates, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return candidates
                .filter(value -> !value.contains(" "))
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .distinct()
                .limit(COMPLETION_LIMIT)
                .toList();
    }

    private static String permissionFor(String command) {
        return switch (command) {
            case "info" -> "terraforge.command.info";
            case "cache" -> "terraforge.command.cache";
            case "teleport" -> "terraforge.command.teleport";
            case "pregenerate" -> "terraforge.command.pregenerate";
            case "towny" -> "terraforge.command.towny";
            case "debug" -> "terraforge.command.debug";
            case "reload" -> "terraforge.command.reload";
            default -> "terraforge.command.location";
        };
    }
}
