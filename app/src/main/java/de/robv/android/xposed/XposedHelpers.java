package de.robv.android.xposed;

import java.lang.reflect.Field;

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
    
    public static void findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        // 空壳，仅用于编译通过
    }
}
