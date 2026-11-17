# Custom Vulkan drivers on Mali (PanVK)

Added 2026-11-16 in the `faux123/WatermelonDS` fork.

## What and why

WatermelonDS could load a custom Vulkan driver package only on Adreno GPUs. On a Mali device the
**Vulkan driver** settings group was hidden, and an import failed with "Custom drivers require an
Adreno GPU".

Mesa's PanVK runs on the stock `mali_kbase` kernel driver of several Mali handhelds and on the
Pixel 7, and those builds ship in the same `.adpkg.zip` format as Turnip (a `meta.json` plus
`libvulkan_panfrost.so`). This change lets WatermelonDS import, select, and load them on Mali.

Success criteria:

1. On a Mali device, the **Vulkan driver** group is shown and accepts a PanVK package.
2. A game started afterward creates its Vulkan device on PanVK, not on the Arm blob.
3. A Turnip package is refused on a Mali device, and a PanVK package on an Adreno device, with a
   message that names both GPU families.
4. Nothing changes for Adreno devices.

Not in scope: PanVK-specific renderer workarounds, a download source for Mali drivers, and a
screen for `PANVK_*` environment variables.

## How

The native loader was never Adreno-specific. `VulkanDispatch.cpp` in `melonDS-android-lib` calls
`adrenotools_open_libvulkan(..., ADRENOTOOLS_DRIVER_CUSTOM, ...)`, which only redirects Android's
Vulkan loader to another library. Only the Kotlin gate tested for an Adreno GPU, so no native
code changed.

| Piece | File | Change |
|---|---|---|
| GPU family | `impl/AdrenoVulkanDriverSupport.kt` | `gpuFamily`: Adreno when `/sys/class/kgsl/kgsl-3d0/gpu_model` starts with "Adreno", Mali when `/dev/mali0` exists. `isSupported` accepts either family. `isAdrenoSupported` is the old Adreno-only test. |
| Wrong-family check | same file | `familyOfLibrary` reads the family from the driver library's file name, by whole tokens (`freedreno`, `turnip`, `adreno`; `panfrost`, `panvk`, `mali`). A neutral name passes. `wrongFamily` and `isRunnableHere` build on it. |
| Import | `impl/AdrenoVulkanDriverManager.kt` | New `ImportException.Reason.WrongGpu` with the package's family. `libvulkan_panfrost.so` joins the preferred library names. A package that carries libraries for both families picks the one for this GPU. |
| Import path fix | same file | Upstream bug: the stored `driverDir` was computed between a canonical path (`/data/data/...`) and `/data/user/0/...`, so it became a chain of `../` into the deleted `pending-<id>` dir. Both sides are now canonical, and the path is checked before the rename. |
| Remove fix | same file | Upstream bug: `removeDriver` deleted the parent of `driverDir`, which is the whole `adreno-drivers` store when the library sits at the zip root. It now deletes only `driver-<id>`. |
| Launch | `impl/SharedPreferencesSettingsRepository.kt` | `getVulkanDriverConfiguration` uses the system driver when the selected package is for the other family (for example, settings restored from another device) and logs `Not loading <lib>` under the `VulkanDriver` tag. |
| Freedreno screen | `impl/FreedrenoSettings.kt`, `VideoPreferencesFragment.kt` | The `FD_*`/`TU_DEBUG` variables and their screen stay Adreno-only; the entry is hidden on Mali. |
| Strings | `values`, `values-b+es`, `values-b+zh+Hans` | The mode title is now "Vulkan driver", the unsupported message names Adreno or Mali, and `video_vulkan_driver_import_wrong_gpu` is new. |
| Tests | `app/src/test/.../impl/GpuFamilyTest.kt` | Family detection, library-name parsing, and the wrong-family rule. |

## Data flow

```
Import custom driver -> AdrenoVulkanDriverManager.importDriver -> meta.json, library pick,
                        wrong-family check -> files/adreno-drivers/driver-<id>/
select               -> video_vulkan_selected_driver_id, mode CUSTOM
game start           -> getVulkanDriverConfiguration (family check again)
                     -> configureVulkanDriverNative -> VulkanDispatch -> adrenotools_open_libvulkan
                     -> libvulkan_panfrost.so -> /dev/mali0 (kbase)
```

## Known limits

- The family comes from the library file name. A package with a neutral name is not refused; if
  it is the wrong driver it fails at load and `VulkanDispatch` falls back to the system driver.
- Only the GitHub flavor builds libadrenotools. The Play Store flavor is unchanged.
