package com.teamuhi.superbenemies.init

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.item.AircraftBossSpawnEggItem
import net.minecraft.world.item.Item
import net.minecraftforge.common.ForgeSpawnEggItem
import net.minecraft.world.item.Rarity
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModItems {
    val REGISTRY: DeferredRegister<Item> = DeferredRegister.create(ForgeRegistries.ITEMS, SuperbEnemies.MOD_ID)

    @JvmField
    val A10_BOSS_SPAWN_EGG: RegistryObject<AircraftBossSpawnEggItem> =
        REGISTRY.register("a10_boss_spawn_egg") {
            AircraftBossSpawnEggItem(
                Item.Properties().stacksTo(16).rarity(Rarity.EPIC),
                ModEntities.A10_WARTHOG_BOSS,
                A10_CLEARANCE_HALF_WIDTH
            )
        }

    @JvmField
    val AC130_BOSS_SPAWN_EGG: RegistryObject<AircraftBossSpawnEggItem> =
        REGISTRY.register("ac130_boss_spawn_egg") {
            AircraftBossSpawnEggItem(
                Item.Properties().stacksTo(16).rarity(Rarity.EPIC),
                ModEntities.AC130_ANGEL_OF_DEATH,
                AC130_CLEARANCE_HALF_WIDTH
            )
        }

    /** A vanilla-style spawn egg (works on blocks and in dispensers) for the player dummy. */
    @JvmField
    val PLAYER_DUMMY_SPAWN_EGG: RegistryObject<ForgeSpawnEggItem> =
        REGISTRY.register("player_dummy_spawn_egg") {
            ForgeSpawnEggItem(ModEntities.PLAYER_DUMMY, DUMMY_EGG_BASE_COLOR, DUMMY_EGG_SPOT_COLOR, Item.Properties())
        }

    /** A vanilla-style spawn egg for the Dummy Soldier. */
    @JvmField
    val DUMMY_SOLDIER_SPAWN_EGG: RegistryObject<ForgeSpawnEggItem> =
        REGISTRY.register("dummy_soldier_spawn_egg") {
            ForgeSpawnEggItem(ModEntities.DUMMY_SOLDIER, SOLDIER_EGG_BASE_COLOR, SOLDIER_EGG_SPOT_COLOR, Item.Properties())
        }

    /** Free airspace the spawn egg demands around the spawn point: about half the wingspan plus a margin. */
    private const val A10_CLEARANCE_HALF_WIDTH = 6.0
    private const val AC130_CLEARANCE_HALF_WIDTH = 16.0

    /** Skin tone and shirt blue of the default player skin; ClientSetup registers the same colours for the item tint. */
    const val DUMMY_EGG_BASE_COLOR = 0xC69C6D
    const val DUMMY_EGG_SPOT_COLOR = 0x3C44AA

    /** Olive drab and khaki for the soldier egg; ClientSetup registers the same colours for the item tint. */
    const val SOLDIER_EGG_BASE_COLOR = 0x556B2F
    const val SOLDIER_EGG_SPOT_COLOR = 0xC3B091
}
