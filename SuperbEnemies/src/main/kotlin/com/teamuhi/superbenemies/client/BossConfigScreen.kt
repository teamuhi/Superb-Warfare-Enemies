package com.teamuhi.superbenemies.client

import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ContainerObjectSelectionList
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraftforge.common.ForgeConfigSpec
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * One config file shown by [BossConfigScreen] as a tab: its [title], its [spec] and the options to list, in file order.
 * A section header is drawn whenever the first path element changes. [requires] maps an option to the on/off option of
 * the same page it depends on; while that toggle is off (as currently edited) the option is greyed out and not editable.
 * [sliders] lists the integer options shown as a slider over the given range instead of a text box (Forge's own range
 * class is private, so the spec cannot be asked); the spec still validates the value.
 */
data class ConfigPage(
    val title: Component,
    val spec: ForgeConfigSpec,
    val values: List<ForgeConfigSpec.ConfigValue<*>>,
    val requires: Map<ForgeConfigSpec.ConfigValue<*>, ForgeConfigSpec.ConfigValue<Boolean>> = emptyMap(),
    val disabledWhenEnabled: Map<ForgeConfigSpec.ConfigValue<*>, ForgeConfigSpec.ConfigValue<Boolean>> = emptyMap(),
    val sliders: Map<ForgeConfigSpec.ConfigValue<*>, IntRange> = emptyMap()
)

/**
 * In-game editor for the mod's config files, opened from the Mods screen. Forge 1.20.1 has no automatic config screen,
 * so this builds one from the specs: an on/off button per boolean, a cycle button per enum, a slider per small integer
 * range (the 0 to 5 target weights) and a validated text box per other number (range shown in the tooltip).
 *
 * One tab per [pages] entry, a search box that filters the open tab, a reset button per option and per tab, and
 * change markers: edited options turn yellow with a dot, tabs get a `*` (unsaved) or `!` (invalid) suffix and the
 * unsaved count is shown above the buttons. Done is disabled while any value on any tab is invalid and saves every
 * spec whose values changed; Cancel and Esc ask before throwing away unsaved edits.
 *
 * The selected tab, the search text and the drafts are fields, so a window resize (which rebuilds the widgets) keeps them.
 * Edits the local copy of the common config only: on a dedicated server the server's own file is what counts.
 */
