package org.apache.commons.lang3.reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class MemberUtilsX {
    public static int compareConstructorFit(final Constructor<?> left, final Constructor<?> right, final Class<?>[] actual) {
        return MemberUtils.compareConstructorFit(left, right, actual);
    }

    public static int compareMethodFit(final Method left, final Method right, final Class<?>[] actual) {
        return MemberUtils.compareMethodFit(left, right, actual);
    }
}
