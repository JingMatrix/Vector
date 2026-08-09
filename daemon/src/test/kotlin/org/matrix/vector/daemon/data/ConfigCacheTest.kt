package org.matrix.vector.daemon.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.vector.ipc.LoadedModule

class ConfigCacheTest {

  @Test
  fun onlyReplacedModuleAppIdsAreInvalidated() {
    val unchanged = LoadedModule().apply { appId = 10001 }
    val oldChanged = LoadedModule().apply { appId = 10002 }
    val newChanged = LoadedModule().apply { appId = 10002 }
    val added = LoadedModule().apply { appId = 10003 }

    val oldModules = mapOf("unchanged" to unchanged, "changed" to oldChanged)
    val newModules =
        mapOf("unchanged" to unchanged, "changed" to newChanged, "added" to added)

    assertEquals(setOf(10002, 10003), moduleGenerationAppIds(oldModules, newModules))
  }

  @Test
  fun removedModuleAppIdIsInvalidated() {
    val removed = LoadedModule().apply { appId = 10004 }

    assertEquals(
        setOf(10004),
        moduleGenerationAppIds(mapOf("removed" to removed), emptyMap()),
    )
  }
}