class BossConfigScreen(private val parent: Screen, private val pages: List<ConfigPage>) :
    Screen(Component.translatable("screen.superbenemies.config.title")) {

    private val values = pages.flatMap { it.values }

    /** The spec each option belongs to, so range checks and saves go to the right file. */
    private val pageOf = HashMap<ForgeConfigSpec.ConfigValue<*>, ConfigPage>().also { map ->
        for (page in pages) for (value in page.values) map[value] = page
    }

    /** Text of every option (all tabs), kept across window resizes and tab switches. */
    private val drafts = HashMap<ForgeConfigSpec.ConfigValue<*>, String>().also { map ->
        for (value in values) map[value] = value.get().toString()
    }

    private var selectedTab = 0
    private var search = ""

    private var list: ConfigList? = null
    private var doneButton: Button? = null
    private var resetTabButton: Button? = null
    private val tabButtons = ArrayList<Button>()

    /** Tooltip of the option label under the mouse this frame, drawn last so the list's scissor cannot clip it. */
    private var labelTooltip: Tooltip? = null

    override fun init() {
        selectedTab = selectedTab.coerceIn(0, pages.lastIndex)
        val previousScroll = list?.scrollAmount ?: 0.0

        // Tab row.
        tabButtons.clear()
        val tabWidth = minOf(TAB_MAX_WIDTH, (width - 2 * SIDE_MARGIN - (pages.size - 1) * TAB_GAP) / pages.size)
        val tabsLeft = (width - (pages.size * tabWidth + (pages.size - 1) * TAB_GAP)) / 2
        for (index in pages.indices) {
            tabButtons += addRenderableWidget(
                Button.builder(tabLabel(index)) { selectTab(index) }
                    .bounds(tabsLeft + index * (tabWidth + TAB_GAP), TAB_Y, tabWidth, 20).build()
            )
        }

        // Search box.
        val box = EditBox(
            font, width / 2 - SEARCH_WIDTH / 2, SEARCH_Y, SEARCH_WIDTH, 20,
            Component.translatable("screen.superbenemies.config.search")
        )
        box.setMaxLength(MAX_SEARCH_LENGTH)
        box.setHint(Component.translatable("screen.superbenemies.config.search").withStyle(ChatFormatting.GRAY))
        box.value = search
        box.setResponder { text ->
            search = text
            populate(0.0)
        }
        addRenderableWidget(box)

        // Bottom row: Done / Reset Tab / Cancel.
        val buttonsLeft = width / 2 - (3 * BUTTON_WIDTH + 2 * BUTTON_GAP) / 2
        val buttonsTop = height - BUTTONS_BOTTOM_OFFSET
        doneButton = addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) {
                save()
                leave()
            }.bounds(buttonsLeft, buttonsTop, BUTTON_WIDTH, 20).build()
        )
        resetTabButton = addRenderableWidget(
            Button.builder(Component.translatable("screen.superbenemies.config.reset_tab")) { resetTab() }
                .bounds(buttonsLeft + BUTTON_WIDTH + BUTTON_GAP, buttonsTop, BUTTON_WIDTH, 20).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(buttonsLeft + 2 * (BUTTON_WIDTH + BUTTON_GAP), buttonsTop, BUTTON_WIDTH, 20).build()
        )

        val newList = ConfigList()
        list = newList
        addWidget(newList)
        populate(previousScroll)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics)
        labelTooltip = null
        list?.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, TITLE_Y, 0xFFFFFF)
        renderStatusLine(graphics)
        super.render(graphics, mouseX, mouseY, partialTick)
        labelTooltip?.let { graphics.renderTooltip(font, it.toCharSequence(minecraft!!), mouseX, mouseY) }
    }

    /** Invalid values first (they block Done), then the unsaved count, otherwise the usual note. */
    private fun renderStatusLine(graphics: GuiGraphics) {
        val invalid = invalidCount()
        val unsaved = unsavedCount()
        val (text, color) = when {
            invalid > 0 -> Component.translatable("screen.superbenemies.config.invalid", invalid) to ERROR_COLOR
            unsaved > 0 -> unsavedText(unsaved) to GOLD_COLOR
            else -> Component.translatable("screen.superbenemies.config.note") to MUTED_COLOR
        }
        graphics.drawCenteredString(font, text, width / 2, height - STATUS_BOTTOM_OFFSET, color)
    }

    /** Esc and Cancel: leave straight away, or ask first when there is something to lose. */
    override fun onClose() {
        val unsaved = unsavedCount()
        if (unsaved == 0) {
            leave()
            return
        }
        // Denying returns to this same instance, so tab, search, scroll and drafts are all still there.
        minecraft?.setScreen(
            ConfirmScreen(
                { discard -> if (discard) leave() else minecraft?.setScreen(this) },
                Component.translatable("screen.superbenemies.config.discard.title"),
                Component.translatable("screen.superbenemies.config.discard.message", unsavedText(unsaved))
            )
        )
    }

    /** Back to the screen that opened this one, without asking. */
    private fun leave() {
        minecraft?.setScreen(parent)
    }

    // ------------------------------------------------------------------------------------------------ tabs

    private fun selectTab(index: Int) {
        if (index == selectedTab) return
        selectedTab = index
        refreshStatus()
        populate(0.0)
    }

    /** Tab title plus ` *` when the tab has unsaved edits and ` !` when it holds an invalid value. */
    private fun tabLabel(index: Int): Component {
        val page = pages[index]
        val label = Component.empty().append(page.title)
        if (page.values.any { isDirty(it) }) label.append(Component.literal(" *").withStyle(ChatFormatting.GOLD))
        if (page.values.any { parse(it, drafts[it] ?: "") == null }) {
            label.append(Component.literal(" !").withStyle(ChatFormatting.RED))
        }
        return label
    }

    /** Re-evaluates everything that depends on the drafts: tab labels, Done and Reset Tab. Cheap enough per keystroke. */
    private fun refreshStatus() {
        for ((index, button) in tabButtons.withIndex()) {
            button.message = tabLabel(index)
            button.active = index != selectedTab
        }
        doneButton?.active = values.all { parse(it, drafts[it] ?: "") != null }
        resetTabButton?.active = pages[selectedTab].values.any { !isDefault(it) }
    }

    /** Sets every option of the open tab to its default. Not saved until Done. */
    private fun resetTab() {
        for (value in pages[selectedTab].values) drafts[value] = defaultText(value)
        populate(list?.scrollAmount ?: 0.0)
    }

    // ------------------------------------------------------------------------------------------------ values

    private fun specOf(value: ForgeConfigSpec.ConfigValue<*>): ForgeConfigSpec.ValueSpec =
        pageOf.getValue(value).spec.spec.get<ForgeConfigSpec.ValueSpec>(value.path)

    /** The typed value for [text], or null when it is not a valid number / is outside the option's range. */
    private fun parse(value: ForgeConfigSpec.ConfigValue<*>, text: String): Any? = when (value.get()) {
        is Boolean -> text.toBooleanStrictOrNull()
        // ValueSpec.test applies the option's own range check.
        is Int -> text.trim().toIntOrNull()?.takeIf { specOf(value).test(it) }
        is Double -> text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && specOf(value).test(it) }
        is Enum<*> -> enumConstants(value).firstOrNull { it.name == text }
        else -> null
    }

    /** Every constant of an enum-valued option, in declaration order. */
    private fun enumConstants(value: ForgeConfigSpec.ConfigValue<*>): List<Enum<*>> =
        (value.get() as Enum<*>).declaringJavaClass.enumConstants.toList()

    /** "PLAYERS_AND_MOBS" -> "Players And Mobs". */
    private fun enumLabel(constant: Enum<*>): String =
        constant.name.lowercase().split('_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    /** The option's default as draft text (enum defaults may be stored as the constant itself). */
    private fun defaultText(value: ForgeConfigSpec.ConfigValue<*>): String =
        when (val default = specOf(value).default) {
            is Enum<*> -> default.name
            else -> default.toString()
        }

    /** True when the draft parses to the default value (so "12" and "12.0" both count for a double default). */
    private fun isDefault(value: ForgeConfigSpec.ConfigValue<*>): Boolean {
        val parsed = parse(value, drafts[value] ?: return true) ?: return false
        return parsed == parse(value, defaultText(value))
    }

    /** True when the draft differs from the value currently in the config (an invalid draft counts as an edit). */
    private fun isDirty(value: ForgeConfigSpec.ConfigValue<*>): Boolean {
        val draft = drafts[value] ?: return false
        return parse(value, draft) != value.get()
    }

    /** False while the toggle this option depends on ([ConfigPage.requires]) is switched off in the drafts. */
    private fun isAvailable(value: ForgeConfigSpec.ConfigValue<*>): Boolean {
        val page = pageOf.getValue(value)
        val requiredToggle = page.requires[value]
        if (requiredToggle != null && !(drafts[requiredToggle]?.toBooleanStrictOrNull() ?: requiredToggle.get())) return false
        val disabledToggle = page.disabledWhenEnabled[value]
        if (disabledToggle != null && (drafts[disabledToggle]?.toBooleanStrictOrNull() ?: disabledToggle.get())) return false
        return true
    }

    private fun unsavedCount(): Int = values.count { isDirty(it) }

    private fun invalidCount(): Int = values.count { parse(it, drafts[it] ?: "") == null }

    private fun unsavedText(count: Int): Component =
        if (count == 1) Component.translatable("screen.superbenemies.config.unsaved_one")
        else Component.translatable("screen.superbenemies.config.unsaved", count)

    private fun save() {
        for (page in pages) {
            var changed = false
            for (value in page.values) {
                val parsed = parse(value, drafts[value] ?: continue) ?: continue
                @Suppress("UNCHECKED_CAST")
                val typed = value as ForgeConfigSpec.ConfigValue<Any>
                if (typed.get() != parsed) {
                    typed.set(parsed)
                    changed = true
                }
            }
            // Only rewrite the files that were actually edited.
            if (changed) page.spec.save()
        }
    }

    /** "cruiseHeightAboveTarget" -> "Cruise Height Above Target". */
    private fun humanize(key: String): String =
        key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

    /** Case-insensitive match on the humanized label or the raw key; an empty query matches everything. */
    private fun matches(value: ForgeConfigSpec.ConfigValue<*>, query: String): Boolean {
        if (query.isEmpty()) return true
        val key = value.path.last()
        return humanize(key).lowercase().contains(query) || key.lowercase().contains(query)
    }

    // ------------------------------------------------------------------------------------------------ list

    /**
     * Refills the list with the open tab, filtered by [search]; a section header only appears when a row under it matches.
     * Rebuilds the row widgets from the drafts, so call it after any draft was changed from outside a widget.
     */
    private fun populate(scroll: Double) {
        val target = list ?: return
        target.clearAll()
        val query = search.trim().lowercase()
        var section: String? = null
        var header: Row? = null
        for (value in pages[selectedTab].values) {
            val name = value.path.first()
            if (name != section) {
                section = name
                header = HeaderRow(Component.translatable("screen.superbenemies.config.section.$name"))
            }
            if (!matches(value, query)) continue
            header?.let {
                target.addRow(it)
                header = null
            }
            target.addRow(OptionRow(value))
        }
        if (target.isEmpty()) target.addRow(NoticeRow(Component.translatable("screen.superbenemies.config.no_results")))
        target.setScrollAmount(scroll)
        refreshStatus()
    }

    private abstract class Row : ContainerObjectSelectionList.Entry<Row>()

    private inner class ConfigList : ContainerObjectSelectionList<Row>(
        this@BossConfigScreen.minecraft!!, this@BossConfigScreen.width, this@BossConfigScreen.height,
        LIST_TOP, this@BossConfigScreen.height - LIST_BOTTOM_MARGIN, ROW_HEIGHT
    ) {
        fun addRow(row: Row) {
            addEntry(row)
        }

        fun clearAll() {
            clearEntries()
        }

        fun isEmpty(): Boolean = children().isEmpty()

        override fun getRowWidth(): Int = ROW_WIDTH

        override fun getScrollbarPosition(): Int = this@BossConfigScreen.width / 2 + ROW_WIDTH / 2 + 10
    }

    /** Section title in gold with a thin divider line on each side. */
    private inner class HeaderRow(private val text: Component) : Row() {
        override fun render(
            graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int,
            mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float
        ) {
            val centre = left + width / 2
            val half = font.width(text) / 2
            graphics.drawCenteredString(font, text, centre, top + 8, GOLD_COLOR)
            val lineY = top + 12
            graphics.hLine(left, centre - half - DIVIDER_GAP, lineY, DIVIDER_COLOR)
            graphics.hLine(centre + half + DIVIDER_GAP, left + width, lineY, DIVIDER_COLOR)
        }

        override fun children(): List<GuiEventListener> = emptyList()
        override fun narratables(): List<NarratableEntry> = emptyList()
    }

    /** A single grey centered line, used for the empty-search result. */
    private inner class NoticeRow(private val text: Component) : Row() {
        override fun render(
            graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int,
            mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float
        ) {
            graphics.drawCenteredString(font, text, left + width / 2, top + 8, MUTED_COLOR)
        }

        override fun children(): List<GuiEventListener> = emptyList()
        override fun narratables(): List<NarratableEntry> = emptyList()
    }

    private inner class OptionRow(private val value: ForgeConfigSpec.ConfigValue<*>) : Row() {
        private val name = humanize(value.path.last())
        private val label = Component.literal(name)
        private val widget: AbstractWidget = createWidget()

        /** Sets the draft back to the option's default and rebuilds the rows so the control shows it. */
        private val resetButton: Button = Button.builder(Component.literal("↺")) {
            drafts[value] = defaultText(value)
            populate(list?.scrollAmount ?: 0.0)
        }.size(RESET_SIZE, RESET_SIZE).tooltip(Tooltip.create(Component.translatable("screen.superbenemies.config.reset")))
            .build()

        private val tooltip: Tooltip

        init {
            // The spec comment already ends with the "Range: a ~ b" line for numeric options.
            val spec = specOf(value)
            val text = listOfNotNull(
                spec.comment?.takeIf { it.isNotBlank() }?.trim(),
                "Default: ${spec.default}"
            ).joinToString("\n")
            tooltip = Tooltip.create(Component.literal(text))
            widget.setTooltip(tooltip)
        }

        private fun createWidget(): AbstractWidget {
            val draft = drafts[value] ?: value.get().toString()
            if (value.get() is Boolean) {
                return CycleButton.onOffBuilder(draft.toBooleanStrictOrNull() ?: true)
                    .displayOnlyValue()
                    .create(0, 0, CONTROL_WIDTH, 20, label) { _, enabled ->
                        drafts[value] = enabled.toString()
                        refreshStatus()
                    }
            }
            if (value.get() is Enum<*>) {
                val constants = enumConstants(value)
                val initial = constants.firstOrNull { it.name == draft } ?: value.get() as Enum<*>
                return CycleButton.builder<Enum<*>> { Component.literal(enumLabel(it)) }
                    .withValues(constants)
                    .withInitialValue(initial)
                    .displayOnlyValue()
                    .create(0, 0, CONTROL_WIDTH, 20, label) { _, selected ->
                        drafts[value] = selected.name
                        refreshStatus()
                    }
            }
            val sliderRange = pageOf.getValue(value).sliders[value]
            if (sliderRange != null) {
                val initial = draft.trim().toIntOrNull()?.coerceIn(sliderRange.first, sliderRange.last) ?: sliderRange.first
                return IntSlider(value, sliderRange.first, sliderRange.last, initial, label)
            }
            val box = EditBox(font, 0, 0, CONTROL_WIDTH, 20, label)
            box.setMaxLength(MAX_NUMBER_LENGTH)
            box.value = draft
            box.setTextColor(if (parse(value, draft) != null) VALID_COLOR else INVALID_COLOR)
            box.setResponder { text ->
                drafts[value] = text
                box.setTextColor(if (parse(value, text) != null) VALID_COLOR else INVALID_COLOR)
                refreshStatus()
            }
            return box
        }

        override fun render(
            graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int,
            mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float
        ) {
            resetButton.x = left + width - RESET_SIZE
            resetButton.y = top
            widget.x = resetButton.x - ELEMENT_GAP - CONTROL_WIDTH
            widget.y = top

            // Greyed out (and not editable) while the option it depends on is off.
            val available = isAvailable(value)
            widget.active = available
            (widget as? EditBox)?.setEditable(available)

            val invalid = parse(value, drafts[value] ?: "") == null
            val dirty = invalid || isDirty(value)
            val labelWidth = widget.x - left - ELEMENT_GAP
            val shown = fit((if (dirty) "● " else "") + name, labelWidth)
            val labelColor = when {
                !available -> MUTED_COLOR
                invalid -> ERROR_COLOR
                dirty -> EDITED_COLOR
                else -> 0xFFFFFF
            }
            graphics.drawString(font, shown, left, top + 6, labelColor)
            if (mouseX in left until left + labelWidth && mouseY in top until top + 20 &&
                mouseY in LIST_TOP until this@BossConfigScreen.height - LIST_BOTTOM_MARGIN
            ) {
                labelTooltip = tooltip
            }

            resetButton.active = !isDefault(value)
            widget.render(graphics, mouseX, mouseY, partialTick)
            resetButton.render(graphics, mouseX, mouseY, partialTick)
        }

        /** [text] cut to [maxWidth] pixels with a trailing ellipsis. */
        private fun fit(text: String, maxWidth: Int): String =
            if (font.width(text) <= maxWidth) text
            else font.plainSubstrByWidth(text, maxWidth - font.width(ELLIPSIS)) + ELLIPSIS

        override fun children(): List<GuiEventListener> = listOf(widget, resetButton)
        override fun narratables(): List<NarratableEntry> = listOf(widget, resetButton)
    }

    /**
     * Slider for an integer option with a small range: it snaps to whole numbers [min]..[max] and writes the number into
     * the option's draft, so [parse], [isDirty] and the reset button treat it like a typed value. Shows the number, and
     * "Off" for 0 when the range starts at 0.
     */
    private inner class IntSlider(
        private val option: ForgeConfigSpec.ConfigValue<*>,
        private val min: Int,
        private val max: Int,
        initial: Int,
        label: Component
    ) : AbstractSliderButton(0, 0, CONTROL_WIDTH, 20, label, fractionOf(initial, min, max)) {

        init {
            updateMessage()
        }

        private fun selected(): Int = (min + (value * (max - min)).roundToInt()).coerceIn(min, max)

        override fun updateMessage() {
            val n = selected()
            message = if (n == 0 && min == 0) Component.translatable("screen.superbenemies.config.slider_off")
            else Component.literal(n.toString())
        }

        override fun applyValue() {
            val n = selected()
            value = fractionOf(n, min, max)
            drafts[option] = n.toString()
            refreshStatus()
        }

        /** Left / right step by one whole number instead of one pixel of the track. */
        override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
            val step = when (keyCode) {
                GLFW.GLFW_KEY_LEFT -> -1
                GLFW.GLFW_KEY_RIGHT -> 1
                else -> return super.keyPressed(keyCode, scanCode, modifiers)
            }
            val n = (selected() + step).coerceIn(min, max)
            if (n != selected()) {
                value = fractionOf(n, min, max)
                applyValue()
                updateMessage()
            }
            return true
        }
    }

    private companion object {
        /** Slider position (0..1) of [n] within [min]..[max]. */
        fun fractionOf(n: Int, min: Int, max: Int): Double = if (max > min) (n - min).toDouble() / (max - min) else 0.0

        const val TITLE_Y = 8
        const val TAB_Y = 22
        const val TAB_MAX_WIDTH = 120
        const val TAB_GAP = 4
        const val SIDE_MARGIN = 10
        const val SEARCH_Y = 46
        const val SEARCH_WIDTH = 200
        const val MAX_SEARCH_LENGTH = 40
        const val LIST_TOP = 72
        const val LIST_BOTTOM_MARGIN = 52
        const val STATUS_BOTTOM_OFFSET = 43
        const val BUTTONS_BOTTOM_OFFSET = 28
        const val BUTTON_WIDTH = 100
        const val BUTTON_GAP = 5
        const val ROW_HEIGHT = 24
        const val ROW_WIDTH = 330
        const val CONTROL_WIDTH = 100
        const val RESET_SIZE = 20
        const val ELEMENT_GAP = 4
        const val DIVIDER_GAP = 6
        const val MAX_NUMBER_LENGTH = 16
        const val ELLIPSIS = "…"

        // Minecraft has no light/dark theme: these read on both the dimmed in-world background and the dirt one.
        const val VALID_COLOR = 0xE0E0E0
        const val INVALID_COLOR = 0xFF5555
        const val ERROR_COLOR = 0xFF5555
        const val GOLD_COLOR = 0xFFD966
        const val EDITED_COLOR = 0xFFFF55
        const val MUTED_COLOR = 0xA0A0A0
        const val DIVIDER_COLOR = 0x80FFD966.toInt()
    }
}
