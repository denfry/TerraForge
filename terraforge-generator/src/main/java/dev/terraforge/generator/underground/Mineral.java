package dev.terraforge.generator.underground;

import org.bukkit.Material;

/**
 * Every block the underground pass places into stone.
 *
 * <p>Ores are deliberately the 1.16 set -- no deepslate variants -- plus copper: a player on a 1.16
 * client through ViaBackwards must see the same ore the server holds, and deepslate ores are exactly
 * what such a client cannot mine as ore. Copper is the one exception, because a 1.17+ player cannot
 * craft without it. Tuff is newer than 1.16 too, but it is only rock: an old client shown a stand-in
 * block loses nothing. Blackstone is 1.16's own.
 */
public enum Mineral {
    DIRT(Material.DIRT, false),
    GRAVEL(Material.GRAVEL, false),
    GRANITE(Material.GRANITE, true),
    DIORITE(Material.DIORITE, true),
    ANDESITE(Material.ANDESITE, true),
    TUFF(Material.TUFF, true),
    BLACKSTONE(Material.BLACKSTONE, true),
    COAL(Material.COAL_ORE, false),
    IRON(Material.IRON_ORE, false),
    COPPER(Material.COPPER_ORE, false),
    GOLD(Material.GOLD_ORE, false),
    REDSTONE(Material.REDSTONE_ORE, false),
    LAPIS(Material.LAPIS_ORE, false),
    DIAMOND(Material.DIAMOND_ORE, false),
    EMERALD(Material.EMERALD_ORE, false);

    private final Material material;
    private final boolean hostRock;

    Mineral(Material material, boolean hostRock) {
        this.material = material;
        this.hostRock = hostRock;
    }

    public Material material() {
        return material;
    }

    /** Whether ore may replace this block, as vanilla's ores replace granite, diorite and andesite. */
    boolean hostRock() {
        return hostRock;
    }
}
