package androidx.test.internal.runner.lifecycle;
import androidx.test.runner.lifecycle.*;
public class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
  public ActivityLifecycleMonitorImpl() {}
  public void signalLifecycleChange(Stage s, android.app.Activity a) {}
  public void addLifecycleCallback(ActivityLifecycleCallback c) {}
  public void removeLifecycleCallback(ActivityLifecycleCallback c) {}
  public java.util.Collection<android.app.Activity> getActivitiesInStage(Stage s) { return java.util.Collections.emptyList(); }
  public Stage getLifecycleStageOf(android.app.Activity a) { return Stage.DESTROYED; }
}
