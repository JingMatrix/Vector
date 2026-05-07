package org.matrix.vector.nativebridge

import android.content.res.Resources
import dalvik.annotation.optimization.FastNative

object ResourcesHook {
    @JvmStatic external fun initXResourcesNative(): Boolean

    @JvmStatic external fun makeInheritable(clazz: Class<*>): Boolean

    @JvmStatic
    external fun buildDummyClassLoader(
        parent: ClassLoader,
        resourceSuperClass: String,
        typedArraySuperClass: String,
    ): ClassLoader

    @JvmStatic
    @FastNative
    external fun rewriteXmlReferencesNative(parserPtr: Long, origRes: Any, repRes: Resources)
}
