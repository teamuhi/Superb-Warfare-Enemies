package com.teamuhi.superbenemies.init

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.entity.boss.a10.A10WarthogBossEntity
import com.teamuhi.superbenemies.entity.boss.ac130.Ac130BossEntity
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.entity.dummy.PlayerDummyEntity
import com.teamuhi.superbenemies.entity.soldier.DummySoldierEntity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraftforge.event.entity.EntityAttributeCreationEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
object ModEntities {
    val REGISTRY: DeferredRegister<EntityType<*>> =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, SuperbEnemies.MOD_ID)

    /**
     * Same builder settings as Superb Warfare's own vehicles (tracking range 512, update interval 1, fire immune)
     * and the same hitbox as its A-10. The registry id must match the data and asset files
     * `sbw/vehicles/a10_warthog_boss.json`, because Superb Warfare resolves vehicle data by entity type id.
     */
    @JvmField
    val A10_WARTHOG_BOSS: RegistryObject<EntityType<A10WarthogBossEntity>> =
        REGISTRY.register("a10_warthog_boss") {
            EntityType.Builder.of(::A10WarthogBossEntity, MobCategory.MISC)
                .sized(3.375f, 2.625f)
                .setTrackingRange(512)
                .setUpdateInterval(1)
                .fireImmune()
                .build("a10_warthog_boss")
        }

    /**
     * Same builder settings again, with the AC-130's hitbox. The registry id must match the data and asset files
     * `sbw/vehicles/ac130_angel_of_death.json`, because Superb Warfare resolves vehicle data by entity type id.
     */
    @JvmField
    val AC130_ANGEL_OF_DEATH: RegistryObject<EntityType<Ac130BossEntity>> =
        REGISTRY.register("ac130_angel_of_death") {
            EntityType.Builder.of(::Ac130BossEntity, MobCategory.MISC)
                .sized(12.5f, 6.8125f)
                .setTrackingRange(512)
                .setUpdateInterval(1)
                .fireImmune()
                .build("ac130_angel_of_death")
        }

    /**
     * Hidden crew member (pilot or gunner) of either boss. Not saved with the world (the boss re-creates it) and not
     * summonable. The registry id and field name are kept from when it was the A-10's pilot only.
     */
    @JvmField
    val BOSS_PILOT: RegistryObject<EntityType<BossCrewEntity>> =
        REGISTRY.register("boss_pilot") {
            EntityType.Builder.of(::BossCrewEntity, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .setTrackingRange(8)
                .noSave()
                .noSummon()
                .fireImmune()
                .build("boss_pilot")
        }

    /**
     * The player dummy: a player-sized, player-looking test target (see [PlayerDummyEntity]). MISC so it never counts
     * towards or gets removed by the mob caps; tracked from 64 blocks so it is visible at the range the bosses fight at.
     */
    @JvmField
    val PLAYER_DUMMY: RegistryObject<EntityType<PlayerDummyEntity>> =
        REGISTRY.register("player_dummy") {
            EntityType.Builder.of(::PlayerDummyEntity, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .setTrackingRange(64)
                .build("player_dummy")
        }

    /**
     * The Dummy Soldier: a player-shaped soldier carrying an Igla MANPADS that hunts the boss aircraft (see
     * [DummySoldierEntity]). Same category, hitbox and tracking range as the player dummy.
     */
    @JvmField
    val DUMMY_SOLDIER: RegistryObject<EntityType<DummySoldierEntity>> =
        REGISTRY.register("dummy_soldier") {
            EntityType.Builder.of(::DummySoldierEntity, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .setTrackingRange(64)
                .build("dummy_soldier")
        }

    @SubscribeEvent
    fun registerAttributes(event: EntityAttributeCreationEvent) {
        event.put(BOSS_PILOT.get(), Mob.createMobAttributes().build())
        event.put(PLAYER_DUMMY.get(), PlayerDummyEntity.createAttributes().build())
        event.put(DUMMY_SOLDIER.get(), DummySoldierEntity.createAttributes().build())
    }
}
