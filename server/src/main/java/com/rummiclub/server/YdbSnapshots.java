package com.rummiclub.server;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import tech.ydb.auth.iam.CloudAuthHelper;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.core.StatusCode;
import tech.ydb.core.UnexpectedResultException;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.query.*;
import tech.ydb.query.tools.QueryReader;
import tech.ydb.table.query.Params;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.table.values.PrimitiveValue;

/** Hobby deployment: bounded snapshot, one serializable row, no local disk as source of truth. */
public final class YdbSnapshots implements SnapshotBackend {
    private final GrpcTransport transport;
    private final QueryClient client;
    private final String stateId;
    public YdbSnapshots(String connection) { this(connection, "main"); }
    YdbSnapshots(String connection, String stateId) {
        this.stateId = stateId;
        transport = GrpcTransport.forConnectionString(connection)
            .withAuthProvider(CloudAuthHelper.getAuthProviderFromEnviron()).build();
        client = QueryClient.newClient(transport).sessionPoolMinSize(0).sessionPoolMaxSize(4).build();
    }
    public void initialize() {
        try (QuerySession session = client.createSession(Duration.ofSeconds(10)).join().getValue()) {
            session.createQuery("CREATE TABLE IF NOT EXISTS rummi_state (id Utf8 NOT NULL, payload Utf8 NOT NULL, PRIMARY KEY(id))", TxMode.NONE)
                .execute().join().getStatus().expectSuccess("Create rummi_state");
        }
    }
    @Override public <T> T transact(Operation<T> operation) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try (QuerySession session = client.createSession(Duration.ofSeconds(10)).join().getValue()) {
                QueryTransaction tx = session.createNewTransaction(TxMode.SERIALIZABLE_RW);
                boolean committed = false;
                try {
                    ResultSetReader rows = QueryReader.readFrom(tx.createQuery("DECLARE $id AS Utf8; SELECT payload FROM rummi_state WHERE id=$id",
                        Params.of("$id", PrimitiveValue.newText(stateId))))
                        .join().getValue().getResultSet(0);
                    String before = rows.next() ? rows.getColumn("payload").getText() : null;
                    Update<T> update = operation.apply(before);
                    if (update.snapshot.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
                        throw new IllegalStateException("Snapshot capacity reached");
                    if (!Objects.equals(before, update.snapshot)) {
                        tx.createQuery("DECLARE $id AS Utf8; DECLARE $payload AS Utf8; UPSERT INTO rummi_state (id,payload) VALUES ($id,$payload)",
                            Params.of("$id", PrimitiveValue.newText(stateId), "$payload", PrimitiveValue.newText(update.snapshot))).execute().join().getValue();
                    }
                    tx.commit().join().getValue(); committed = true;
                    return update.result;
                } finally { if (!committed) try { tx.rollback().join(); } catch (RuntimeException ignored) { } }
            } catch (UnexpectedResultException e) {
                // Only a confirmed abort is safe to replay. Never replay an ambiguous commit.
                if (e.getStatus().getCode() != StatusCode.ABORTED || attempt >= 5) throw e;
                Thread.sleep(15L * (attempt + 1));
            }
        }
    }
    void deleteTestSnapshot() {
        if (!stateId.startsWith("test-")) throw new IllegalStateException("Only isolated test data may be removed");
        try (QuerySession session = client.createSession(Duration.ofSeconds(10)).join().getValue()) {
            session.createQuery("DECLARE $id AS Utf8; DELETE FROM rummi_state WHERE id=$id", TxMode.SERIALIZABLE_RW,
                Params.of("$id", PrimitiveValue.newText(stateId))).execute().join().getValue();
        }
    }
    @Override public void close() { client.close(); transport.close(); }
}
