package com.teamuhi.superbenemies.item

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.entity.boss.common.BossAircraft
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Difficulty
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.function.Supplier
import kotlin.math.max

/**
 * Spawns a boss aircraft (the A-10 or the AC-130). This is a plain [Item] rather than a vanilla spawn egg because the
 * vanilla `SpawnEggItem` only accepts `Mob` entity types and the bosses are vehicles.
 *
 * The boss appears already airborne, [SPAWN_ALTITUDE] blocks above the clicked position or the terrain below it
 * (whichever is higher), facing the player's look direction.
 *
 * @param type the boss entity type; the entity it creates must implement [BossAircraft]
 * @param clearanceHalfWidth half the width of the box that must be free of blocks around the spawn point: roughly the
 *        wingspan / 2 plus a margin (6 for the A-10, 16 for the 28 block wide AC-130)
 */
class AircraftBossSpawnEggItem(
    properties: Properties,
    private val type: Supplier<out EntityType<out VehicleEntity>>,
    private val clearanceHalfWidth: Double
) : Item(properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        if (level !is ServerLevel) return InteractionResult.SUCCESS
        val clicked = context.clickedPos
        return if (trySpawn(level, context.player, clicked, context.itemInHand)) {
            InteractionResult.CONSUME
        } else {
            InteractionResult.FAIL
        }
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level !is ServerLevel) return InteractionResultHolder.success(stack)

        val hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE)
        val base = if (hit.type == HitResult.Type.BLOCK) hit.blockPos else player.blockPosition()
        return if (trySpawn(level, player, base, stack)) {
            InteractionResultHolder.consume(stack)
        } else {
            InteractionResultHolder.fail(stack)
        }
    }

    private fun trySpawn(level: ServerLevel, player: Player?, base: BlockPos, stack: ItemStack): Boolean {
        fun refuse(key: String): Boolean {
            player?.displayClientMessage(
                Component.translatable("message.superbenemies.spawn_refused.$key").withStyle(ChatFormatting.RED),
                true
            )
            return false
        }

        if (level.difficulty == Difficulty.PEACEFUL) return refuse("peaceful")
        // Nether-style dimensions have a bedrock ceiling: there is no sky to fly in.
        if (level.dimensionType().hasCeiling()) return refuse("ceiling")

        val x = base.x + 0.5
        val z = base.z + 0.5
        if (!level.worldBorder.isWithinBounds(x, z, BORDER_MARGIN)) return refuse("border")

        val terrainY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.x, base.z)
        // Clicking deep underground: the heightmap surface is far above, so the sky is not reachable.
        if (terrainY - base.y > MAX_COVER) return refuse("no_sky")

        val spawnY = max(base.y, terrainY) + SPAWN_ALTITUDE
        if (spawnY + HEADROOM > level.maxBuildHeight) return refuse("too_high")

        // The airframe plus a safety margin must be free of blocks.
        val bossType = type.get()
        val half = clearanceHalfWidth
        // Vertical extent from the type's hitbox (at least the A-10's 7 blocks), so the tall AC-130 is covered too.
        val height = max(bossType.height.toDouble() + 2.0, MIN_CLEARANCE_HEIGHT)
        val clearance = AABB(x - half, spawnY - 2.0, z - half, x + half, spawnY - 2.0 + height, z + half)
        if (!level.noCollision(clearance)) return refuse("obstructed")

        val boss = bossType.create(level) ?: return refuse("failed")
        if (boss !is BossAircraft) {
            // Misconfigured registration: never leave a half-initialised entity behind.
            boss.discard()
            return refuse("failed")
        }
        val yaw = player?.yRot ?: level.random.nextFloat() * 360f
        boss.moveTo(x, spawnY, z, yaw, 0f)
        boss.prepareLaunch(Vec3(x, terrainY.toDouble(), z))
        if (!level.addFreshEntity(boss)) return refuse("failed")

        level.gameEvent(player, GameEvent.ENTITY_PLACE, boss.position())
        if (player != null) {
            player.cooldowns.addCooldown(this, COOLDOWN_TICKS)
            if (!player.abilities.instabuild) stack.shrink(1)
        }
        return true
    }

    private companion object {
        const val SPAWN_ALTITUDE = 60.0
        const val HEADROOM = 40.0
        const val MIN_CLEARANCE_HEIGHT = 7.0
        const val MAX_COVER = 60
        const val BORDER_MARGIN = 64.0
        const val COOLDOWN_TICKS = 40
    }
}
