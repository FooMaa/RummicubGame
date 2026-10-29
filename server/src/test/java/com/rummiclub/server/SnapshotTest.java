package com.rummiclub.server;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SnapshotTest {
    static final class Shared { String snapshot; boolean fail; }
    static final class Memory implements SnapshotBackend {
        final Shared shared;
        Memory(Shared shared) { this.shared=shared; }
        public <T> T transact(Operation<T> operation) throws Exception {
            synchronized(shared) {
                Update<T> update=operation.apply(shared.snapshot);
                if(shared.fail) { shared.fail=false; throw new IllegalStateException("Simulated commit failure"); }
                shared.snapshot=update.snapshot;return update.result;
            }
        }
        public void close() { }
    }
    private final HttpClient client=HttpClient.newHttpClient();
    private int status;
    private JsonObject call(Server server,String route,String token,String body) throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://localhost:"+server.port()+"/api/"+route)).POST(HttpRequest.BodyPublishers.ofString(body));
        if(token!=null)b.header("Authorization","Bearer "+token);
        HttpResponse<String> r=client.send(b.build(),HttpResponse.BodyHandlers.ofString());status=r.statusCode();return JsonParser.parseString(r.body()).getAsJsonObject();
    }
    private String register(Server s,String name) throws Exception {JsonObject a=call(s,"auth/register",null,"{\"name\":\""+name+"\",\"password\":\"password123\"}");assertEquals(200,status);return a.get("token").getAsString();}
    @Test public void twoInstancesShareAccountsAndRejectRepeatedMovesAfterFailedCommit() throws Exception {
        Path dir=Files.createTempDirectory("rummi-snapshot");Shared shared=new Shared();
        try(Server a=new Server(0,dir.resolve("a.db"),new Memory(shared));Server b=new Server(0,dir.resolve("b.db"),new Memory(shared))) {
            a.start();b.start();String alice=register(a,"Alice"),boris=register(b,"Boris");
            JsonObject lobby=call(a,"room/create",alice,"{\"turnSeconds\":0}");String code=lobby.get("code").getAsString();String body="{\"code\":\""+code+"\"}";
            call(b,"room/join",boris,body);assertEquals(200,status);JsonObject game=call(a,"room/start",alice,body);
            String current=game.get("turn").equals(game.get("me"))?alice:boris;
            String command="{\"code\":\""+code+"\",\"revision\":0}";
            shared.fail=true;call(b,"room/draw",current,command);assertEquals(503,status);
            assertEquals(0,call(b,"room/state",current,body).get("revision").getAsInt());
            call(a,"room/draw",current,command);assertEquals(200,status);
            call(b,"room/draw",current,command);assertEquals(400,status);
            assertEquals(1,call(b,"room/state",alice,body).get("revision").getAsInt());
            for(JsonElement p:call(b,"room/state",alice,body).getAsJsonArray("players"))assertFalse(p.getAsJsonObject().has("rack"));
        }
        try(Server restarted=new Server(0,dir.resolve("fresh.db"),new Memory(shared))) {
            restarted.start();call(restarted,"auth/login",null,"{\"name\":\"Alice\",\"password\":\"password123\"}");assertEquals(200,status);
        }
        try(var paths=Files.walk(dir)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}
    }
    @Test public void snapshotIncludesRevocationAndRestoresExactly() throws Exception {
        Path dir=Files.createTempDirectory("rummi-store");
        try(Store a=new Store(dir.resolve("a.db"));Store b=new Store(dir.resolve("b.db"))) {
            Store.Account user=a.register("Alice","password123");String before=a.snapshot();b.restore(before);
            assertEquals(before,b.snapshot());assertEquals(user.id,b.authenticate(user.token).id);
            b.logout(user.token);a.restore(b.snapshot());assertThrows(SecurityException.class,()->a.authenticate(user.token));
        }
        try(var paths=Files.walk(dir)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}
    }
    @Test public void pollingAdvancesBotsWithoutABackgroundThread() throws Exception {
        Path dir=Files.createTempDirectory("rummi-cloud-bot");Shared shared=new Shared();
        try(Server s=new Server(0,dir.resolve("bot.db"),new Memory(shared))) {
            s.start();String token=register(s,"Alice");String code=call(s,"room/create",token,"{\"turnSeconds\":0}").get("code").getAsString();String body="{\"code\":\""+code+"\"}";
            call(s,"room/bot",token,body);JsonObject game=call(s,"room/start",token,body);
            if(game.get("me").equals(game.get("turn")))game=call(s,"room/draw",token,"{\"code\":\""+code+"\",\"revision\":0}");
            long revision=game.get("revision").getAsLong();Thread.sleep(1800);
            JsonObject next=call(s,"room/state",token,body);assertEquals(200,status);assertTrue(next.get("revision").getAsLong()>revision);
        }
        try(var paths=Files.walk(dir)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}
    }
}
