package com.teamuhi.superbcamera.client.ui

import com.teamuhi.superbcamera.client.Anchor
import com.teamuhi.superbcamera.client.CameraController
import com.teamuhi.superbcamera.client.CameraStore
import com.teamuhi.superbcamera.client.PanelSide
import com.teamuhi.superbcamera.client.Perspective
import com.teamuhi.superbcamera.client.TargetTracker
import com.teamuhi.superbcamera.client.UiSize
import com.teamuhi.superbcamera.client.ui.kit.ButtonStyle
import com.teamuhi.superbcamera.client.ui.kit.FlatButton
import com.teamuhi.superbcamera.client.ui.kit.NumberField
import com.teamuhi.superbcamera.client.ui.kit.NumberSpec
import com.teamuhi.superbcamera.client.ui.kit.RailTab
import com.teamuhi.superbcamera.client.ui.kit.ScrollArea
import com.teamuhi.superbcamera.client.ui.kit.Section
import com.teamuhi.superbcamera.client.ui.kit.Segment
import com.teamuhi.superbcamera.client.ui.kit.Segmented
import com.teamuhi.superbcamera.client.ui.kit.TabRail
import com.teamuhi.superbcamera.client.ui.kit.TextBlock
import com.teamuhi.superbcamera.client.ui.kit.TextField
import com.teamuhi.superbcamera.client.ui.kit.Toggle
import com.teamuhi.superbcamera.client.ui.kit.Ui
import com.teamuhi.superbcamera.client.ui.kit.UiRoot
import com.teamuhi.superbcamera.client.ui.kit.Widget
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.Mth
import java.util.EnumMap
import java.util.Objects

/**
 * The flat, categorized side panel: header, category rail, one page per category in a scroll area, and a footer.
 * Built from the widget kit in panel units (see [UiRoot]); nothing here is a vanilla button.
 *
 * All state lives in [CameraStore] and the edited [Perspective], so the panel can be rebuilt at any time (resize,
 * switching perspective, structural changes such as a different anchor) without losing anything. A rebuild requested
 * from inside an event handler is deferred to the next [update] so the widget lists are never modified mid-dispatch.
 *
 * Every user change goes through [EditHistory.push] first, which is what Ctrl+Z restores.
 */
