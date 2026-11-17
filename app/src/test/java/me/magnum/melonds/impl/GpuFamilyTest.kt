package me.magnum.melonds.impl

import me.magnum.melonds.impl.AdrenoVulkanDriverSupport.detectFamily
import me.magnum.melonds.impl.AdrenoVulkanDriverSupport.familyOfLibrary
import me.magnum.melonds.impl.AdrenoVulkanDriverSupport.wrongFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuFamilyTest {
    @Test
    fun detectsFamilyFromKgslModelOrMaliNode() {
        assertEquals(GpuFamily.ADRENO, detectFamily("Adreno830v2", hasMaliDevice = false))
        assertEquals(GpuFamily.MALI, detectFamily(null, hasMaliDevice = true))
        assertNull(detectFamily(null, hasMaliDevice = false))
        assertNull(detectFamily("PowerVR", hasMaliDevice = false))
    }

    @Test
    fun readsFamilyFromDriverLibraryName() {
        assertEquals(GpuFamily.ADRENO, familyOfLibrary("libvulkan_freedreno.so"))
        assertEquals(GpuFamily.ADRENO, familyOfLibrary("vulkan.adreno.so"))
        assertEquals(GpuFamily.MALI, familyOfLibrary("libvulkan_panfrost.so"))
        assertEquals(GpuFamily.MALI, familyOfLibrary("drivers/libvulkan_panfrost.so"))
        assertNull(familyOfLibrary("libvulkan_custom.so"))
        // "normalize" contains "mali"; only whole tokens may count.
        assertNull(familyOfLibrary("libvulkan_normalize.so"))
        // A name that claims both families is neutral, not refused.
        assertNull(familyOfLibrary("libvulkan_freedreno_mali.so"))
        assertNull(familyOfLibrary(null))
    }

    @Test
    fun refusesOnlyTheOtherFamily() {
        assertEquals(GpuFamily.ADRENO, wrongFamily("libvulkan_freedreno.so", GpuFamily.MALI))
        assertEquals(GpuFamily.MALI, wrongFamily("libvulkan_panfrost.so", GpuFamily.ADRENO))
        assertNull(wrongFamily("libvulkan_panfrost.so", GpuFamily.MALI))
        assertNull(wrongFamily("libvulkan_freedreno.so", GpuFamily.ADRENO))
        assertNull(wrongFamily("libvulkan_custom.so", GpuFamily.MALI))
    }
}
