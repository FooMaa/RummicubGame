package com.rummiclub.server;

/** A small deployment's complete state is read and committed in one serializable transaction. */
public interface SnapshotBackend extends AutoCloseable {
    @FunctionalInterface interface Operation<T> { Update<T> apply(String snapshot) throws Exception; }
    final class Update<T> {
        public final String snapshot;
        public final T result;
        public Update(String snapshot, T result) { this.snapshot = snapshot; this.result = result; }
    }
    <T> T transact(Operation<T> operation) throws Exception;
    @Override void close() throws Exception;
}
