package com.rummiclub.server;

import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Opt-in contract test against the real database, isolated from the game's main row. */
public class YdbLiveTest {
    @Test public void concurrentInstancesCommitExactlyOnceAndReadDurableState() throws Exception {
        Assume.assumeTrue("Explicit live-test opt in required", "1".equals(System.getenv("RUMMI_LIVE_YDB")));
        String connection=System.getenv("YDB_CONNECTION_STRING"),id="test-"+UUID.randomUUID();
        try(YdbSnapshots a=new YdbSnapshots(connection,id);YdbSnapshots b=new YdbSnapshots(connection,id)) {
            a.transact(s->new SnapshotBackend.Update<>("0",null));
            ExecutorService workers=Executors.newFixedThreadPool(2);
            try {
                List<Callable<Void>> jobs=Arrays.asList(()->{increment(a);return null;},()->{increment(b);return null;});
                for(Future<Void> result:workers.invokeAll(jobs))result.get();
                assertEquals("2",a.transact(s->new SnapshotBackend.Update<>(s,s)));
                assertEquals("2",b.transact(s->new SnapshotBackend.Update<>(s,s)));
            } finally { workers.shutdownNow();a.deleteTestSnapshot(); }
        }
    }
    private static void increment(YdbSnapshots backend) throws Exception {
        backend.transact(s->{Thread.sleep(100);return new SnapshotBackend.Update<>(String.valueOf(Integer.parseInt(s)+1),null);});
    }
}
