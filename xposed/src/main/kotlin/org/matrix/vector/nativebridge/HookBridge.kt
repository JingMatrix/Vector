package org.matrix.vector.nativebridge

import dalvik.annotation.optimization.FastNative
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

object HookBridge {
    @JvmStatic
    external fun hookMethod(
        useModernApi: Boolean,
        hookMethod: Executable,
        hooker: Class<*>,
        priority: Int,
        callback: Any?,
    ): Boolean

    @JvmStatic
    external fun unhookMethod(
        useModernApi: Boolean,
        hookMethod: Executable,
        callback: Any?,
    ): Boolean

    @JvmStatic external fun deoptimizeMethod(method: Executable): Boolean

    @JvmStatic
    @Throws(InstantiationException::class)
    external fun <T> allocateObject(clazz: Class<T>): T

    @JvmStatic
    @Throws(
        IllegalAccessException::class,
        IllegalArgumentException::class,
        InvocationTargetException::class,
    )
    external fun invokeOriginalMethod(method: Executable, thisObject: Any?, vararg args: Any?): Any?

    @JvmStatic
    @Throws(
        IllegalAccessException::class,
        IllegalArgumentException::class,
        InvocationTargetException::class,
    )
    external fun <T> invokeSpecialMethod(
        method: Executable,
        shorty: CharArray,
        clazz: Class<T>,
        thisObject: Any?,
        vararg args: Any?,
    ): Any?

    @JvmStatic @FastNative external fun instanceOf(obj: Any?, clazz: Class<*>): Boolean

    @JvmStatic @FastNative external fun setTrusted(cookie: Any?): Boolean

    @JvmStatic
    external fun callbackSnapshot(hooker_callback: Class<*>, method: Executable): Array<Array<Any?>>

    @JvmStatic external fun getStaticInitializer(clazz: Class<*>): Method?
}
