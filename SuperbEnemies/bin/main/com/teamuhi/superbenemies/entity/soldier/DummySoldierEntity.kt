package com.teamuhi.superbenemies.entity.soldier

import com.atsuishio.superbwarfare.entity.projectile.IglaMissileEntity
import com.atsuishio.superbwarfare.init.ModSounds
import com.teamuhi.superbenemies.config.SoldierConfig
import com.teamuhi.superbenemies.config.SoldierSettings
import com.teamuhi.superbenemies.entity.dummy.DummySettings
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import com.teamuhi.superbenemies.entity.dummy.ai.DummyAi
import com.teamuhi.superbenemies.entity.dummy.ai.DummyHideGoal
import com.teamuhi.superbenemies.entity.dummy.ai.DummyLookGoal
import com.teamuhi.superbenemies.entity.dummy.ai.DummyRoamGoal
import com.teamuhi.superbenemies.entity.soldier.ai.SoldierIglaGoal
import com.teamuhi.superbenemies.init.ModItems
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Entity.RemovalReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import org.joml.Vector3d
import com.atsuishio.superbwarfare.init.ModItems as SbwItems

/**
 * A player-looking soldier with an Igla 9K38 MANPADS that shoots down the boss aircraft.
 *
 * The body, movement, health tag and so on come from [HumanoidDummyEntity]; this class adds the launcher: a synced
 * [aiming] flag (the renderer raises the arms with it), the ammo (loaded missile + reserve, a reload timer and the
 * resupply after a while without any), and the bookkeeping of the one missile in flight. The decisions (when to aim,
 * lock and fire) live in [SoldierIglaGoal].
 *
 * Superb Warfare's Igla only keeps steering while its *shooter is a player who is zooming*; with a mob as the owner it
 * would home forever. So the soldier enforces that rule itself: its missile is set lost the moment it stops aiming, dies
 * or is unloaded. When the missile disappears the soldier decides whether it hit (its last position was inside the
 * target's hitbox, inflated by the blast radius) and, per the config, runs for cover.
 *
 * All timers use [tickCount] (reset by a reload of the chunk); only the missile counts and the resupply countdown are saved.
 */
class DummySoldierEntity(type: EntityType<out PathfinderMob>, level: Level) : HumanoidDummyEntity(type, level) {

    override val settings: DummySettings get() = SoldierSettings

    override val coverMemoryTicks: Int get() = 0 // walk back as soon as the cover time is over
    override val maxLookPitch: Float get() = 89f // the launcher points almost straight up

    override fun coverEnabled(): Boolean = SoldierConfig.MOVEMENT.get() != com.teamuhi.superbenemies.config.SoldierMovement.ROAM

    /** True while the soldier has the launcher raised. Synced; written only by the server through [aim]. */
    val aiming: Boolean get() = entityData.get(DATA_AIMING)

    /** The missile in the launcher (0 or 1). */
    var loaded = 0
        private set

    /** Missiles carried besides the loaded one. */
    var reserve = 0
        private set

    /** The missile this soldier fired that is still flying and guided, or null. */
    var activeMissile: IglaMissileEntity? = null
        private set

    /** What [activeMissile] was fired at (to judge hit or miss when it is gone). */
    private var missileTarget: Entity? = null
    private var missileRadius = 0.0

    private var ammoReady = false
    private var launcherChecked = false

    /** Tick at which the next missile is loaded, or [NONE] when no reload is running. */
    private var reloadUntil = NONE

    /** Tick at which the empty soldier is resupplied, or [NONE]. */
    private var resupplyAt = NONE

    /** Until this tick the soldier wants to be in cover (after a hit, or a miss with `coverAfterMiss`). */
    private var coverUntil = 0

