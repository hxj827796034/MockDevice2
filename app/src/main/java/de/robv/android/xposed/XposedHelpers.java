package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class XposedHelpers {
    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            return null;
        }
    }
    
    // 修复：参数改成 (Class<?> clazz, String methodName, Object... parameterTypesAndCallback)
    public static void findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        // 空壳方法，只用于编译通过，运行时由 LSPosed 接管
    }
}
