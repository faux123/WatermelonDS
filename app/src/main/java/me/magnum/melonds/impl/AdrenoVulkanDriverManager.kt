package me.magnum.melonds.impl

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import me.magnum.melonds.domain.model.VulkanDriverMode
import me.magnum.melonds.domain.repositories.SettingsRepository
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

class AdrenoVulkanDriverManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    data class ImportResult(val id: String, val displayName: String)

    private data class DriverMetadata(
        val name: String?,
        val libraryName: String?,
    )

    class ImportException(
        val reason: Reason,
        // Set for WrongGpu: the GPU family the package's library was built for.
        val driverFamily: GpuFamily? = null,
    ) : Exception(reason.name) {
        enum class Reason {
            WrongGpu,
            UnsupportedBuild,
            NotZip,
            InvalidZip,
            NoDriver,
            AmbiguousDriver,
        }
    }

    val isSupported: Boolean
        get() = AdrenoVulkanDriverSupport.isSupported(context)

    fun importDriver(uri: Uri): ImportResult {
        if (!isSupported) {
            throw ImportException(ImportException.Reason.UnsupportedBuild)
        }

        val displayName = queryDisplayName(uri)
            ?.takeIf { it.isNotBlank() }
            ?: "Custom Vulkan driver"
        if (!displayName.endsWith(".zip", ignoreCase = true)) {
            throw ImportException(ImportException.Reason.NotZip)
        }

        val driverId = UUID.randomUUID().toString()
        val importRoot = File(context.filesDir, "adreno-drivers")
        val pendingRoot = File(importRoot, "pending-$driverId")
        val installedRoot = File(importRoot, "driver-$driverId")

        try {
            pendingRoot.mkdirs()
            extractZip(uri, pendingRoot)
            val metadata = readDriverMetadata(pendingRoot)
            val driverFile = selectDriverFile(pendingRoot, metadata)
            val driverDir = driverFile.parentFile ?: throw ImportException(ImportException.Reason.NoDriver)
            AdrenoVulkanDriverSupport.wrongFamily(driverFile.name, AdrenoVulkanDriverSupport.gpuFamily)?.let {
                throw ImportException(ImportException.Reason.WrongGpu, driverFamily = it)
            }
            val importedDisplayName = metadata.name
                ?.takeIf { it.isNotBlank() }
                ?: displayName.removeSuffix(".zip")

            // selectDriverFile can return a canonical path (/data/data/...) while pendingRoot is
            // /data/user/0/..., which made this a chain of "../" into the deleted pending dir.
            // Computed before the rename so a failure leaves nothing under installedRoot.
            val driverDirRelativePath = driverDir.canonicalFile.toRelativeString(pendingRoot.canonicalFile)
            if (driverDirRelativePath.startsWith("..")) {
                throw ImportException(ImportException.Reason.InvalidZip)
            }

            installedRoot.deleteRecursively()
            if (!pendingRoot.renameTo(installedRoot)) {
                throw ImportException(ImportException.Reason.InvalidZip)
            }
            val currentDriverDir = if (driverDirRelativePath == ".") {
                installedRoot
            } else {
                File(installedRoot, driverDirRelativePath)
            }
            settingsRepository.setCustomVulkanDriver(
                id = driverId,
                driverDir = currentDriverDir.absolutePath,
                driverName = driverFile.name,
                displayName = importedDisplayName,
            )
            settingsRepository.setVulkanDriverMode(VulkanDriverMode.CUSTOM)
            return ImportResult(driverId, importedDisplayName)
        } catch (e: ImportException) {
            pendingRoot.deleteRecursively()
            throw e
        } catch (e: Exception) {
            pendingRoot.deleteRecursively()
            throw ImportException(ImportException.Reason.InvalidZip)
        }
    }

    fun removeDriver(id: String) {
        // Delete the package's own install dir. The old code deleted driverDir's parent, which is
        // the whole adreno-drivers store when the library sits at the zip root (driverDir ==
        // installedRoot), so removing one driver removed them all.
        if (runCatching { UUID.fromString(id) }.isSuccess) {
            File(File(context.filesDir, "adreno-drivers"), "driver-$id").deleteRecursively()
        }
        settingsRepository.removeCustomVulkanDriver(id)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun extractZip(uri: Uri, destination: File) {
        val destinationCanonical = destination.canonicalFile
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val outputFile = File(destinationCanonical, entry.name).canonicalFile
                    if (!outputFile.path.startsWith(destinationCanonical.path + File.separator)) {
                        throw ImportException(ImportException.Reason.InvalidZip)
                    }

                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        outputFile.outputStream().use { output ->
                            zip.copyTo(output)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: throw ImportException(ImportException.Reason.InvalidZip)
    }

    private fun readDriverMetadata(root: File): DriverMetadata {
        val metadataFile = File(root, "meta.json")
        if (!metadataFile.isFile) {
            return DriverMetadata(name = null, libraryName = null)
        }

        return runCatching {
            val json = JSONObject(metadataFile.readText())
            DriverMetadata(
                name = json.optString("name").takeIf { it.isNotBlank() },
                libraryName = json.optString("libraryName").takeIf { it.isNotBlank() },
            )
        }.getOrDefault(DriverMetadata(name = null, libraryName = null))
    }

    private fun selectDriverFile(root: File, metadata: DriverMetadata): File {
        val allSoFiles = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("so", ignoreCase = true) }
            .toList()
        // A package carrying both Turnip and PanVK must pick the one for this GPU. If none fits,
        // keep them all so the caller reports WrongGpu instead of NoDriver.
        val soFiles = allSoFiles.filter { AdrenoVulkanDriverSupport.isRunnableHere(it.name) }
            .ifEmpty { allSoFiles }

        metadata.libraryName?.let { libraryName ->
            val metadataDriver = File(root, libraryName).canonicalFile
            val rootCanonical = root.canonicalFile
            if (!metadataDriver.path.startsWith(rootCanonical.path + File.separator)) {
                throw ImportException(ImportException.Reason.InvalidZip)
            }
            if (metadataDriver.isFile && metadataDriver.extension.equals("so", ignoreCase = true)) {
                return metadataDriver
            }
        }

        listOf("libvulkan_freedreno.so", "vulkan.adreno.so", "libvulkan_panfrost.so").forEach { preferredName ->
            val matches = soFiles.filter { it.name.equals(preferredName, ignoreCase = true) }
            if (matches.size == 1) {
                return matches.first()
            }
            if (matches.size > 1) {
                throw ImportException(ImportException.Reason.AmbiguousDriver)
            }
        }

        val genericMatches = soFiles.filter { file ->
            file.name.startsWith("libvulkan", ignoreCase = true) ||
                (file.name.startsWith("vulkan.", ignoreCase = true) && file.name.endsWith(".so", ignoreCase = true))
        }
        return when (genericMatches.size) {
            1 -> genericMatches.first()
            0 -> throw ImportException(ImportException.Reason.NoDriver)
            else -> throw ImportException(ImportException.Reason.AmbiguousDriver)
        }
    }
}
