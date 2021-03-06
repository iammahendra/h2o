package water;

import java.util.Arrays;
import java.util.UUID;

import water.api.Constants;

public class Job extends Iced {
  // Global LIST of Jobs key.
  static final Key LIST = Key.make(Constants.BUILT_IN_KEY_JOBS, (byte) 0, Key.BUILT_IN_KEY);
  private static final int KEEP_LAST_COUNT = 100;
  public static final long CANCELLED_END_TIME = -1;

  // Per-job fields
  public final Key    _self; // Boolean read-only value; exists==>running, not-exists==>canceled/removed
  public final Key    _dest; // Key holding final value after job is removed
  public final String _description;
  public final long   _startTime;
  public long         _endTime;

  public Key self() { return _self; }
  public Key dest() { return _dest; }

  public interface Progress {
    float progress();
  }

  public static class Fail extends Iced {
    public final String _message;
    public Fail(String message) { _message = message; }
  }

  static final class List extends Iced {
    Job[] _jobs = new Job[0];
  }

  public static Job[] all() {
    List list = UKV.get(LIST);
    return list != null ? list._jobs : new Job[0];
  }

  public Job(String description, Key dest) {
    // Pinned to self, because it should be almost always updated locally
    _self = Key.make(UUID.randomUUID().toString(), (byte) 0, Key.JOB, H2O.SELF);
    _description = description;
    _startTime = System.currentTimeMillis();
    _dest = dest;
  }

  public void start() {
    DKV.put(_self, new Value(_self, new byte[0]));
    new TAtomic<List>() {
      @Override public List atomic(List old) {
        if( old == null ) old = new List();
        Job[] jobs = old._jobs;
        old._jobs = Arrays.copyOf(jobs,jobs.length+1);
        old._jobs[jobs.length] = Job.this;
        return old;
      }
    }.fork(LIST);
  }

  // Overriden for Parse
  public float progress() {
    Freezable f = UKV.get(_dest);
    if(f instanceof Job)
      return ((Job.Progress) f).progress();
    return 0;
  }

  // Block until the Job finishes.
  // NOT F/J FRIENDLY, EATS THE THREAD until job completes.  Only use for web threads.
  public <T> T get() {
    // TODO through notifications?
    while( DKV.get(_self) != null ) {
      try {
        Thread.sleep(10);
      } catch( InterruptedException e ) {
        throw new RuntimeException(e);
      }
    }
    return (T) UKV.get(_dest);
  }

  public void cancel() { cancel(_self); }
  public static void cancel(final Key self) {
    DKV.remove(self);
    DKV.write_barrier();
    new TAtomic<List>() {
      @Override public List atomic(List old) {
        if( old == null ) old = new List();
        Job[] jobs = old._jobs;
        for( int i = 0; i < jobs.length; i++ ) {
          if( jobs[i]._self.equals(self) ) {
            jobs[i]._endTime = CANCELLED_END_TIME;
            break;
          }
        }
        return old;
      }
    }.fork(LIST);
  }

  public boolean cancelled() { return cancelled(_self); }
  public static boolean cancelled(Key self) {
    return DKV.get(self) == null;
  }

  public void remove() {
    DKV.remove(_self);
    new TAtomic<List>() {
      @Override public List atomic(List old) {
        if( old == null ) return null;
        Job[] jobs = old._jobs;
        for( int i = 0; i < jobs.length; i++ ) {
          if( jobs[i]._self.equals(_self) ) {
            if(jobs[i]._endTime != CANCELLED_END_TIME)
              jobs[i]._endTime = System.currentTimeMillis();
            break;
          }
        }
        int count = jobs.length;
        while(count > KEEP_LAST_COUNT) {
          long min = Long.MAX_VALUE;
          int n = -1;
          for( int i = 0; i < jobs.length; i++ ) {
            if(jobs[i]._endTime != 0 && jobs[i]._startTime < min) {
              min = jobs[i]._startTime;
              n = i;
            }
          }
          if(n < 0)
            break;
          jobs[n] = jobs[--count];
        }
        if(count < jobs.length)
          old._jobs = Arrays.copyOf(jobs, count);
        return old;
      }
    }.fork(LIST);
  }
}
