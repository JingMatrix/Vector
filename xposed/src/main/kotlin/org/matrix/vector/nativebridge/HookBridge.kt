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
    // Takes the array rather than a vararg: a Kotlin spread copies it through Arrays.copyOf, and
    // this runs on every dispatch of every hooked method. The JVM descriptor is unchanged, so the
    // native registration still matches.
    external fun invokeOriginalMethod(
        method: Executable,
        thisObject: Any?,
        args: Array<out Any?>,
    ): Any?

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
        args: Array<out Any?>,
    ): Any?

    /**
     * Lowers the dispatch guard for the duration of a call into module code, and returns the depth
     * to hand back to [resumeDispatch].
     *
     * While the guard is raised, a hooked method entered from the framework's own frames runs its
     * original instead of dispatching again. That is what keeps the dispatch from re-entering
     * itself when a module hooks something the dispatch happens to call — including calls no one
     * wrote, such as the Object.getClass() that R8 compiles Kotlin's null checks into. Hookers and
     * the original method are entitled to a full dispatch, so the guard comes down around them.
     */
    @JvmStatic @FastNative external fun suspendDispatch(): Int

    /** Restores the depth returned by [suspendDispatch] or [raiseDispatch]. */
    @JvmStatic @FastNative external fun resumeDispatch(depth: Int)

    /**
     * Raises the guard over a stretch of framework code, returning the depth to hand back to
     * [resumeDispatch].
     *
     * The chain is re-entered from module code with the guard already down, so it cannot assume it
     * holds. Building one chain node calls getClass twice — R8's null checks on the constructor's
     * parameters — and each of those would otherwise dispatch, build another node, and call
     * getClass again.
     */
    @JvmStatic @FastNative external fun raiseDispatch(): Int

    @JvmStatic @FastNative external fun instanceOf(obj: Any?, clazz: Class<*>): Boolean

    @JvmStatic @FastNative external fun setTrusted(cookie: Any?): Boolean

    /** Returns null when [method] carries no hooks at all. */
    @JvmStatic
    external fun callbackSnapshot(
        hooker_callback: Class<*>,
        method: Executable,
    ): Array<Array<Any?>>?

    /**
     * Locates a class's static initializer without initializing it.
     * [artMethods] must be the ArtMethod addresses of the class's declared constructors and
     * methods, which reflection can supply without triggering initialization, and [artMethodSize]
     * the size of one ArtMethod. One member is enough, which matters because a class whose only
     * members are the static initializer and an implicit constructor shows just one to reflection.
     *
     * Returns null when the class has no static initializer or the method layout is not the one
     * this relies on.
     */
    @JvmStatic
    external fun findStaticInitializer(
        clazz: Class<*>,
        artMethods: LongArray,
        artMethodSize: Long,
    ): Executable?
}
