package de.robv.android.xposed;

public abstract class XC_MethodHook {
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;

        public void setResult(Object result) { this.result = result; }
        public Object getResult() { return result; }
        public void setThrowable(Throwable throwable) { this.throwable = throwable; }
        public Throwable getThrowable() { return throwable; }
    }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
