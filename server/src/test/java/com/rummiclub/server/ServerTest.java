package com.rummiclub.server;

import com.google.gson.*;
import org.junit.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import static org.junit.Assert.*;
import com.rummiclub.core.*;

public class ServerTest {
    private Server server;
    private Path database;
    private final HttpClient client=HttpClient.newHttpClient();
    private int status;
    @Before public void setup() throws Exception { database=Files.createTempDirectory("rummi-test").resolve("game.db");server=new Server(0,database);server.start(); }
    @After public void stop() throws Exception {server.close();try(java.util.stream.Stream<Path> paths=Files.walk(database.getParent())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}
    private JsonObject call(String route,String token,String json) throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://localhost:"+server.port()+"/api/"+route)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        if(token!=null)b.header("Authorization","Bearer "+token);
        HttpResponse<String> response=client.send(b.build(),HttpResponse.BodyHandlers.ofString());status=response.statusCode();return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private String register(String name) throws Exception {JsonObject r=call("auth/register",null,"{\"name\":\""+name+"\",\"password\":\"password123\"}");assertEquals(r.toString(),200,status);return r.get("token").getAsString();}
    @Test public void authenticationValidationAndRevocation() throws Exception {
        call("auth/login",null,"{broken");assertEquals(400,status);
        call("auth/register",null,"{\"name\":\"Al\",\"password\":\"weak\"}");assertEquals(400,status);
        String token=register("Алиса");call("auth/register",null,"{\"name\":\"алиса\",\"password\":\"password123\"}");assertEquals(400,status);
        call("auth/login",null,"{\"name\":\"Алиса\",\"password\":\"wrong\"}");assertEquals(400,status);
        JsonObject login=call("auth/login",null,"{\"name\":\"алиса\",\"password\":\"password123\"}");assertEquals(200,status);assertNotNull(login.get("token"));
        call("room/create","forged","{}");assertEquals(401,status);
        call("auth/logout",token,"{}");assertEquals(200,status);call("room/current",token,"{}");assertEquals(401,status);
    }
    @Test public void roomPermissionsHiddenHandsAndDurableReconnect() throws Exception {
        String a=register("Alice"),b=register("Boris"),outsider=register("Carol");
        JsonObject room=call("room/create",a,"{\"turnSeconds\":0}");String code=room.get("code").getAsString();String body="{\"code\":\""+code+"\"}";
        call("room/state",outsider,body);assertEquals(400,status);
        call("room/start",a,body);assertEquals(400,status);
        call("room/join",b,body);assertEquals(200,status);
        call("room/bot",b,body);assertEquals(400,status);
        call("room/bot",a,body);assertEquals(200,status);
        JsonObject started=call("room/start",a,body);assertEquals(200,status);assertEquals(14,started.getAsJsonArray("rack").size());assertFalse(started.has("pool"));
        for(JsonElement player:started.getAsJsonArray("players"))assertFalse(player.getAsJsonObject().has("rack"));
        call("room/join",outsider,body);assertEquals(400,status);
        server.close();server=new Server(0,database);server.start();
        JsonObject restored=call("room/state",a,body);assertEquals(200,status);assertEquals(started.get("rack"),restored.get("rack"));assertEquals(code,call("room/current",b,"{}").get("code").getAsString());
        call("room/leave",b,body);assertEquals(200,status);call("room/state",b,body);assertEquals(400,status);
        call("room/leave",a,body);assertEquals(200,status);assertEquals("",call("room/current",a,"{}").get("code").getAsString());
    }
    @Test public void invalidAndRepeatedMovesCannotMutateTheGame() throws Exception {
        String a=register("Alice"),b=register("Boris");JsonObject lobby=call("room/create",a,"{\"turnSeconds\":0}");String code=lobby.get("code").getAsString();String body="{\"code\":\""+code+"\"}";
        call("room/join",b,body);JsonObject game=call("room/start",a,body);
        String current=game.get("turn").getAsString().equals(game.get("me").getAsString())?a:b;
        String other=current.equals(a)?b:a;
        call("room/draw",other,"{\"code\":\""+code+"\",\"revision\":0}");assertEquals(400,status);
        call("room/play",current,"{\"code\":\""+code+"\",\"revision\":0,\"board\":[[0,0,0]]}");assertEquals(400,status);
        call("room/play",current,"{\"code\":\""+code+"\",\"revision\":0,\"board\":[[0.5,1,2]]}");assertEquals(400,status);
        assertEquals(0,call("room/state",a,body).get("revision").getAsLong());
        JsonObject result=call("room/draw",current,"{\"code\":\""+code+"\",\"revision\":0}");assertEquals(200,status);assertEquals(15,result.getAsJsonArray("rack").size());
        call("room/draw",current,"{\"code\":\""+code+"\",\"revision\":0}");assertEquals(400,status);
        assertEquals(1,call("room/state",a,body).get("revision").getAsLong());
    }
    @Test public void twoRemoteClientsCanCompleteAGameAndRematch() throws Exception {
        String a=register("Alice"),b=register("Boris");
        JsonObject lobby=call("room/create",a,"{\"turnSeconds\":0}");String code=lobby.get("code").getAsString();String body="{\"code\":\""+code+"\"}";
        String aId=lobby.get("me").getAsString();call("room/join",b,body);JsonObject state=call("room/start",a,body);
        Gson gson=new Gson();int turns=0;
        while(!state.get("finished").getAsBoolean()&&turns++<800){
            String token=state.get("turn").getAsString().equals(aId)?a:b;
            state=call("room/state",token,body);Room.View visible=gson.fromJson(state,Room.View.class);
            boolean opened=visible.players.stream().filter(p->p.id.equals(visible.me)).findFirst().get().opened;
            java.util.List<java.util.List<Integer>> move=Bot.choose(visible.rack,visible.board,opened);
            JsonObject command=new JsonObject();command.addProperty("code",code);command.addProperty("revision",visible.revision);
            if(move!=null)command.add("board",gson.toJsonTree(move));
            state=call(move==null?"room/draw":"room/play",token,command.toString());assertEquals(state.toString(),200,status);
        }
        assertTrue(state.get("finished").getAsBoolean());int score=0;for(JsonElement p:state.getAsJsonArray("players"))score+=p.getAsJsonObject().get("score").getAsInt();assertEquals(0,score);
        call("room/rematch",b,body);assertEquals(400,status);
        assertFalse(call("room/rematch",a,body).get("started").getAsBoolean());
    }
    @Test public void serverBotsPlayWithoutClientCommands() throws Exception {
        String token=register("Alice");JsonObject room=call("room/create",token,"{\"turnSeconds\":0}");String code=room.get("code").getAsString();String body="{\"code\":\""+code+"\"}";
        call("room/bot",token,body);JsonObject game=call("room/start",token,body);
        if(game.get("turn").equals(game.get("me")))game=call("room/draw",token,"{\"code\":\""+code+"\",\"revision\":0}");
        long revision=game.get("revision").getAsLong();long until=System.currentTimeMillis()+6000;
        do{Thread.sleep(250);game=call("room/state",token,body);}while(game.get("revision").getAsLong()==revision&&System.currentTimeMillis()<until);
        assertTrue(game.get("revision").getAsLong()>revision);assertEquals(game.get("me"),game.get("turn"));
    }
}
