package com.teamuhi.superbenemies.entity.boss.ac130

/**
 * The AC-130's three side guns, each fired by the crew member in its own seat.
 *
 * The seat numbers and weapon names come from Superb Warfare's `ac_130h.json`: seat 0 is the pilot, seats 1-3 are the
 * gun seats. All three have `Orientation -90`, i.e. they look out of the **left** side of the airframe.
 *
 * The limits are relative to the seat's own frame, which is what SBW enforces for a player's mouse input (a Mob's
 * aim has to be clamped by our own code):
 *  - [minYaw] / [maxYaw]: degrees left (-) / right (+) of the seat's look direction (the JSON's `MinYaw` / `MaxYaw`).
 *  - [minDepression] / [maxDepression]: degrees **below** the seat's own horizontal, in Minecraft's xRot sense
 *    (positive = looking down). These are the JSON's `MinPitch` / `MaxPitch` negated, because the JSON measures
 *    pitch positive = up.
 *
 * @property seat passenger seat index of the gunner
 * @property weaponName key into the vehicle data's `Weapons` map; pass the gunner (not this name) to
 *           `vehicleShoot`, because only the seat overload honours the seat's shoot direction
 */
enum class Ac130Gun(
    val seat: Int,
    val weaponName: String,
    val minYaw: Float,
    val maxYaw: Float,
    val minDepression: Float,
    val maxDepression: Float
) {
    /** 25 mm GAU-12 style cannon, 1200 rpm. */
    M61(1, "M61", -15f, 15f, 15f, 40f),

    /** 40 mm Bofors, 12 round magazine. */
    BOFORS(2, "Bofors", -15f, 15f, -5f, 60f),

    /** 105 mm howitzer, single shot per reload. */
    M102(3, "M102", -15f, 15f, 5f, 35f);
}
