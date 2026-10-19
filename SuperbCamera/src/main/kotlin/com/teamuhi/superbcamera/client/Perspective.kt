package com.teamuhi.superbcamera.client

import net.minecraft.world.phys.Vec3

/** What a [Perspective] is attached to. */
enum class Anchor(val key: String) {
    /** The vehicle the player rides (full pitch and roll with Superb Warfare). Falls back to [PLAYER] on foot. */
    VEHICLE("vehicle"),

    /** The player (or the spectated entity), rotating with the body only. */
    PLAYER("player"),

    /** A fixed spot in the world, for fly-by shots. */
    WORLD("world"),

    /** An entity picked in the Targets tab. */
    TARGET("target")
}

/** Which side of the screen the editor panel sits on. */
enum class PanelSide { LEFT, RIGHT }

/**
 * How large the editor panel is drawn, independent of the game's GUI scale. [pixelsPerUnit] is the number of real
 * pixels one panel unit covers (0 means "the game's GUI scale"); the panel never gets bigger than the GUI scale.
 */
enum class UiSize(val key: String, val pixelsPerUnit: Int) {
    COMPACT("compact", 2),
    NORMAL("normal", 3),
    LARGE("large", 0)
}

/**
 * One saved camera, shown as an extra entry in the F5 cycle. Plain mutable data: it is what Gson writes to
 * `config/superbcamera.json`, so every property needs a default (Gson builds it through the no-arg constructor).
 *
 * Offsets are expressed in the frame of the rotation in use (see [CameraMath]): +X right, +Y up, +Z forward.
 * Angles are degrees, [yaw] and [roll] in -180..180, [pitch] in -90..90 (positive pitch looks down, like Minecraft).
 */
