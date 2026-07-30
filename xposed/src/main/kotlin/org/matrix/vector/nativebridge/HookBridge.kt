package org.matrix.vector.nativebridge

import dalvik.annotation.optimization.FastNative
import java.lang.reflect.Executable
import java.lang.reflect.Field
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

    /**
     * Clears the final flag ART reads, so that reflection will write [field] again.
     *
     * Android 17 refuses every reflective write to a static final field however accessible the
     * [Field] is, and clearing the reflective copy's flag does not help because the check reads
     * the ArtField. The write itself stays with reflection, which keeps its conversions and its
     * exceptions; this only stops it being refused.
     *
     * [modifiers] must be `field.modifiers`. It is checked against the flags about to be written,
     * so a runtime that lays an ArtField out differently is left alone rather than corrupted.
     *
     * Returns false when the field is still final, which is the caller's cue to report the
     * failure it already had. The field stays writable afterwards.
     */
    @JvmStatic external fun makeFieldWritable(field: Field, modifiers: Int): Boolean

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