class EditorPanel(
    private val ui: UiRoot,
    private val mc: Minecraft,
    private val history: EditHistory,
    private val host: Host
) {

    /** What the panel needs from the screen that hosts it. */
    interface Host {
        /** Closes the editor, keeping the camera locked on the perspective. */
        fun close()

        /** The edited perspective changed identity (switch, add, delete): running modal operations must stop. */
        fun perspectiveChanged()

        /** The UI size setting changed: recompute the scale, then rebuild. */
        fun relayout()
    }

    private enum class Tab(val key: String) {
        TRANSFORM("transform"), LENS("lens"), ANCHOR("anchor"), TARGETS("targets"), KEYS("keys")
    }

    private val font = ui.font
    private val targets = TargetList(mc)

    private var scrollArea: ScrollArea? = null
    private var builtFor: Perspective? = null
    private var pendingRebuild = true
    private var pendingBody = false
    private var bodySignature = 0
    private var targetTick = 0

    private val perspective: Perspective? get() = CameraStore.active

    // ------------------------------------------------------------------------------------------------ update

    /** Asks for a full rebuild before the next frame. */
    fun requestRebuild() {
        pendingRebuild = true
    }

    /** Applies deferred rebuilds and notices structural changes (anchor, picked target) made by undo or hotkeys. */
    fun update() {
        val p = perspective
        if (pendingRebuild || p !== builtFor) {
            rebuild()
        } else if ((pendingBody || signature(p) != bodySignature) && !ui.typing) {
            // Never swap the widgets under a field that is being typed in; the change is applied once it is done.
            fillBody()
        }
    }

    /** Game tick: cursor blinking and the 10-tick refresh of the Targets list. */
    fun tick() {
        ui.tick()
        if (tab == Tab.TARGETS && ++targetTick >= TARGET_REFRESH_TICKS) {
            targetTick = 0
            targets.refresh()
            pendingBody = true
        }
    }

    private fun signature(p: Perspective?): Int =
        if (p == null) 0 else Objects.hash(p.anchor, p.targetType.isEmpty(), p.locked, p.targetName)

    // ------------------------------------------------------------------------------------------------ build

    private fun rebuild() {
        scrollMemory[tab] = scrollArea?.scroll ?: 0.0
        ui.clear()
        scrollArea = null
        pendingRebuild = false
        val p = perspective
        builtFor = p
        if (p == null) return

        val side = CameraStore.panelSide
        val x0 = if (side == PanelSide.LEFT) MARGIN else ui.viewWidth - MARGIN - PANEL_WIDTH
        val y0 = MARGIN
        val height = (ui.viewHeight - 2 * MARGIN).coerceAtLeast(MIN_PANEL_HEIGHT)
        ui.panelX = x0
        ui.panelY = y0
        ui.panelWidth = PANEL_WIDTH
        ui.panelHeight = height

        val background = Background()
        background.place(x0, y0, PANEL_WIDTH, height)
        ui.children += background

        val inner = PANEL_WIDTH - 2 * PAD
        val ix = x0 + PAD
        var y = y0 + PAD

        val header = HeaderBar(
            font, { perspective }, { CameraStore.activeIndex }, { CameraStore.perspectives.size },
            onPrevious = { switchBy(-1) }, onNext = { switchBy(1) }, onAdd = { addPerspective() }, onDelete = { deleteActive() }
        )
        header.place(ix, y, inner, HEADER_HEIGHT)
        ui.children += header
        y += HEADER_HEIGHT + 4

        val rail = TabRail(
            Tab.entries.map {
                RailTab(
                    Component.translatable("screen.superbcamera.tab.${it.key}"),
                    Component.translatable("screen.superbcamera.tab.${it.key}.tooltip")
                )
            },
            selected = { tab.ordinal },
            onSelect = { selectTab(Tab.entries[it]) }
        )
        rail.place(ix, y, inner, RAIL_HEIGHT)
        ui.children += rail
        y += RAIL_HEIGHT + 3

        val footerTop = y0 + height - PAD - FOOTER_HEIGHT
        val bottomWidgets = ArrayList<Widget>()
        var bottomHeight = 0
        when (tab) {
            Tab.TARGETS -> {
                y += addTargetsToolbar(ix, y, inner)
                bottomHeight = buildTargetsBottom(bottomWidgets, inner)
            }
            else -> {}
        }

        val bodyBottom = footerTop - 3 - (if (bottomHeight > 0) bottomHeight + 2 else 0)
        val area = ScrollArea()
        area.place(ix, y, inner, (bodyBottom - y).coerceAtLeast(MIN_BODY))
        ui.children += area
        scrollArea = area

        var by = area.y + area.height + 2
        for (widget in bottomWidgets) {
            widget.place(ix, by, inner, widget.height)
            ui.children += widget
            by += widget.height + 2
        }

        addFooter(ix, footerTop, inner)

        if (tab == Tab.TARGETS) targets.refresh()
        fillBody()
        area.scrollTo(scrollMemory[tab] ?: 0.0)
    }

    private fun addFooter(ix: Int, top: Int, inner: Int) {
        val resetWidth = 44
        val sideWidth = 16
        val exitWidth = inner - resetWidth - sideWidth - 4

        val reset = FlatButton(
            Component.translatable("screen.superbcamera.reset"), Component.translatable("screen.superbcamera.reset.tooltip")
        ) { resetPerspective() }
        reset.place(ix, top, resetWidth, Ui.ROW)

        val exit = FlatButton(
            Component.translatable("screen.superbcamera.exit"), Component.translatable("screen.superbcamera.exit.tooltip")
        ) { exitToVanilla() }
        exit.place(ix + resetWidth + 2, top, exitWidth, Ui.ROW)

        val sideButton = FlatButton(
            label = { if (CameraStore.panelSide == PanelSide.RIGHT) "⇆" else "⇄" },
            tooltip = { Component.translatable("screen.superbcamera.side").string },
            onPress = { togglePanelSide() }
        )
        sideButton.place(ix + inner - sideWidth, top, sideWidth, Ui.ROW)

        val sizeWidth = 54
        val size = Segmented(
            UiSize.entries.map {
                Segment(
                    Component.translatable("screen.superbcamera.size.${it.key}"),
                    Component.translatable("screen.superbcamera.size.${it.key}.tooltip")
                )
            },
            selected = { CameraStore.uiSize.ordinal },
            onSelect = {
                CameraStore.uiSize = UiSize.entries[it]
                host.relayout()
            }
        )
        size.place(ix, top + Ui.ROW + 2, sizeWidth, Ui.ROW)

        val done = FlatButton(
            Component.translatable("screen.superbcamera.done"), Component.translatable("screen.superbcamera.done.tooltip"),
            style = ButtonStyle.ACCENT
        ) { host.close() }
        done.place(ix + sizeWidth + 2, top + Ui.ROW + 2, inner - sizeWidth - 2, Ui.ROW)

        for (widget in listOf(reset, exit, sideButton, size, done)) ui.children += widget
    }

    // ------------------------------------------------------------------------------------------------ pages

    /** Fills the scroll area for the current tab (cheap; also called after structural changes and list refreshes). */
    private fun fillBody() {
        pendingBody = false
        val area = scrollArea ?: return
        val p = perspective ?: return
        bodySignature = signature(p)
        val column = Column(area.contentWidth)
        when (tab) {
            Tab.TRANSFORM -> buildTransform(column, p)
            Tab.LENS -> buildLens(column, p)
            Tab.ANCHOR -> buildAnchor(column, p)
            Tab.TARGETS -> buildTargetRows(column, p)
            Tab.KEYS -> buildKeys(column)
        }
        area.setContent(column.widgets, column.height)
    }

    /** Stacks widgets top to bottom in content space. */
    private inner class Column(val width: Int) {
        val widgets = ArrayList<Widget>()
        private var cursor = 0
        val height: Int get() = cursor

        fun add(widget: Widget, height: Int, gap: Int = 2) {
            widget.place(0, cursor, width, height)
            widgets += widget
            cursor += height + gap
        }

        fun space(amount: Int) {
            cursor += amount
        }
    }

    private fun section(column: Column, id: String, title: Component): Boolean {
        val open = id !in closedSections
        column.add(
            Section(title, { id !in closedSections }) {
                if (!closedSections.remove(id)) closedSections += id
                pendingBody = true
            },
            SECTION_HEIGHT, 3
        )
        return open
    }

    private fun numberField(
        label: String, color: Int, labelWidth: Int, p: Perspective, spec: NumberSpec,
        tooltip: String? = null
    ): NumberField = NumberField(
        font, label, color, labelWidth, spec,
        tooltip?.let { Component.translatable(it) }, Component.translatable("screen.superbcamera.reset_value")
    ) { key -> history.push(p, key) }

    private fun buildTransform(column: Column, p: Perspective) {
        val limit = Perspective.POSITION_LIMIT
        fun position(label: String, color: Int, axis: String, get: () -> Double, set: (Double) -> Unit, default: Double) =
            numberField(
                label, color, TRANSFORM_LABEL, p,
                NumberSpec(get, set, -limit, limit, decimals = 3, dragPerUnit = 0.025, wheelStep = 0.1, snap = 1.0, default = default),
                "screen.superbcamera.pos.$axis.tooltip"
            )

        if (section(column, "location", Component.translatable("screen.superbcamera.section.location"))) {
            column.add(position("X", Ui.AXIS_X, "x", { p.x }, { p.x = it }, 0.0), Ui.ROW)
            column.add(position("Y", Ui.AXIS_Y, "y", { p.y }, { p.y = it }, 0.0), Ui.ROW)
            column.add(position("Z", Ui.AXIS_Z, "z", { p.z }, { p.z = it }, Perspective.DEFAULT_Z), Ui.ROW)
        }
        column.space(3)

        fun rotation(label: String, color: Int, axis: String, get: () -> Double, set: (Double) -> Unit, range: Double) =
            numberField(
                label, color, TRANSFORM_LABEL, p,
                NumberSpec(
                    get, set, -range, range, barMin = -range, barMax = range, decimals = 1,
                    dragPerUnit = 0.5, wheelStep = 1.0, snap = 5.0, default = 0.0, unit = "°"
                ),
                "screen.superbcamera.rot.$axis.tooltip"
            )

        if (section(column, "rotation", Component.translatable("screen.superbcamera.section.rotation"))) {
            column.add(rotation(Component.translatable("screen.superbcamera.rot.yaw").string, Ui.AXIS_Y, "yaw", { p.yaw }, { p.yaw = it }, 180.0), Ui.ROW)
            column.add(rotation(Component.translatable("screen.superbcamera.rot.pitch").string, Ui.AXIS_X, "pitch", { p.pitch }, { p.pitch = it }, 90.0), Ui.ROW)
            column.add(rotation(Component.translatable("screen.superbcamera.rot.roll").string, Ui.AXIS_Z, "roll", { p.roll }, { p.roll = it }, 180.0), Ui.ROW)
        }
    }

    private fun buildLens(column: Column, p: Perspective) {
        column.add(
            numberField(
                Component.translatable("screen.superbcamera.fov").string, Ui.ACCENT, LENS_LABEL, p,
                NumberSpec(
                    { p.fov }, { p.fov = it }, Perspective.FOV_MIN, Perspective.FOV_MAX,
                    barMin = Perspective.FOV_MIN, barMax = Perspective.FOV_MAX, decimals = 0,
                    dragPerUnit = 0.4, wheelStep = 1.0, snap = 5.0, default = Perspective.DEFAULT_FOV
                ),
                "screen.superbcamera.fov.tooltip"
            ), Ui.ROW
        )
        column.add(
            numberField(
                Component.translatable("screen.superbcamera.smoothing").string, Ui.ACCENT, LENS_LABEL, p,
                NumberSpec(
                    { p.smoothing }, { p.smoothing = it }, 0.0, Perspective.SMOOTHING_MAX,
                    barMin = 0.0, barMax = Perspective.SMOOTHING_MAX, decimals = 2,
                    dragPerUnit = 0.005, wheelStep = 0.01, snap = 0.05, default = 0.0
                ),
                "screen.superbcamera.smoothing.tooltip"
            ), Ui.ROW
        )
        column.space(3)
        column.add(
            Toggle(
                Component.translatable("screen.superbcamera.hide_hud"), Component.translatable("screen.superbcamera.hide_hud.tooltip"),
                { p.hideHud }
            ) { value -> edit(p) { p.hideHud = value } },
            Ui.ROW
        )
    }

    private fun buildAnchor(column: Column, p: Perspective) {
        val segments = Anchor.entries.map { anchor ->
            Segment(
                Component.translatable("screen.superbcamera.anchor.${anchor.key}"),
                Component.translatable(
                    if (anchor == Anchor.TARGET && p.targetType.isEmpty()) "screen.superbcamera.anchor.target.locked"
                    else "screen.superbcamera.anchor.${anchor.key}.tooltip"
                ),
                if (anchor == Anchor.TARGET) ({ p.targetType.isNotEmpty() }) else ({ true })
            )
        }
        column.add(Segmented(segments, { p.anchor.ordinal }) { setAnchor(p, Anchor.entries[it]) }, Ui.ROW, 4)

        column.add(
            Toggle(
                Component.translatable("screen.superbcamera.lock"), Component.translatable("screen.superbcamera.lock.tooltip"),
                { p.locked }
            ) { value -> edit(p) { p.locked = value } },
            Ui.ROW
        )
        val followsAnchorRotation = { p.locked && (p.anchor == Anchor.VEHICLE || p.anchor == Anchor.TARGET) }
        column.add(
            Toggle(
                Component.translatable("screen.superbcamera.inherit_pitch"), Component.translatable("screen.superbcamera.inherit_pitch.tooltip"),
                { p.inheritPitch }, followsAnchorRotation
            ) { value -> edit(p) { p.inheritPitch = value } },
            Ui.ROW
        )
        column.add(
            Toggle(
                Component.translatable("screen.superbcamera.inherit_roll"), Component.translatable("screen.superbcamera.inherit_roll.tooltip"),
                { p.inheritRoll }, { followsAnchorRotation() || !p.locked }
            ) { value -> edit(p) { p.inheritRoll = value } },
            Ui.ROW
        )
        if (p.anchor == Anchor.TARGET) {
            column.add(
                Toggle(
                    Component.translatable("screen.superbcamera.reacquire"), Component.translatable("screen.superbcamera.reacquire.tooltip"),
                    { p.reacquire }
                ) { value -> edit(p) { p.reacquire = value } },
                Ui.ROW
            )
        }
        if (p.anchor == Anchor.WORLD) {
            column.space(2)
            column.add(
                FlatButton(
                    Component.translatable("screen.superbcamera.world_here"), Component.translatable("screen.superbcamera.world_here.tooltip")
                ) { edit(p) { setWorldSpot(p) } },
                Ui.ROW
            )
        }

        column.space(4)
        val pivot = TextBlock(
            { Component.translatable("screen.superbcamera.anchor.pivot", CameraController.status) },
            { if (CameraController.statusWarning) Ui.DANGER else Ui.MUTED }
        )
        column.add(pivot, pivot.measure(ui, column.width).coerceAtLeast(font.lineHeight + 1) + font.lineHeight + 1)
    }

    private fun buildTargetRows(column: Column, p: Perspective) {
        val empty = targets.empty
        if (empty != null) {
            val notice = TextBlock({ Component.translatable("screen.superbcamera.targets.${empty.key}") })
            column.add(notice, notice.measure(ui, column.width) + 2)
            return
        }
        for (info in targets.rows) {
            column.add(
                TargetRow(info, { perspective?.takeIf { it.anchor == Anchor.TARGET }?.targetUuid }) { pickTarget(it) },
                TargetRow.HEIGHT
            )
        }
    }

    private fun buildKeys(column: Column) {
        column.add(
            Toggle(
                Component.translatable("screen.superbcamera.emulate"), Component.translatable("screen.superbcamera.emulate.tooltip"),
                { CameraStore.emulateMiddleMouse }
            ) { CameraStore.emulateMiddleMouse = it },
            Ui.ROW, 4
        )
        for ((key, desc) in KEY_ROWS) {
            val row = KeyRow(key, Component.translatable("screen.superbcamera.keys.$desc"))
            column.add(row, row.measure(ui, column.width), 0)
        }
    }

    // ------------------------------------------------------------------------------------------------ targets chrome

    /** The fixed search / filter / sort rows above the list; returns their height. */
    private fun addTargetsToolbar(ix: Int, y: Int, inner: Int): Int {
        val search = TextField(
            font, Component.translatable("screen.superbcamera.search"), MAX_SEARCH_LENGTH,
            onChange = { text ->
                if (text != targets.query) {
                    targets.query = text
                    targets.refresh()
                    scrollArea?.scrollTo(0.0)
                    pendingBody = true
                }
            }
        )
        search.value = targets.query
        search.place(ix, y, inner, Ui.ROW)
        ui.children += search

        val sortWidth = 26
        val filter = Segmented(
            TargetFilter.entries.map {
                Segment(
                    Component.translatable("screen.superbcamera.targets.filter.${it.key}.short"),
                    Component.translatable("screen.superbcamera.targets.filter", Component.translatable("screen.superbcamera.targets.filter.${it.key}"))
                )
            },
            selected = { targets.filter.ordinal },
            onSelect = {
                targets.filter = TargetFilter.entries[it]
                targets.refresh()
                scrollArea?.scrollTo(0.0)
                pendingBody = true
            }
        )
        filter.place(ix, y + Ui.ROW + 2, inner - sortWidth - 2, Ui.ROW)
        ui.children += filter

        val sort = FlatButton(
            label = { Component.translatable("screen.superbcamera.targets.sort.${targets.sort.key}.short").string },
            tooltip = {
                Component.translatable(
                    "screen.superbcamera.targets.sort", Component.translatable("screen.superbcamera.targets.sort.${targets.sort.key}")
                ).string
            },
            onPress = {
                targets.sort = targets.sort.next()
                targets.refresh()
                scrollArea?.scrollTo(0.0)
                pendingBody = true
            }
        )
        sort.place(ix + inner - sortWidth, y + Ui.ROW + 2, sortWidth, Ui.ROW)
        ui.children += sort
        return 2 * Ui.ROW + 2 + 3
    }

    /** The hint and the Stop button under the list; adds them to [out] (unplaced) and returns their total height. */
    private fun buildTargetsBottom(out: MutableList<Widget>, inner: Int): Int {
        val hint = TextBlock({
            Component.translatable(
                if (TargetTracker.canSpectate()) "screen.superbcamera.targets.hint.spectator" else "screen.superbcamera.targets.hint"
            )
        })
        val hintHeight = hint.measure(ui, inner)
        hint.place(0, 0, inner, hintHeight)
        val stop = FlatButton(
            Component.translatable("screen.superbcamera.targets.stop"),
            enabled = { perspective?.anchor == Anchor.TARGET }
        ) { stopFollowing() }
        stop.place(0, 0, inner, Ui.ROW)
        out += hint
        out += stop
        return hintHeight + 2 + Ui.ROW
    }

    // ------------------------------------------------------------------------------------------------ actions

    private fun selectTab(newTab: Tab) {
        if (newTab == tab) return
        scrollMemory[tab] = scrollArea?.scroll ?: 0.0
        tab = newTab
        targetTick = 0
        pendingRebuild = true
    }

    /** Runs [block] as one undoable change to [p]. */
    private inline fun edit(p: Perspective, block: () -> Unit) {
        history.push(p)
        block()
    }

    /** Edits the neighbouring perspective instead (the same as pressing F5 to reach it). */
    private fun switchBy(delta: Int) {
        val count = CameraStore.perspectives.size
        if (count < 2) return
        CameraStore.activate(Math.floorMod(CameraStore.activeIndex + delta, count))
        perspectiveSwitched()
    }

    private fun addPerspective() {
        val current = perspective ?: return
        val index = CameraStore.add(current)
        if (index < 0) return
        CameraStore.activate(index)
        perspectiveSwitched()
    }

    private fun deleteActive() {
        if (perspective == null) return
        TargetTracker.release()
        CameraStore.delete(CameraStore.activeIndex)
        perspectiveSwitched()
    }

    private fun perspectiveSwitched() {
        history.clear()
        scrollMemory.clear()
        host.perspectiveChanged()
        pendingRebuild = true
    }

    private fun resetPerspective() {
        val p = perspective ?: return
        history.push(p)
        TargetTracker.stopFollowing(p)
        p.resetToDefaults()
        CameraController.resetSmoothing()
        pendingBody = true
    }

    private fun exitToVanilla() {
        ui.blur()
        TargetTracker.release()
        CameraStore.deactivate()
        CameraController.switchCameraType(CameraType.THIRD_PERSON_BACK)
        host.close()
    }

    private fun togglePanelSide() {
        CameraStore.panelSide = if (CameraStore.panelSide == PanelSide.RIGHT) PanelSide.LEFT else PanelSide.RIGHT
        pendingRebuild = true
    }

    private fun pickTarget(info: TargetInfo) {
        val p = perspective ?: return
        history.push(p)
        TargetTracker.select(p, info.entity)
        CameraController.resetSmoothing()
    }

    private fun stopFollowing() {
        val p = perspective ?: return
        history.push(p)
        TargetTracker.stopFollowing(p)
        CameraController.resetSmoothing()
    }

    /** Switches the anchor. A fresh World anchor captures the current view; Target remembers the anchor it replaced. */
    private fun setAnchor(p: Perspective, next: Anchor) {
        if (next == p.anchor || (next == Anchor.TARGET && p.targetType.isEmpty())) return
        history.push(p)
        if (next == Anchor.TARGET && p.anchor != Anchor.TARGET) p.anchorBeforeTarget = p.anchor
        p.anchor = next
        if (next == Anchor.WORLD && p.worldDim.isEmpty()) setWorldSpot(p)
        CameraController.resetSmoothing()
    }

    /** Stores the current camera position and direction as the world spot; the offsets are cleared so the view stays put. */
    private fun setWorldSpot(p: Perspective) {
        val camera = mc.gameRenderer.mainCamera
        val level = mc.level ?: return
        p.worldX = camera.position.x
        p.worldY = camera.position.y
        p.worldZ = camera.position.z
        p.worldYaw = Mth.wrapDegrees(camera.yRot).toDouble()
        p.worldPitch = camera.xRot.toDouble().coerceIn(-90.0, 90.0)
        p.worldDim = level.dimension().location().toString()
        p.dimensionWarned = false
        p.x = 0.0
        p.y = 0.0
        p.z = 0.0
        p.yaw = 0.0
        p.pitch = 0.0
        p.roll = 0.0
        CameraController.resetSmoothing()
    }

    // ------------------------------------------------------------------------------------------------ small widgets

    /** The translucent panel backdrop with its hairline border. */
    private class Background : Widget() {
        override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
            g.fill(x, y, x + width, y + height, Ui.SURFACE)
            Ui.outline(g, x, y, width, height, Ui.BORDER)
        }
    }

    /** One line of the Keys cheat sheet: the shortcut in a small chip and its (wrapped) description. */
    private class KeyRow(private val key: String, private val description: Component) : Widget() {
        private var lines: List<FormattedCharSequence> = emptyList()

        fun measure(ui: UiRoot, forWidth: Int): Int {
            lines = ui.font.split(description, (forWidth - KEY_COLUMN - 5).coerceAtLeast(8))
            return lines.size.coerceAtLeast(1) * ui.font.lineHeight + 3
        }

        override fun render(g: GuiGraphics, ui: UiRoot, mx: Int, my: Int) {
            g.fill(x, y + 1, x + KEY_COLUMN, y + 1 + ui.font.lineHeight + 1, Ui.RAISED)
            g.drawString(ui.font, Ui.fit(ui.font, key, KEY_COLUMN - 4), x + 2, y + 2, Ui.ACCENT, false)
            for ((index, line) in lines.withIndex()) {
                g.drawString(ui.font, line, x + KEY_COLUMN + 5, y + 2 + index * ui.font.lineHeight, Ui.TEXT, false)
            }
        }

        private companion object {
            const val KEY_COLUMN = 64
        }
    }

    private companion object {
        const val PANEL_WIDTH = 168
        const val MARGIN = 4
        const val PAD = 4
        const val HEADER_HEIGHT = 16
        const val RAIL_HEIGHT = 14
        const val SECTION_HEIGHT = 12
        const val FOOTER_HEIGHT = 2 * Ui.ROW + 2
        const val MIN_PANEL_HEIGHT = 140
        const val MIN_BODY = 24
        const val TRANSFORM_LABEL = 36
        const val LENS_LABEL = 54
        const val TARGET_REFRESH_TICKS = 10
        const val MAX_SEARCH_LENGTH = 40

        /** Open / closed state outlives the screen within a session. */
        val closedSections = HashSet<String>()
        val scrollMemory = EnumMap<Tab, Double>(Tab::class.java)
        var tab = Tab.TRANSFORM

        /** The Keys sheet: shortcut text and the suffix of its `screen.superbcamera.keys.*` description. */
        val KEY_ROWS = listOf(
            "MMB drag" to "orbit",
            "Shift+MMB" to "pan",
            "Ctrl+MMB" to "dolly",
            "Wheel" to "wheel",
            "Ctrl+Wheel" to "wheel_pan_x",
            "Shift+Wheel" to "wheel_pan_y",
            "Alt+Wheel" to "wheel_fov",
            "LMB drag" to "look",
            "Alt+LMB" to "emulation",
            "G" to "grab",
            "R" to "rotate",
            "KP 1 / 3 / 7" to "views",
            "KP 2/4/6/8" to "orbit_step",
            "Shift+KP 4/6" to "roll_step",
            "KP ." to "frame",
            "KP + / -" to "dolly_step",
            "Ctrl+Z" to "undo",
            "Ctrl+Y" to "redo",
            "Home" to "home",
            "N" to "toggle_panel",
            "Shift+`" to "fly",
            "\\ / Esc" to "close"
        )
    }
}