class Perspective(
    var name: String = "",
    var anchor: Anchor = Anchor.PLAYER,
    /** True: the rotation is relative to the anchor. False: it follows the player's look plus the offsets. */
    var locked: Boolean = true,
    var x: Double = 0.0,
    var y: Double = 0.0,
    var z: Double = DEFAULT_Z,
    var yaw: Double = 0.0,
    var pitch: Double = 0.0,
    var roll: Double = 0.0,
    var fov: Double = DEFAULT_FOV,
    /** Per-tick retention of the previous camera state: 0 is an exact lock, 0.95 is very laggy. */
    var smoothing: Double = 0.0,
    var inheritPitch: Boolean = true,
    var inheritRoll: Boolean = true,
    var hideHud: Boolean = false,
    var worldX: Double = 0.0,
    var worldY: Double = 0.0,
    var worldZ: Double = 0.0,
    var worldYaw: Double = 0.0,
    var worldPitch: Double = 0.0,
    /** Dimension id of the stored world spot, empty when none was captured yet. */
    var worldDim: String = "",
    /** Entity type id of the picked target (UUIDs do not survive respawns). Empty when none was picked. */
    var targetType: String = "",
    /** Display name of the picked target, for the editor only. */
    var targetName: String = "",
    /** When the target is gone, follow the nearest loaded entity of the same type instead. */
    var reacquire: Boolean = true,
    /** The anchor to return to when following stops. */
    var anchorBeforeTarget: Anchor = Anchor.PLAYER
) {
    /** UUID of the followed entity, resolved at runtime only. */
    @Transient
    var targetUuid: java.util.UUID? = null

    /** Cached entity for [targetUuid]; see [TargetTracker]. */
    @Transient
    var targetEntity: net.minecraft.world.entity.Entity? = null

    /** Level game time before which [TargetTracker] does not scan for the target again. */
    @Transient
    var nextScanTick: Long = 0L

    /** The dimension warning was already shown for this perspective. */
    @Transient
    var dimensionWarned: Boolean = false

    /** The stored world spot as a vector. */
    val worldPos: Vec3 get() = Vec3(worldX, worldY, worldZ)

    /** Restores every camera setting to its default, keeping the name. Picked target and world spot are cleared too. */
    fun resetToDefaults() {
        copyFrom(Perspective(name = name))
    }

    /** Copies every saved setting from [other] (the runtime target is dropped, it belongs to the original). */
    fun copyFrom(other: Perspective) {
        name = other.name
        anchor = other.anchor
        locked = other.locked
        x = other.x
        y = other.y
        z = other.z
        yaw = other.yaw
        pitch = other.pitch
        roll = other.roll
        fov = other.fov
        smoothing = other.smoothing
        inheritPitch = other.inheritPitch
        inheritRoll = other.inheritRoll
        hideHud = other.hideHud
        worldX = other.worldX
        worldY = other.worldY
        worldZ = other.worldZ
        worldYaw = other.worldYaw
        worldPitch = other.worldPitch
        worldDim = other.worldDim
        targetType = other.targetType
        targetName = other.targetName
        reacquire = other.reacquire
        anchorBeforeTarget = other.anchorBeforeTarget
        targetUuid = other.targetUuid
        targetEntity = other.targetEntity
        dimensionWarned = false
    }

    /**
     * Forces every value into its valid range and repairs fields Gson left null (an unknown enum name or a missing
     * key reads as null even for non-null Kotlin types). NaN and infinite numbers fall back to the default.
     */
    fun sanitize(index: Int) {
        // Gson can hand back nulls for non-null Kotlin properties; read through nullable views to repair them.
        val rawName: String? = name
        val rawAnchor: Anchor? = anchor
        val rawBefore: Anchor? = anchorBeforeTarget
        val rawDim: String? = worldDim
        val rawType: String? = targetType
        val rawTargetName: String? = targetName

        name = cleanName(rawName, index)
        anchor = rawAnchor ?: Anchor.PLAYER
        anchorBeforeTarget = (rawBefore ?: Anchor.PLAYER).takeIf { it != Anchor.TARGET } ?: Anchor.PLAYER
        worldDim = rawDim ?: ""
        targetType = rawType ?: ""
        targetName = rawTargetName ?: ""

        x = x.finiteOr(0.0).coerceIn(-POSITION_LIMIT, POSITION_LIMIT)
        y = y.finiteOr(0.0).coerceIn(-POSITION_LIMIT, POSITION_LIMIT)
        z = z.finiteOr(DEFAULT_Z).coerceIn(-POSITION_LIMIT, POSITION_LIMIT)
        yaw = yaw.finiteOr(0.0).coerceIn(-180.0, 180.0)
        pitch = pitch.finiteOr(0.0).coerceIn(-90.0, 90.0)
        roll = roll.finiteOr(0.0).coerceIn(-180.0, 180.0)
        fov = fov.finiteOr(DEFAULT_FOV).coerceIn(FOV_MIN, FOV_MAX)
        smoothing = smoothing.finiteOr(0.0).coerceIn(0.0, SMOOTHING_MAX)
        worldX = worldX.finiteOr(0.0)
        worldY = worldY.finiteOr(0.0)
        worldZ = worldZ.finiteOr(0.0)
        worldYaw = worldYaw.finiteOr(0.0)
        worldPitch = worldPitch.finiteOr(0.0).coerceIn(-90.0, 90.0)
        // A target without a type cannot be followed; drop to the anchor it replaced.
        if (anchor == Anchor.TARGET && targetType.isEmpty()) anchor = anchorBeforeTarget
    }

    private fun Double.finiteOr(default: Double): Double = if (isFinite()) this else default

    companion object {
        const val MAX_NAME_LENGTH = 24
        const val POSITION_LIMIT = 64.0
        const val FOV_MIN = 10.0
        const val FOV_MAX = 150.0
        const val DEFAULT_FOV = 70.0
        const val DEFAULT_Z = -4.0
        const val SMOOTHING_MAX = 0.95

        /** The name trimmed and cut to [MAX_NAME_LENGTH]; blank becomes "Perspective [index]+1". */
        fun cleanName(raw: String?, index: Int): String {
            val trimmed = raw?.trim().orEmpty().take(MAX_NAME_LENGTH).trim()
            return trimmed.ifEmpty { "Perspective ${index + 1}" }
        }
    }
}