    // wantsCover() runs every tick (coverMemoryTicks is 0), so the boss lookup behind it is cached.
    private var bossNear = false
    private var nextBossCheck = 0

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, SoldierIglaGoal(this))
        goalSelector.addGoal(2, DummyHideGoal(this))
        goalSelector.addGoal(3, DummyRoamGoal(this))
        goalSelector.addGoal(4, DummyLookGoal(this))
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_AIMING, false)
    }

    override fun pickupItem(): ItemStack = ItemStack(ModItems.DUMMY_SOLDIER_SPAWN_EGG.get())

    // ================================================================================================== launcher state

    /** Raises or lowers the launcher. Server only; the renderer reads [aiming]. */
    fun aim(value: Boolean) {
        if (level().isClientSide || aiming == value) return
        entityData.set(DATA_AIMING, value)
    }

    /** True when a missile is loaded or can still be loaded (unlimited ammo, reserve), or one is in flight. */
    fun hasAnyMissile(): Boolean =
        loaded > 0 || reserve > 0 || activeMissile != null || SoldierConfig.UNLIMITED_AMMO.get()

    /**
     * Fires the loaded missile at [target]: copies Superb Warfare's `IglaItem.shootBullet` (right-shoulder muzzle, +0.3
     * on the direction's y, cloud puff behind the tube) and sets the missile to home on [target]. Returns false when
     * nothing was fired (no missile loaded, one already flying, or the level refused the entity).
     */
    fun launchMissile(target: Entity): Boolean {
        val serverLevel = level() as? ServerLevel ?: return false
        if (loaded <= 0 || activeMissile != null || !isAlive) return false

        val radius = SoldierConfig.EXPLOSION_RADIUS.get()
        val missile = IglaMissileEntity(
            this, serverLevel,
            SoldierConfig.MISSILE_DAMAGE.get().toFloat(),
            SoldierConfig.EXPLOSION_DAMAGE.get().toFloat(),
            radius.toFloat()
        )
        val shoulderYaw = (yRot + 360f + 90f) % 360f
        val muzzle = Vector3d(0.0, -0.2, 0.15)
            .rotateZ((-xRot * Mth.DEG_TO_RAD).toDouble())
            .rotateY((-shoulderYaw * Mth.DEG_TO_RAD).toDouble())
        missile.setPos(x + muzzle.x, eyeY + muzzle.y, z + muzzle.z)
        missile.setLife(PROJECTILE_LIFE)
        missile.setGuideType(0)
        missile.setTargetUuid(target.stringUUID)
        val look = lookAngle
        missile.shoot(look.x, look.y + 0.3, look.z, MUZZLE_VELOCITY, INACCURACY)
        if (!serverLevel.addFreshEntity(missile)) return false

        loaded = 0
        activeMissile = missile
        missileTarget = target
        missileRadius = radius

        serverLevel.playSound(null, x, y, z, ModSounds.IGLA_FIRE_3P.get(), SoundSource.PLAYERS, FIRE_VOLUME, 1f)
        serverLevel.sendParticles(
            ParticleTypes.CLOUD,
            x + 1.8 * look.x, y + bbHeight - 0.1 + 1.8 * look.y, z + 1.8 * look.z,
            30, 0.4, 0.4, 0.4, 0.005
        )
        return true
    }

    /**
     * Ends the guidance of the flying missile (it falls and explodes on its own) and forgets it. Called when the soldier
     * stops aiming, dies or is removed. The missile is then counted as a miss only by [tickMissile], never here.
     */
    fun releaseMissile() {
        val missile = activeMissile ?: return
        if (!missile.isRemoved) missile.setLost(true)
        activeMissile = null
        missileTarget = null
    }

    // ================================================================================================== cover

    override fun wantsCover(): Boolean {
        val now = tickCount
        if (now < coverUntil) return true
        if (!SoldierConfig.HIDE_WHEN_OUT_OF_AMMO.get() || hasAnyMissile()) return false
        if (now >= nextBossCheck) {
            nextBossCheck = now + BOSS_CHECK_INTERVAL
            bossNear = DummyAi.nearestBoss(this, false) != null
        }
        return bossNear
    }

    // ================================================================================================== spawning / saving

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forge's spawn event still routes spawns through this method
    override fun finalizeSpawn(
        level: ServerLevelAccessor, difficulty: DifficultyInstance, reason: MobSpawnType,
        spawnData: SpawnGroupData?, tag: CompoundTag?
    ): SpawnGroupData? {
        val data = super.finalizeSpawn(level, difficulty, reason, spawnData, tag)
        ensureLauncher()
        if (!ammoReady) initAmmo()
        return data
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        if (!ammoReady) return
        tag.putInt(TAG_LOADED, loaded)
        tag.putInt(TAG_RESERVE, reserve)
        tag.putInt(TAG_RESUPPLY_IN, if (resupplyAt == NONE) NONE else (resupplyAt - tickCount).coerceAtLeast(0))
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        if (tag.contains(TAG_LOADED)) {
            loaded = tag.getInt(TAG_LOADED).coerceIn(0, 1)
            reserve = tag.getInt(TAG_RESERVE).coerceAtLeast(0)
            val resupplyIn = tag.getInt(TAG_RESUPPLY_IN)
            resupplyAt = if (resupplyIn >= 0) resupplyIn else NONE
            ammoReady = true
        }
    }

    /** The Igla in the main hand (for the renderer, and so the soldier looks armed); never dropped. */
    private fun ensureLauncher() {
        launcherChecked = true
        val igla = SbwItems.IGLA_9K38.get()
        if (getItemBySlot(EquipmentSlot.MAINHAND).`is`(igla)) return
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack(igla))
        setDropChance(EquipmentSlot.MAINHAND, 0f)
    }

    /** First ammo setup: `missiles` counts the loaded one. */
    private fun initAmmo() {
        ammoReady = true
        loaded = 1
        reserve = (SoldierConfig.MISSILES.get() - 1).coerceAtLeast(0)
        reloadUntil = NONE
        resupplyAt = NONE
    }

    // ================================================================================================== per-tick upkeep

    override fun customServerAiStep() {
        super.customServerAiStep()
        val now = tickCount
        if (!launcherChecked || now % LAUNCHER_CHECK_INTERVAL == 0) ensureLauncher() // also covers /summon
        tickMissile(now)
        tickAmmo(now)
    }

    /**
     * Missile watchdog. A flying missile is set lost (and forgotten) as soon as the soldier stops aiming. A missile that
     * is gone is judged: a hit when its last position was inside the target's hitbox inflated by the blast radius plus
     * [HIT_MARGIN]. A hit starts the cover time with `coverAfterHit`, a miss only with `coverAfterMiss`.
     */
    private fun tickMissile(now: Int) {
        val missile = activeMissile ?: return
        val lost = !missile.isRemoved && !aiming
        if (!missile.isRemoved && !lost) return

        val target = missileTarget
        val hit = !lost && target != null &&
            target.boundingBox.inflate(missileRadius + HIT_MARGIN).contains(missile.x, missile.y, missile.z)
        if (lost) missile.setLost(true)
        activeMissile = null
        missileTarget = null

        val cover = if (hit) SoldierConfig.COVER_AFTER_HIT.get() else SoldierConfig.COVER_AFTER_MISS.get()
        if (cover) coverUntil = now + SoldierConfig.COVER_SECONDS.get() * 20
    }

    /** Reload timer, resupply timer, and clamping when the config shrank `missiles`. */
    private fun tickAmmo(now: Int) {
        if (!ammoReady) initAmmo()
        val unlimited = SoldierConfig.UNLIMITED_AMMO.get()

        if (!unlimited && now % CLAMP_INTERVAL == 0) {
            val cap = SoldierConfig.MISSILES.get()
            if (loaded > cap) loaded = cap
            if (loaded + reserve > cap) reserve = (cap - loaded).coerceAtLeast(0)
        }

        if (loaded > 0 || activeMissile != null) {
            reloadUntil = NONE
            resupplyAt = NONE
            return
        }

        if (unlimited || reserve > 0) {
            resupplyAt = NONE
            if (reloadUntil == NONE) reloadUntil = now + SoldierConfig.RELOAD_TICKS.get()
            if (now >= reloadUntil) {
                loaded = 1
                if (!unlimited) reserve--
                reloadUntil = NONE
            }
            return
        }

        // Completely empty: resupply after the configured delay (0 = never).
        reloadUntil = NONE
        val seconds = SoldierConfig.RESUPPLY_SECONDS.get()
        if (seconds <= 0) {
            resupplyAt = NONE
        } else if (resupplyAt == NONE) {
            resupplyAt = now + seconds * 20
        } else if (now >= resupplyAt) {
            reserve = SoldierConfig.MISSILES.get() // the reload above puts the first one in the launcher
            resupplyAt = NONE
        }
    }

    // ================================================================================================== death / removal

    override fun die(source: DamageSource) {
        super.die(source)
        releaseMissile()
        if (!level().isClientSide) aim(false)
    }

    /** Also covers chunk unloads and `discard()` (sneak pickup): the missile must not keep homing without its shooter. */
    override fun remove(reason: RemovalReason) {
        releaseMissile()
        super.remove(reason)
    }

    companion object {
        private val DATA_AIMING: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(DummySoldierEntity::class.java, EntityDataSerializers.BOOLEAN)

        private const val TAG_LOADED = "MissileLoaded"
        private const val TAG_RESERVE = "MissileReserve"
        private const val TAG_RESUPPLY_IN = "ResupplyIn"

        private const val NONE = -1

        /** The Igla's `ProjectileLife` and the `Velocity` of its json. */
        private const val PROJECTILE_LIFE = 300
        private const val MUZZLE_VELOCITY = 3f

        /** Spread of the launch (degrees-ish); the missile homes in afterwards, so it is small. */
        private const val INACCURACY = 1f
        private const val FIRE_VOLUME = 4f

        /** A missile that vanished this far (blocks) beyond the blast radius from the target's hitbox still counts as a hit. */
        private const val HIT_MARGIN = 2.0

        private const val LAUNCHER_CHECK_INTERVAL = 100
        private const val CLAMP_INTERVAL = 20
        private const val BOSS_CHECK_INTERVAL = 20

        /** Same stats as the player dummy (walk speed 0.1, 20 health, 32 follow range); armor comes from the config. */
        fun createAttributes(): AttributeSupplier.Builder = HumanoidDummyEntity.createDummyAttributes()
    }
}
