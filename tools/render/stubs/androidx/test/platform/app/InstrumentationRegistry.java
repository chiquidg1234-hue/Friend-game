package androidx.test.platform.app;
public final class InstrumentationRegistry {
  private static android.app.Instrumentation instrumentation;
  public static void registerInstance(android.app.Instrumentation i, android.os.Bundle b) { instrumentation = i; }
  public static android.app.Instrumentation getInstrumentation() { return instrumentation; }
}
