package com.teamuhi.superbenemies.entity.dummy

import com.teamuhi.superbenemies.entity.boss.common.ai.EntityTracker

/** Every player dummy; read by the boss target selection so it never scans the level for them. */
val TrackedDummies = EntityTracker { it is PlayerDummyEntity }
