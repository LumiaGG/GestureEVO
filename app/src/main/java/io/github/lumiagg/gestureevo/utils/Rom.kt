package io.github.lumiagg.gestureevo.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import io.github.lumiagg.gestureevo.settings.FreeFormSettingsActivity.Companion.KEY_FREEFORM_CODE
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object Rom {
    private const val TAG = "Rom"
    private const val ROM_NAME = "ROM_NAME"
    private const val ROM_VERSION = "ROM_VERSION"
    private const val ROM_FLYME = "FLYME"
    private const val ROM_QIKU = "QIKU"
    private const val HARMONY_OS = "harmony"
    private const val KEY_VERSION_MIUI = "ro.miui.ui.version.name"
    private const val KEY_VERSION_EMUI = "ro.build.version.emui"
    private const val KEY_VERSION_OPPO = "ro.build.version.opporom"
    private const val KEY_VERSION_SMARTISAN = "ro.smartisan.version"
    private const val KEY_VERSION_VIVO = "ro.vivo.os.version"
    private const val KEY_VIVO_OS_NAME = "ro.vivo.os.name"
    private const val KEY_VIVO_OS_DISPLAY_ID = "ro.vivo.os.build.display.id"

    // In-memory system property cache to avoid inter-process / shell execution overhead on repeated reads.
    private val propCache = ConcurrentHashMap<String, String>()

    // Cached basic hardware characteristics (immutable over app lifecycle).
    private val manufacturer by lazy { Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT) }
    private val brand by lazy { Build.BRAND.orEmpty().lowercase(Locale.ROOT) }

    val isXiaomiDeviceCached: Boolean by lazy {
        manufacturer.contains("xiaomi")
                || brand.contains("xiaomi")
                || brand.contains("redmi")
                || brand.contains("poco")
    }

    val isOnePlusDeviceCached: Boolean by lazy {
        manufacturer.contains("oneplus") || brand.contains("oneplus")
    }

    val isOppoDeviceCached: Boolean by lazy {
        manufacturer.contains("oppo") || brand.contains("oppo")
    }

    val isRealmeDeviceCached: Boolean by lazy {
        manufacturer.contains("realme")
                || brand.contains("realme")
                || manufacturer.contains("oppo")
                || brand.contains("oppo")
    }

    val isOriginOSCached: Boolean by lazy {
        listOf(KEY_VIVO_OS_NAME, KEY_VIVO_OS_DISPLAY_ID)
            .any { getProp(it).contains("origin", ignoreCase = true) }
    }

    private val isHarmonyOS: Boolean by lazy {
        try {
            val clz = Class.forName("com.huawei.system.BuildEx")
            val method = clz.getMethod("getOsBrand")
            HARMONY_OS.equals(method.invoke(clz) as? String, ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    // Optional OEM detection; unavailable reflection falls back to the existing getprop reader.
    @get:SuppressLint("PrivateApi")
    private val systemPropertiesGetMethod by lazy {
        try {
            val clz = Class.forName("android.os.SystemProperties")
            clz.getMethod("get", String::class.java, String::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    data class CheckRomRet(val type: RomType, val version: Int) {
        fun isXiaomi(): Boolean = type == RomType.MIUI || isXiaomiDeviceCached
        fun isMiui13Plus(): Boolean = isXiaomi() && (version >= 13 || version >= 130 || version == -1)
        fun isHuawei(): Boolean = type == RomType.HARMONY_OS || type == RomType.EMUI
        fun isOriginOS(): Boolean = isOriginOSCached
        fun isOppoFamily(): Boolean = isOppoDeviceCached || isRealmeDeviceCached || isOnePlusDeviceCached || type == RomType.OPPO
        fun defaultFreeFormCode(): Int = when (type) {
            RomType.FLYME -> RomFreeFormCode.FLYME.code
            RomType.EMUI, RomType.HARMONY_OS -> RomFreeFormCode.EMUI.code
            RomType.OPPO -> RomFreeFormCode.OPPO.code
            RomType.VIVO -> RomFreeFormCode.VIVO.code
            else -> RomFreeFormCode.DEFAULT.code
        }
    }

    enum class RomType {
        MIUI, EMUI, FLYME, OPPO, SMARTISAN, VIVO, QIKU, HARMONY_OS, UNKNOWN
    }

    enum class RomFreeFormCode(val code: Int) {
        NUBIA(-2),        // Nubia / RedMagic launches freeform via Intent identifier rather than window mode code
        DEFAULT(5),       // Other unknown ROMs
        FLYME(11),
        OPPO(100),
        EMUI(102),
        HARMONY_OS(102),
        VIVO(106)
    }

    @Volatile
    private var cachedRomRet: CheckRomRet? = null

    fun isPad(context: Context): Boolean {
        return ((context.resources.configuration.screenLayout
                and Configuration.SCREENLAYOUT_SIZE_MASK)
                >= Configuration.SCREENLAYOUT_SIZE_LARGE)
    }

    suspend fun checkRom(context: Context): CheckRomRet {
        cachedRomRet?.let { return it }
        val romTypeStr = context.settingsStore.readKey(ROM_NAME, "")
        val result = if (romTypeStr.isEmpty()) {
            val parsed = parseProp()
            context.settingsStore.saveKey(ROM_NAME, parsed.type.name)
            context.settingsStore.saveKey(ROM_VERSION, parsed.version)
            parsed
        } else {
            try {
                CheckRomRet(RomType.valueOf(romTypeStr), context.settingsStore.readKey(ROM_VERSION, -1))
            } catch (e: Exception) {
                e.printStackTrace()
                val parsed = parseProp()
                context.settingsStore.saveKey(ROM_NAME, parsed.type.name)
                context.settingsStore.saveKey(ROM_VERSION, parsed.version)
                parsed
            }
        }
        cachedRomRet = result
        return result
    }

    fun isXiaomiDevice(): Boolean = isXiaomiDeviceCached
    fun isOnePlusDevice(): Boolean = isOnePlusDeviceCached
    fun isRealmeDevice(): Boolean = isRealmeDeviceCached
    fun isOppoDevice(): Boolean = isOppoDeviceCached
    fun isOriginOS(): Boolean = isOriginOSCached

    suspend fun isMiui13Plus(context: Context): Boolean = checkRom(context).isMiui13Plus()
    suspend fun isHuawei(context: Context): Boolean = checkRom(context).isHuawei()

    suspend fun getFreeFormCode(context: Context): Int {
        val code = context.settingsStore.readKey(KEY_FREEFORM_CODE, -1)
        if (code != -1) return code
        return checkRom(context).defaultFreeFormCode()
    }

    /**
     * Adaptively calculates the system scaling compensation factor for MIUI freeform windows
     * based on physical display characteristics and device form factor:
     * - Portrait: all devices use the standard 0.70x tier (compensation 1.42f).
     * - Landscape:
     *   1. Tablet / unfolded foldable (aspect ratio < 1.6, min dimension >= 1600, or isPad):
     *      Vertical space is sufficient; MIUI maintains 1.42f.
     *   2. Standard narrow phone: vertical space is heavily compressed; MIUI downscales to 0.55x (compensation 1.8f).
     */
    fun calculateMiuiCompensation(
        context: Context,
        screenWidth: Int,
        screenHeight: Int,
        isPortrait: Boolean
    ): Float {
        if (isPortrait) return 1.42f
        val minD = minOf(screenWidth, screenHeight)
        val maxD = maxOf(screenWidth, screenHeight)
        val aspectRatio = maxD.toFloat() / minD.toFloat()
        val isLargeScreen = isPad(context) || aspectRatio < 1.6f || minD >= 1600
        return if (isLargeScreen) 1.42f else 1.8f
    }

    private fun getVersion(prop: String): Int {
        try {
            val matcher = Pattern.compile("\\d+").matcher(prop)
            if (matcher.find()) {
                return matcher.group().toInt()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return -1
    }

    private fun parseProp(): CheckRomRet {
        if (isHarmonyOS) {
            return CheckRomRet(RomType.HARMONY_OS, -1)
        }

        var prop = getProp(KEY_VERSION_MIUI)
        if (prop.isNotEmpty()) return CheckRomRet(RomType.MIUI, getVersion(prop))

        prop = getProp(KEY_VERSION_EMUI)
        if (prop.isNotEmpty()) return CheckRomRet(RomType.EMUI, getVersion(prop))

        prop = getProp(KEY_VERSION_OPPO)
        if (prop.isNotEmpty()) return CheckRomRet(RomType.OPPO, getVersion(prop))
        if (isRealmeDeviceCached) return CheckRomRet(RomType.OPPO, -1)
        if (isOppoDeviceCached) return CheckRomRet(RomType.OPPO, -1)
        if (isOnePlusDeviceCached) return CheckRomRet(RomType.OPPO, -1)

        prop = getProp(KEY_VERSION_VIVO)
        if (prop.isNotEmpty()) return CheckRomRet(RomType.VIVO, getVersion(prop))

        prop = getProp(KEY_VERSION_SMARTISAN)
        if (prop.isNotEmpty()) return CheckRomRet(RomType.SMARTISAN, getVersion(prop))

        if (Build.DISPLAY.uppercase(Locale.getDefault()).contains(ROM_FLYME)) {
            return CheckRomRet(RomType.FLYME, -1)
        }
        if (manufacturer == "360" || manufacturer == ROM_QIKU.lowercase(Locale.ROOT)) {
            return CheckRomRet(RomType.QIKU, -1)
        }
        return CheckRomRet(RomType.UNKNOWN, -1)
    }

    private fun getProp(name: String): String {
        return propCache.getOrPut(name) {
            val reflected = try {
                systemPropertiesGetMethod?.invoke(null, name, "") as? String
            } catch (_: Throwable) {
                null
            }
            if (!reflected.isNullOrBlank()) {
                reflected
            } else {
                getPropByExec(name)
            }
        }
    }

    private fun getPropByExec(name: String): String {
        var line = ""
        var input: BufferedReader? = null
        try {
            val p = Runtime.getRuntime().exec("getprop $name")
            input = BufferedReader(InputStreamReader(p.inputStream), 1024)
            line = input.readLine().orEmpty()
        } catch (ex: IOException) {
            Log.e(TAG, "Unable to read prop $name", ex)
        } finally {
            if (input != null) {
                try {
                    input.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        return line
    }
}
