package com.teamuhi.superbcamera.client

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.teamuhi.superbcamera.SuperbCamera
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraftforge.fml.loading.FMLPaths
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The part of the store that is written to disk. */
class CameraData(
    var perspectives: MutableList<Perspective> = ArrayList(),
    var lastUsedIndex: Int = 0,
    var panelSide: PanelSide = PanelSide.RIGHT,
    var uiSize: UiSize = UiSize.COMPACT,
    /** Alt + left mouse button stands in for the middle button (orbit / pan / dolly). */
    var emulateMiddleMouse: Boolean = true
)

/**
 * The saved perspectives plus the runtime selection. [activeIndex] is the perspective currently driving the camera
 * (-1 while a vanilla view is in use); it is never saved.
 *
 * Disk format: `config/superbcamera.json`, Gson. Values are clamped on load; a file that cannot be read is renamed to
 * `superbcamera.json.bak` and the defaults are used. Saved when the editor closes, on add / delete / rename and when
 * leaving a world, never per frame.
 */
object CameraStore {
    const val MAX_PERSPECTIVES = 9

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val file: Path get() = FMLPaths.CONFIGDIR.get().resolve("superbcamera.json")

    private var data = CameraData()

    /** Index into [perspectives] of the active custom perspective, or -1 for a vanilla view. Runtime only. */
    var activeIndex = -1
        private set

    val perspectives: List<Perspective> get() = data.perspectives

    val lastUsedIndex: Int get() = data.lastUsedIndex.coerceIn(0, (perspectives.size - 1).coerceAtLeast(0))

    var panelSide: PanelSide
        get() = data.panelSide
        set(value) {
            data.panelSide = value
        }

    var uiSize: UiSize
        get() = data.uiSize
        set(value) {
            data.uiSize = value
        }

    /** True when Alt + left mouse button emulates the middle button in the editor viewport. */
    var emulateMiddleMouse: Boolean
        get() = data.emulateMiddleMouse
        set(value) {
            data.emulateMiddleMouse = value
        }

    /** The perspective driving the camera, or null for a vanilla view. */
    val active: Perspective? get() = perspectives.getOrNull(activeIndex)

    val isFull: Boolean get() = perspectives.size >= MAX_PERSPECTIVES

    // ------------------------------------------------------------------------------------------------ disk

    /** Reads the file (once at startup). A missing file means defaults; an unreadable one is moved aside. */
    fun load() {
        val path = file
        if (!Files.exists(path)) return
        try {
            val parsed = Files.newBufferedReader(path, StandardCharsets.UTF_8).use { gson.fromJson(it, CameraData::class.java) }
                ?: throw IllegalStateException("empty file")
            // Gson leaves a missing list / enum as null even for non-null Kotlin properties.
            val rawList: List<Perspective?>? = parsed.perspectives
            val list = rawList.orEmpty().filterNotNull().take(MAX_PERSPECTIVES).toMutableList()
            list.forEachIndexed { index, perspective -> perspective.sanitize(index) }
            val side: PanelSide? = parsed.panelSide
            val size: UiSize? = parsed.uiSize
            data = CameraData(list, parsed.lastUsedIndex, side ?: PanelSide.RIGHT, size ?: UiSize.COMPACT, parsed.emulateMiddleMouse)
        } catch (e: Exception) {
            SuperbCamera.LOGGER.warn("Could not read {}, using defaults: {}", path, e.toString())
            moveAside(path)
            data = CameraData()
        }
    }

    /** Writes the file through a temporary file so a crash never leaves a half-written config. */
    fun save() {
        val path = file
        try {
            val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.newBufferedWriter(temp, StandardCharsets.UTF_8).use { gson.toJson(data, it) }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // IOException from the disk, or Gson refusing a value; either way the old file is left as it was.
            SuperbCamera.LOGGER.error("Could not save {}", path, e)
        }
    }

    private fun moveAside(path: Path) {
        try {
            Files.move(path, path.resolveSibling(path.fileName.toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            SuperbCamera.LOGGER.warn("Could not rename the unreadable config: {}", e.toString())
        }
    }

    // ------------------------------------------------------------------------------------------------ selection

    /**
     * Makes perspective [index] the active camera (the underlying vanilla view becomes third person back).
     * Returns false when the index is invalid.
     */
    fun activate(index: Int): Boolean {
        if (index !in perspectives.indices) return false
        activeIndex = index
        data.lastUsedIndex = index
        if (Minecraft.getInstance().options.cameraType != CameraType.THIRD_PERSON_BACK) {
            CameraController.switchCameraType(CameraType.THIRD_PERSON_BACK)
        }
        CameraController.resetSmoothing()
        return true
    }

    /** Back to a vanilla view; the perspectives stay saved. */
    fun deactivate() {
        if (activeIndex >= 0) data.lastUsedIndex = activeIndex
        activeIndex = -1
        CameraController.resetSmoothing()
    }

    /** Clears the runtime selection and cached entities (world left); saved data stays. */
    fun resetRuntime() {
        activeIndex = -1
        for (perspective in perspectives) {
            perspective.targetEntity = null
            perspective.targetUuid = null
            perspective.dimensionWarned = false
        }
        CameraController.resetSmoothing()
    }

    // ------------------------------------------------------------------------------------------------ editing

    /**
     * Appends a copy of [template] (or the defaults) as the next "Perspective N", saves and returns its index,
     * or -1 when the list is full.
     */
    fun add(template: Perspective?): Int {
        if (isFull) return -1
        val created = Perspective()
        if (template != null) created.copyFrom(template)
        created.name = uniqueName()
        created.sanitize(perspectives.size)
        data.perspectives.add(created)
        save()
        return data.perspectives.lastIndex
    }

    /**
     * Removes perspective [index] from the F5 cycle. The active index follows: an earlier removal shifts it down;
     * removing the active one moves to the next perspective (or the previous when it was last), or to a vanilla
     * view when none is left. Saves.
     */
    fun delete(index: Int) {
        if (index !in perspectives.indices) return
        data.perspectives.removeAt(index)
        when {
            data.perspectives.isEmpty() -> activeIndex = -1
            activeIndex == index -> activeIndex = index.coerceAtMost(data.perspectives.lastIndex)
            activeIndex > index -> activeIndex--
        }
        data.lastUsedIndex = data.lastUsedIndex.coerceIn(0, (data.perspectives.size - 1).coerceAtLeast(0))
        CameraController.resetSmoothing()
        save()
    }

    /**
     * Renames [perspective] (trimmed, 1-24 characters, blank becomes "Perspective N") and saves. Does nothing when
     * [perspective] is no longer in the list (deleted while its name was being edited).
     */
    fun rename(perspective: Perspective, raw: String) {
        if (perspectives.none { it === perspective }) return
        perspective.name = Perspective.cleanName(raw, perspectives.indexOf(perspective).coerceAtLeast(0))
        save()
    }

    /** The first "Perspective N" not used by another entry. */
    private fun uniqueName(): String {
        val taken = perspectives.map { it.name }.toSet()
        var n = perspectives.size + 1
        while ("Perspective $n" in taken) n++
        return "Perspective $n"
    }
}
