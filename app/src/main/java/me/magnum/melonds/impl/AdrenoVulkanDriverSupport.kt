package me.magnum.melonds.impl

import android.content.Context
import android.os.Build
import me.magnum.melonds.R
import java.io.File

enum class GpuFamily(val displayName: String) {
    ADRENO("Qualcomm Adreno"),
    MALI("Arm Mali"),
}

// The name is historical: custom drivers load on Adreno (Turnip) and on Mali (PanVK). The
// adrenotools loader only redirects Android's Vulkan loader to another library, so it is not
// Adreno-specific; only this gate was.
object AdrenoVulkanDriverSupport {
    private val gpuModel: String? by lazy {
        runCatching {
            File("/sys/class/kgsl/kgsl-3d0/gpu_model").bufferedReader().use { it.readLine() }
        }.getOrNull()
    }

    // mali_kbase exposes /dev/mali0 to apps, since the vendor Vulkan driver opens it in-process.
    private val hasMaliDevice: Boolean by lazy {
        runCatching { File("/dev/mali0").exists() }.getOrDefault(false)
    }

    val gpuFamily: GpuFamily? by lazy { detectFamily(gpuModel, hasMaliDevice) }

    fun isSupported(context: Context): Boolean {
        return isLoaderAvailable(
            context.resources.getBoolean(R.bool.adrenotools_enabled),
            Build.VERSION.SDK_INT,
            Build.SUPPORTED_64_BIT_ABIS,
        ) && gpuFamily != null
    }

    // Freedreno/Turnip environment variables mean nothing to PanVK, so their screen stays Adreno-only.
    fun isAdrenoSupported(context: Context): Boolean {
        return isSupported(context) && gpuFamily == GpuFamily.ADRENO
    }

    internal fun detectFamily(gpuModel: String?, hasMaliDevice: Boolean): GpuFamily? {
        return when {
            isAdrenoGpu(gpuModel) -> GpuFamily.ADRENO
            hasMaliDevice -> GpuFamily.MALI
            else -> null
        }
    }

    internal fun isAdrenoGpu(gpuModel: String?): Boolean {
        return gpuModel?.trim()?.startsWith("Adreno", ignoreCase = true) == true
    }

    internal fun isLoaderAvailable(
        buildEnabled: Boolean,
        sdkInt: Int,
        supported64BitAbis: Array<String>,
    ): Boolean {
        return buildEnabled &&
            sdkInt >= Build.VERSION_CODES.P &&
            supported64BitAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
    }

    // Mesa names its libraries after the driver (libvulkan_freedreno.so, libvulkan_panfrost.so),
    // and Qualcomm's blob is vulkan.adreno.so. A neutral name returns null and is not refused;
    // if it is the wrong driver it fails at load and the native side falls back to the system driver.
    internal fun familyOfLibrary(libraryName: String?): GpuFamily? {
        // Whole tokens only: a substring test would read "normalize" as Mali.
        val tokens = libraryName?.substringAfterLast('/')?.lowercase()
            ?.split('_', '.', '-')
            ?.toSet()
            ?: return null
        val adreno = tokens.any { it in ADRENO_TOKENS }
        val mali = tokens.any { it in MALI_TOKENS }
        return when {
            adreno && !mali -> GpuFamily.ADRENO
            mali && !adreno -> GpuFamily.MALI
            else -> null
        }
    }

    // Returns the family the library was built for when it cannot run on deviceFamily, else null.
    internal fun wrongFamily(libraryName: String?, deviceFamily: GpuFamily?): GpuFamily? {
        val libraryFamily = familyOfLibrary(libraryName) ?: return null
        if (deviceFamily == null || libraryFamily == deviceFamily) return null
        return libraryFamily
    }

    fun isRunnableHere(libraryName: String?): Boolean = wrongFamily(libraryName, gpuFamily) == null

    private val ADRENO_TOKENS = setOf("freedreno", "turnip", "adreno")
    private val MALI_TOKENS = setOf("panfrost", "panvk", "mali")
}
