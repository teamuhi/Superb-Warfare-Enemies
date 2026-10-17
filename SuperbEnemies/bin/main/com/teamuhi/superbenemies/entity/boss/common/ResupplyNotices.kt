package com.teamuhi.superbenemies.entity.boss.common

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Style

/** Chat styles of the "withdraws" / "begins hunting" notices shared by every boss that flies off to rearm. */
object ResupplyNotices {
    /** ChatFormatting has no orange, so the withdraw notice uses a true orange RGB. */
    val WITHDRAW_STYLE: Style = Style.EMPTY.withColor(0xFF8C00)
    val HUNTING_STYLE: Style = Style.EMPTY.withColor(ChatFormatting.RED).withBold(true)
}
