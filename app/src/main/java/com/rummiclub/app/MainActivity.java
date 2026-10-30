package com.rummiclub.app;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.google.gson.*;
import com.rummiclub.core.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF111C1B, CARD = 0xFF1D2D29, MUTED = 0xFF9BAEA5, INK = 0xFFF3F5ED, LIME = 0xFFC5F277;
    private static final Gson JSON = new Gson();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private Future<?> requestTask, pollTask, editTask;
    private AlertDialog connectionDialog;
    private long requestStarted;
    private long draftSerial, syncedDraftSerial;
    private boolean editBusy, dragging, gameLayout;
    private ToneGenerator tones;
    private final TurnAlerts alerts=new TurnAlerts();
    private int trainingSeconds;
    private SharedPreferences prefs;
    private SessionVault vault;
    private String token = "", endpoint = "", screen = "home", playerName = "Игрок";
    private LinearLayout root;
    private ScrollView scroll, rackViewport;
    private final List<HorizontalScrollView> rowViewports=new ArrayList<>();
    private TextView connectionLabel, clockLabel;
    private Room local;
    private Room.View view;
    private boolean online, active, requestBusy, pollBusy, botBusy, dirty, sortByNumber;
    private int epoch;
    private long lastPoll;
    private List<List<Integer>> draft = new ArrayList<>();
    private final LinkedHashSet<Integer> selected = new LinkedHashSet<>();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (connectionDialog != null && connectionDialog.isShowing() && requestBusy) {
                long seconds = (SystemClock.elapsedRealtime() - requestStarted) / 1000;
                connectionDialog.setMessage("Ожидаем ответ сервера · " + seconds + " с\n\nПосле простоя бесплатный сервер может запускаться до двух минут.\n\n" + endpoint);
            }
            if (screen.equals("game") || screen.equals("lobby")) {
                if (online && view != null && System.currentTimeMillis() - lastPoll >= 3000) poll();
                if (!online && screen.equals("game")) {
                    if(local!=null&&local.game!=null){long revision=local.game.revision;local.game.timeout(System.currentTimeMillis());if(local.game.revision!=revision){saveLocal();accept(local.view("local"),true);}}
                    botStep();
                }
                updateClock();
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("rummiclub", MODE_PRIVATE); vault = new SessionVault(this);
        try{tones=new ToneGenerator(AudioManager.STREAM_MUSIC,65);}catch(RuntimeException unavailable){tones=null;}
        token = vault.read(); endpoint = prefs.getString("endpoint", BuildConfig.DEFAULT_SERVER_URL); playerName = prefs.getString("name", "Игрок");
        try { local = JSON.fromJson(prefs.getString("local", "null"), Room.class); } catch (RuntimeException ignored) { local = null; }
        if (state != null && state.containsKey("view")) {
            try {
                view = JSON.fromJson(state.getString("view"), Room.View.class); online = state.getBoolean("online");
                screen = state.getString("screen", "home");
                if (view != null && (screen.equals("game") || screen.equals("lobby"))) {
                    draft = Rules.copyBoard(view.board);
                    if (state.containsKey("draft")) {
                        draft = JSON.fromJson(state.getString("draft"), new com.google.gson.reflect.TypeToken<List<List<Integer>>>(){}.getType());
                        dirty = state.getBoolean("dirty");
                    }
                    renderRoom(); return;
                }
            } catch (RuntimeException ignored) { }
        }
        home();
    }
    @Override protected void onResume() { super.onResume(); active = true; handler.removeCallbacks(ticker); handler.post(ticker); }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (screen.equals("game") && view != null) gameScreen();
    }
    @Override protected void onPause() { super.onPause(); active = false; handler.removeCallbacks(ticker); if(online)editSignal();saveLocal(); }
    @Override protected void onDestroy() { super.onDestroy(); dismissConnectionDialog(); epoch++; handler.removeCallbacksAndMessages(null); worker.shutdownNow(); network.shutdownNow();if(tones!=null)tones.release(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (view != null) { out.putString("view", JSON.toJson(view)); out.putString("draft", JSON.toJson(draft)); out.putBoolean("dirty", dirty); out.putBoolean("online", online); out.putString("screen", screen); }
    }
    @Override public void onBackPressed() {
        if (screen.equals("home")) super.onBackPressed();
        else if (dirty) new AlertDialog.Builder(this).setTitle("Вернуться в меню?").setMessage("Черновик сохраняется. При истечении времени правильные комбинации будут подтверждены.").setPositiveButton("В меню", (d,w) -> { leaveGameToMenu(); }).setNegativeButton("Остаться", null).show();
        else if(screen.equals("game"))leaveGameToMenu();else home();
    }
    private void leaveGameToMenu(){
        if(online&&myTurn()&&draftSerial>syncedDraftSerial){JsonObject b=roomBody();b.addProperty("editing",dirty);b.add("draft",JSON.toJsonTree(draft));b.addProperty("draftVersion",draftSerial);request("room/edit",b,r->home());}else home();
    }
    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private void space(LinearLayout parent, int height) { View v = new View(this); parent.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private TextView label(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextColor(color); t.setTextSize(size); t.setLineSpacing(dp(3), 1);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        parent.addView(t, new LinearLayout.LayoutParams(-1, -2)); return t;
    }
    private TextView eyebrow(LinearLayout parent, String value) { TextView t = label(parent, value, 11, LIME, true); t.setLetterSpacing(.16f); return t; }
    private Button button(LinearLayout parent,String value,boolean primary,Runnable action){
        LinearLayout item=row();item.setPadding(dp(6),dp(5),dp(10),dp(5));item.setBackground(shape(primary?0xFF284035:CARD,16));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(parent.getOrientation()==LinearLayout.HORIZONTAL?0:-1,-2,parent.getOrientation()==LinearLayout.HORIZONTAL?1:0);params.setMargins(dp(2),dp(7),dp(2),0);parent.addView(item,params);
        ActionButton button=new ActionButton(this,iconFor(value),value,primary);item.addView(button,new LinearLayout.LayoutParams(dp(48),dp(48)));button.setOnClickListener(v->action.run());
        TextView caption=label(item,value,13,INK,true);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);cp.leftMargin=dp(10);caption.setLayoutParams(cp);caption.setMaxLines(2);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        item.setOnClickListener(v->{if(button.isEnabled())button.performClick();});return button;
    }
    private String iconFor(String value){
        String t=value.toLowerCase(Locale.ROOT);
        if(t.contains("убрать")||t.contains("снять"))return "clear";
        if(t.contains("поделиться"))return "share";
        if(t.contains("сохранить"))return "save";
        if(t.contains("настрой")||t.contains("адрес"))return "settings";
        if(t.contains("правил")||t.contains("как играть"))return "help";
        if(t.contains("покинуть")||t.contains("выйти"))return "exit";
        if(t.contains("в меню"))return "home";
        if(t.contains("бот"))return "bot";
        if(t.contains("начать")||t.contains("продолжить")||t.contains("ещё партию"))return "play";
        if(t.contains("создать комнату"))return "plus";
        if(t.contains("войти")||t.contains("аккаунт"))return "key";
        return "users";
    }
    private void pair(LinearLayout parent,String a,Runnable aa,String b,Runnable bb){LinearLayout r=row();parent.addView(r);button(r,a,false,aa);button(r,b,false,bb);}
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = column(); c.setPadding(dp(20),dp(20),dp(20),dp(20)); c.setBackground(shape(CARD,24));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin = dp(16); parent.addView(c,p); return c;
    }
    private void base(String section) {
        gameLayout=false;connectionLabel = null; clockLabel = null;
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG); scroll.setClipToPadding(false);
        root = column(); root.setPadding(dp(22),dp(20),dp(22),dp(28));
        scroll.addView(root); setContentView(scroll);
        LinearLayout nav = row(); root.addView(nav);
        TextView brand = new TextView(this); brand.setText("Руммикуб"); brand.setTextColor(INK); brand.setTextSize(21); brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        nav.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView tag = new TextView(this); tag.setText(section); tag.setTextColor(LIME); tag.setTextSize(10); tag.setLetterSpacing(.1f); tag.setPadding(dp(12),dp(9),dp(12),dp(9)); tag.setBackground(shape(CARD,20)); nav.addView(tag);
        brand.setContentDescription("Руммикуб — главное меню"); brand.setOnClickListener(v -> onBackPressed());
        ActionButton help=new ActionButton(this,"help","Справка по кнопкам",false);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(40),dp(40));hp.leftMargin=dp(8);nav.addView(help,hp);help.setOnClickListener(v->controlsHelp());space(root,24);
    }
    private void navigate(String destination) {
        if (!screen.equals(destination)) {
            dismissConnectionDialog();
            epoch++; screen = destination;
            if (requestTask != null) requestTask.cancel(true);
            if (pollTask != null) pollTask.cancel(true);
            if (editTask != null) editTask.cancel(true);
            requestBusy = false; pollBusy = false;editBusy=false;dragging=false;
        }
    }
    private void home() {
        navigate("home"); base("ИГРА В ЧИСЛА");
        eyebrow(root,"ВАШ СЛЕДУЮЩИЙ ХОРОШИЙ ХОД"); space(root,12);
        label(root,"Собирайте ряды.\nОбъединяйте друзей.",34,INK,true); space(root,12);
        label(root,"Немного стратегии. Немного удачи.\nИ ещё одна партия.",15,MUTED,false);
        LinearLayout hero = card(root); GradientDrawable heroBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFF315C43,0xFF1B3931}); heroBackground.setCornerRadius(dp(24)); hero.setBackground(heroBackground);
        LinearLayout tiles = row(); tiles.setGravity(Gravity.CENTER); hero.addView(tiles);
        for (int id : new int[]{6,7,8,104}) { TileView tile = new TileView(this,id,false); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(55),dp(76)); p.setMargins(dp(4),0,dp(4),0); tiles.addView(tile,p); tile.setRotation(id==6 ? -8 : id==104 ? 8 : 0); }
        space(hero,16); TextView hint = label(hero,"106 фишек · 2–4 игрока · бесконечно много ходов",11,0xFFD8E8C9,false); hint.setGravity(Gravity.CENTER);
        button(root,"Играть с ботами   →",true,this::newLocalDialog);
        button(root,"Играть с друзьями   ↗",false,this::onlineMenu);
        if (local != null && local.game != null && !local.game.finished) button(root,"Продолжить тренировку",false,() -> { online=false; accept(local.view("local"),true); });
        if (!token.isEmpty()) button(root,"Вернуться в сетевую комнату",false,() -> request("room/current",new JsonObject(),result -> {
            if (!result.has("code") || result.get("code").getAsString().isEmpty()) { toast("Активных комнат нет"); onlineMenu(); }
            else { online=true; accept(JSON.fromJson(result,Room.View.class),true); }
        }));
        pair(root,"Как играть",this::rules,"Настройки",this::settings);
        space(root,24); label(root,"Играйте в своём ритме. Тренировка доступна без интернета.",12,MUTED,false);
    }
    private void newLocalDialog() {
        new AlertDialog.Builder(this).setTitle("Сколько ботов за столом?").setItems(new String[]{"Один · дуэль","Два · компания","Три · полный стол"},(d,which) -> {
            Runnable start = () -> {
                alerts.reset();
                local = new Room(); local.code="TRAINING"; local.host="local"; local.turnSeconds=trainingSeconds;
                local.members.add(new Game.Player("local",playerName,false));
                String[] names={"Атлас","Луна","Орион"};
                for(int i=0;i<=which;i++) local.members.add(new Game.Player("bot-"+i,names[i],true));
                local.game=Game.start(local.members,new java.security.SecureRandom(),local.turnSeconds,System.currentTimeMillis());
                saveLocal(); online=false; accept(local.view("local"),true);
            };
            Runnable confirm=()->{if(local!=null && local.game!=null && !local.game.finished)new AlertDialog.Builder(this).setTitle("Начать заново?").setMessage("Текущая тренировка будет заменена.").setPositiveButton("Начать",(x,w)->start.run()).setNegativeButton("Отмена",null).show();else start.run();};
            new AlertDialog.Builder(this).setTitle("Время на ход").setItems(new String[]{"Без таймера","60 секунд","120 секунд"},(x,w)->{trainingSeconds=new int[]{0,60,120}[w];confirm.run();}).show();
        }).show();
    }
    private void onlineMenu() {
        if (endpoint.isEmpty()) { settings(); return; }
        if (token.isEmpty()) { auth(false); return; }
        navigate("online"); base("ПО СЕТИ"); eyebrow(root,"РАДЫ ВИДЕТЬ ВАС, " + playerName.toUpperCase(Locale.ROOT));
        space(root,12); label(root,"Ваш стол\nуже ждёт.",36,INK,true);
        LinearLayout c=card(root); label(c,"Вместе интереснее",22,INK,true); space(c,8); label(c,"Создайте комнату, поделитесь кодом и пригласите до трёх друзей. Свободные места можно отдать ботам.",15,MUTED,false);
        button(c,"Создать комнату",true,() -> new AlertDialog.Builder(this).setTitle("Время на ход").setItems(new String[]{"60 секунд · классика","120 секунд · спокойно","Без таймера"},(d,w) -> {
            JsonObject body=new JsonObject(); body.addProperty("turnSeconds",new int[]{60,120,0}[w]);
            request("room/create",body,result->{ online=true; accept(JSON.fromJson(result,Room.View.class),true); });
        }).show());
        button(c,"Войти по коду",false,this::joinDialog);
        button(root,"Моя текущая комната",false,()->request("room/current",new JsonObject(),result->{
            if(result.get("code").getAsString().isEmpty()) toast("У вас пока нет комнаты");
            else { online=true; accept(JSON.fromJson(result,Room.View.class),true); }
        }));
        button(root,"← В меню",false,this::home);
        connectionLabel=label(root,"",12,MUTED,false);
    }
    private EditText input(LinearLayout parent,String hint,boolean password) {
        EditText e=new EditText(this); e.setHint(hint); e.setTextColor(INK); e.setHintTextColor(MUTED); e.setTextSize(16); e.setSingleLine(true);
        e.setPadding(dp(14),dp(12),dp(14),dp(12)); e.setBackground(shape(0xFF283D35,12));
        e.setInputType(password ? InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54)); p.topMargin=dp(12); parent.addView(e,p); return e;
    }
    private void auth(boolean register) {
        navigate("auth"); base("АККАУНТ"); eyebrow(root,"СОХРАНИТЕ СВОЁ МЕСТО ЗА СТОЛОМ"); space(root,12);
        label(root,register?"Привет,\nновый игрок.":"С возвращением.",34,INK,true);
        LinearLayout c=card(root); label(c,register?"Создать аккаунт":"Вход в Руммикуб",22,INK,true);
        EditText name=input(c,"Имя игрока",false), password=input(c,"Пароль · минимум 8 символов",true);
        if(!playerName.equals("Игрок")) name.setText(playerName);
        label(c,"Имя служит логином. Пароль понадобится для входа на другом устройстве.",12,MUTED,false);
        button(c,register?"Зарегистрироваться":"Войти",true,()->{
            JsonObject body=new JsonObject(); body.addProperty("name",name.getText().toString().trim()); body.addProperty("password",password.getText().toString());
            request("auth/"+(register?"register":"login"),body,result->{
                token=result.get("token").getAsString(); playerName=result.get("name").getAsString();
                try { vault.save(token); } catch(Exception e) { toast("Сессия доступна только до закрытия приложения"); }
                prefs.edit().putString("name",playerName).apply(); onlineMenu();
            });
        });
        button(root,register?"Уже есть аккаунт? Войти":"Впервые здесь? Создать аккаунт",false,()->auth(!register));
        button(root,"Адрес сервера",false,this::settings); button(root,"← В меню",false,this::home);
        connectionLabel=label(root,"",12,MUTED,false);
    }
    private void settings() {
        navigate("settings"); base("НАСТРОЙКИ"); label(root,"Ваше пространство",28,INK,true);
        LinearLayout c=card(root); label(c,"Сервер для сетевой игры",20,INK,true);
        label(c,"Все друзья должны подключиться к одному серверу. Адрес выдаёт тот, кто его запустил.",14,MUTED,false);
        EditText url=input(c,"https://game.example.com",false); url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI); url.setText(endpoint);
        button(c,"Сохранить адрес",true,()->{
            String value=url.getText().toString().trim().replaceAll("/+$","");
            try { URI uri=URI.create(value); if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || (!"https".equals(uri.getScheme()) && !(BuildConfig.DEBUG&&"http".equals(uri.getScheme())))) throw new IllegalArgumentException(); }
            catch(Exception e){toast(BuildConfig.DEBUG?"Укажите полный http:// или https:// адрес":"Укажите полный HTTPS-адрес");return;}
            if(!value.equals(endpoint)){token="";vault.clear();view=null;}
            endpoint=value;prefs.edit().putString("endpoint",endpoint).apply();toast("Адрес сохранён");onlineMenu();
        });
        if(BuildConfig.DEBUG) label(c,"В этой установочной сборке разрешён HTTP для домашней сети. Например: http://192.168.1.20:8080. Release требует HTTPS.",12,MUTED,false);
        if(!token.isEmpty()) button(root,"Выйти из аккаунта «"+playerName+"»",false,()->request("auth/logout",new JsonObject(),r->{token="";vault.clear();view=null;home();}));
        CheckBox sound=new CheckBox(this);sound.setText("Звук смены хода и предупреждений");sound.setTextColor(INK);sound.setChecked(prefs.getBoolean("sound",true));root.addView(sound);sound.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("sound",on).apply());
        CheckBox vibration=new CheckBox(this);vibration.setText("Вибрация");vibration.setTextColor(INK);vibration.setChecked(prefs.getBoolean("vibration",true));root.addView(vibration);vibration.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean("vibration",on).apply());
        button(root,"← В меню",false,this::home);
    }
    private void joinDialog() {
        EditText code=new EditText(this);code.setHint("Код из 6 символов");code.setSingleLine(true);code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(this).setTitle("Присоединиться к друзьям").setView(code).setPositiveButton("Войти",(d,w)->{
            JsonObject body=new JsonObject();body.addProperty("code",code.getText().toString().trim().toUpperCase(Locale.ROOT));
            request("room/join",body,r->{online=true;accept(JSON.fromJson(r,Room.View.class),true);});
        }).setNegativeButton("Отмена",null).show();
    }
    private void accept(Room.View incoming,boolean reset) {
        if(view==null||!Objects.equals(view.code,incoming.code)||incoming.revision<view.revision||(!view.started&&incoming.started)||(view.finished&&!incoming.finished))alerts.reset();
        boolean boardChanged=reset||view==null||!Objects.equals(view.code,incoming.code)||view.revision!=incoming.revision||view.started!=incoming.started;
        boolean changed=boardChanged||!JSON.toJson(view.players).equals(JSON.toJson(incoming.players))||!Objects.equals(view.host,incoming.host);
        view=incoming;
        if(boardChanged){if(editTask!=null)editTask.cancel(true);editTask=null;editBusy=false;draft=view.draft!=null&&view.me.equals(view.turn)?Rules.copyBoard(view.draft):Rules.copyBoard(view.board);selected.clear();dirty=!draft.equals(view.board);draftSerial=view.draftVersion;syncedDraftSerial=view.draftVersion;dragging=false;}
        if(changed&&!dragging)renderRoom();
        if(connectionLabel!=null)connectionLabel.setText(online?(draftSerial>syncedDraftSerial?"Сохраняем черновик…":"● Черновик сохранён"):"● Тренировка · без интернета");
    }
    private void renderRoom(){if(view.started){navigate("game");gameScreen();}else{navigate("lobby");lobby();}}
    private void lobby(){
        base("КОМНАТА");eyebrow(root,"ПРИГЛАСИТЕ СВОЮ КОМПАНИЮ");space(root,12);label(root,"Начнём, когда\nвсе соберутся.",32,INK,true);
        LinearLayout c=card(root);eyebrow(c,"КОД КОМНАТЫ");TextView code=label(c,view.code,36,LIME,true);code.setLetterSpacing(.15f);
        button(c,"Поделиться кодом",false,()->{
            Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TEXT,"Заходи в Руммикуб! Код комнаты: "+view.code+"\nСервер: "+endpoint);startActivity(Intent.createChooser(intent,"Пригласить друзей"));
        });
        space(root,18);label(root,"ИГРОКИ  "+view.players.size()+" / 4",12,MUTED,true);
        for(Room.Seat p:view.players){
            LinearLayout seat=card(root);label(seat,(p.bot?"◈  ":"●  ")+p.name+(p.id.equals(view.me)?" · вы":""),18,INK,true);
            label(seat,p.bot?"Бот · готов к игре":p.id.equals(view.host)?"Хозяин комнаты":"За столом",12,MUTED,false);
            if(p.bot&&view.host.equals(view.me))button(seat,"Убрать бота",false,()->{JsonObject b=new JsonObject();b.addProperty("player",p.id);roomAction("removeBot",b);});
        }
        space(root,8);label(root,view.turnSeconds==0?"Без таймера · играйте в своём ритме":"На ход: "+view.turnSeconds+" секунд",13,MUTED,false);
        if(view.host.equals(view.me)){
            if(view.players.size()<4)button(root,"+ Добавить бота",false,()->roomAction("bot",new JsonObject()));
            Button start=button(root,"Начать партию   →",true,()->roomAction("start",new JsonObject()));start.setEnabled(view.players.size()>=2);start.setAlpha(view.players.size()>=2?1f:.4f);
        }else label(root,"Хозяин комнаты начнёт партию, когда все будут готовы.",14,MUTED,false);
        button(root,"Покинуть комнату",false,this::leaveDialog);connectionLabel=label(root,"● Подключено",12,MUTED,false);
    }
    private boolean myTurn(){return view!=null&&view.started&&!view.finished&&view.me.equals(view.turn);}
    private boolean canEdit(){if(!myTurn()){toast("Дождитесь своего хода");return false;}if(requestBusy){toast("Ход отправляется…");return false;}if(view.deadline>0&&remainingSeconds()==0){toast("Время истекло · ждём подтверждения стола");return false;}return true;}
    private Room.Seat me(){for(Room.Seat p:view.players)if(p.id.equals(view.me))return p;throw new IllegalStateException();}
    private ActionButton action(LinearLayout parent,String icon,String description,boolean primary,boolean enabled,Runnable click){
        ActionButton button=new ActionButton(this,icon,description,primary);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(3),0,dp(3),0);parent.addView(button,p);
        button.setEnabled(enabled);button.setAlpha(enabled?1:.35f);button.setOnClickListener(v->click.run());return button;
    }
    private void gameScreen(){
        int oldY=!gameLayout||scroll==null?0:scroll.getScrollY();int oldRackY=!gameLayout||rackViewport==null?0:rackViewport.getScrollY();
        List<Integer> oldXs=new ArrayList<>();if(gameLayout)for(HorizontalScrollView viewport:rowViewports)oldXs.add(viewport.getScrollX());rowViewports.clear();gameLayout=true;connectionLabel=null;clockLabel=null;
        boolean landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout frame=column();frame.setBackgroundColor(BG);frame.setPadding(dp(10),dp(6),dp(10),dp(4));setContentView(frame);
        LinearLayout nav=row();frame.addView(nav,new LinearLayout.LayoutParams(-1,dp(landscape?40:46)));
        ActionButton home=new ActionButton(this,"home","Главное меню",false);nav.addView(home,new LinearLayout.LayoutParams(dp(42),dp(42)));home.setOnClickListener(v->onBackPressed());
        TextView heading=label(nav,myTurn()?"Ваш ход":view.finished?"Итоги партии":"Ход соперника",20,INK,true);heading.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));heading.setPadding(dp(10),0,0,0);
        ActionButton help=new ActionButton(this,"help","Справка по кнопкам",false);nav.addView(help,new LinearLayout.LayoutParams(dp(42),dp(42)));help.setOnClickListener(v->controlsHelp());
        if(online){ActionButton exit=new ActionButton(this,"exit","Покинуть комнату",false);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(dp(42),dp(42));ep.leftMargin=dp(6);nav.addView(exit,ep);exit.setOnClickListener(v->leaveDialog());}
        LinearLayout seats=row();LinearLayout.LayoutParams seatsP=new LinearLayout.LayoutParams(-1,dp(48));seatsP.setMargins(0,dp(6),0,dp(6));frame.addView(seats,seatsP);
        for(Room.Seat p:view.players){
            LinearLayout seat=column();seat.setPadding(dp(7),dp(4),dp(7),0);seat.setBackground(shape(p.id.equals(view.turn)&&!view.finished?0xFF344D34:CARD,12));
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,-1,1);sp.setMargins(dp(2),0,dp(2),0);seats.addView(seat,sp);
            TextView name=label(seat,p.id.equals(view.me)?"Вы":p.name,12,INK,true);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label(seat,p.count+" · "+(p.opened?"в игре":"30+")+(p.bot?" · бот":""),10,MUTED,false);
        }
        scroll=new ScrollView(this);scroll.setFillViewport(true);frame.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));root=column();root.setPadding(dp(2),dp(4),dp(2),dp(12));scroll.addView(root);if(landscape){frame.removeView(seats);StringBuilder roster=new StringBuilder();for(Room.Seat p:view.players){if(roster.length()>0)roster.append("   ·   ");roster.append(p.id.equals(view.me)?"Вы":p.name).append(": ").append(p.count);}TextView summary=label(root,roster.toString(),11,MUTED,false);summary.setMaxLines(1);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);}
        if(view.finished){
            LinearLayout result=card(root);label(result,view.me.equals(view.winner)?"Вы победили!":"Партия завершена",24,LIME,true);
            for(Room.Seat p:view.players)label(result,p.name+"     "+(p.score>0?"+":"")+p.score,16,INK,p.id.equals(view.winner));
            LinearLayout rr=row();result.addView(rr);action(rr,"play","Ещё партия",true,!online||view.host.equals(view.me),()->{if(!online)newLocalDialog();else roomAction("rematch",new JsonObject());});
        }
        if(!landscape)label(root,view.message==null?"Соберите комбинацию":view.message,12,MUTED,false);
        LinearLayout table=column();table.setPadding(dp(8),dp(12),dp(8),dp(12));table.setBackground(shape(CARD,18));LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.topMargin=dp(8);root.addView(table,tp);
        eyebrow(table,"СТОЛ · "+(online?view.code:"ТРЕНИРОВКА")+" · БАНК "+view.poolCount);space(table,8);
        if(draft.isEmpty())label(table,"Перетащите сюда фишки или выберите их и нажмите +. Первый выход — от 30 очков.",13,MUTED,false);
        for(int i=0;i<draft.size();i++){
            final int index=i;List<Integer> meld=draft.get(i);
            TextView rowLabel=label(table,"РЯД "+(i+1)+(Rules.meldValue(meld)<0?" · не завершён":""),10,Rules.meldValue(meld)<0?0xFFFFD166:MUTED,true);
            HorizontalScrollView horizontal=new HorizontalScrollView(this);rowViewports.add(horizontal);if(i<oldXs.size()){int x=oldXs.get(i);horizontal.post(()->horizontal.scrollTo(x,0));}horizontal.setHorizontalScrollBarEnabled(false);LinearLayout tiles=row();horizontal.addView(tiles);table.addView(horizontal,new LinearLayout.LayoutParams(-1,dp(66)));
            addEndButton(tiles,index,false);
            for(int id:meld)addGameTile(tiles,id,index);
            addEndButton(tiles,index,true);tiles.setOnDragListener(dropTarget(index,null,true,false));space(table,6);
        }
        LinearLayout newRow=row();table.addView(newRow);ActionButton add=action(newRow,"plus","Создать новый ряд из выбранных фишек",false,myTurn(),()->placeAt(-1,null,true));add.setOnDragListener(dropTarget(-1,null,true,false));
        TextView dropHint=label(newRow,"Новый ряд · перетащите фишки на +",12,MUTED,false);dropHint.setLayoutParams(new LinearLayout.LayoutParams(0,-2,3));dropHint.setPadding(dp(8),0,0,0);dropHint.setOnDragListener(dropTarget(-1,null,true,false));
        LinearLayout dock=column();dock.setPadding(dp(8),dp(8),dp(8),dp(4));dock.setBackground(shape(CARD,18));LinearLayout.LayoutParams dockP=new LinearLayout.LayoutParams(-1,-2);dockP.topMargin=dp(6);frame.addView(dock,dockP);dock.setOnDragListener(dropTarget(-1,null,true,true));
        List<Integer> remaining=new ArrayList<>(view.rack);remaining.removeAll(Rules.flatten(draft));
        remaining.sort((a,b)->sortByNumber?Integer.compare(tileNumberKey(a),tileNumberKey(b)):Integer.compare(tileColorKey(a),tileColorKey(b)));
        LinearLayout dockTitle=row();dock.addView(dockTitle);
        TextView rackTitle=label(dockTitle,"ВАШИ ФИШКИ · "+remaining.size()+(selected.isEmpty()?"":" · выбрано "+selected.size()),11,INK,true);rackTitle.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        if(!selected.isEmpty()){ActionButton clear=new ActionButton(this,"clear","Снять выбор",false);dockTitle.addView(clear,new LinearLayout.LayoutParams(dp(34),dp(34)));clear.setOnClickListener(v->{selected.clear();gameScreen();});}
        clockLabel=new TextView(this);clockLabel.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);clockLabel.setTextSize(19);clockLabel.setGravity(Gravity.CENTER);clockLabel.setPadding(dp(10),dp(4),dp(10),dp(4));dockTitle.addView(clockLabel,new LinearLayout.LayoutParams(dp(86),dp(36)));
        int columns=Math.max(4,(getResources().getDisplayMetrics().widthPixels-dp(44))/dp(48));
        int maxRack=landscape?dp(64):Math.max(dp(64),(int)(getResources().getDisplayMetrics().heightPixels*.23f));
        ScrollView rackScroll=new ScrollView(this);rackViewport=rackScroll;rackScroll.post(()->rackScroll.scrollTo(0,oldRackY));LinearLayout rackTiles=column();rackScroll.addView(rackTiles);dock.addView(rackScroll,new LinearLayout.LayoutParams(-1,Math.min(maxRack,Math.max(dp(48),((remaining.size()+columns-1)/columns)*dp(64)))));
        LinearLayout tileRow=null;for(int i=0;i<remaining.size();i++){if(i%columns==0){tileRow=row();rackTiles.addView(tileRow);}addGameTile(tileRow,remaining.get(i),-1);}
        if(remaining.isEmpty())label(rackTiles,view.finished?"Все фишки выложены":"Все фишки на столе — подтвердите ход",12,LIME,false);
        space(dock,5);LinearLayout actions=row();dock.addView(actions);
        action(actions,"sort",sortByNumber?"Сортировать по цвету":"Сортировать по числу",false,true,()->{sortByNumber=!sortByNumber;gameScreen();});
        action(actions,"return","Вернуть выбранные свои фишки на подставку",false,myTurn()&&!selected.isEmpty(),this::returnToRack);
        action(actions,"plus","Новый ряд",false,myTurn()&&!selected.isEmpty(),()->placeAt(-1,null,true));
        action(actions,"undo","Восстановить стол на начало хода",false,myTurn()&&dirty,this::resetDraft);
        action(actions,"draw",view.poolCount==0?"Пропустить ход":"Взять фишку и закончить ход",false,myTurn(),()->{
            if(dirty)new AlertDialog.Builder(this).setTitle("Взять фишку?").setMessage("Черновик будет отменён, ход закончится.").setPositiveButton("Взять",(d,w)->draw()).setNegativeButton("Отмена",null).show();else draw();
        });
        action(actions,"check","Подтвердить ход",true,myTurn()&&dirty,this::commit);
        connectionLabel=label(dock,online?(draftSerial>syncedDraftSerial?"Сохраняем черновик…":"● По сети · черновик сохранён"):"● Тренировка",10,MUTED,false);
        if(landscape)connectionLabel.setVisibility(View.GONE);updateClock();ScrollView tableViewport=scroll;tableViewport.post(()->tableViewport.scrollTo(0,oldY));
    }
    private int tileColorKey(int id){return Rules.joker(id)?1000:Rules.color(id)*100+Rules.number(id);}
    private int tileNumberKey(int id){return Rules.joker(id)?1000:Rules.number(id)*10+Rules.color(id);}
    private void addEndButton(LinearLayout parent,int row,boolean after){
        ActionButton add=new ActionButton(this,"plus",(after?"Добавить в конец ряда ":"Добавить в начало ряда ")+(row+1),false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(44),dp(54));p.setMargins(dp(2),0,dp(2),0);parent.addView(add,p);add.setAlpha(myTurn()?1:.4f);add.setOnClickListener(v->placeAt(row,null,after));add.setOnDragListener(dropTarget(row,null,after,false));
    }
    private static final class TileDrag {
        final String room;final long revision;final List<Integer> tiles;
        TileDrag(String room,long revision,Collection<Integer> tiles){this.room=room;this.revision=revision;this.tiles=new ArrayList<>(tiles);}
    }
    private void addGameTile(LinearLayout parent,int id,int row){
        TileView tile=new TileView(this,id,selected.contains(id));parent.addView(tile,new LinearLayout.LayoutParams(dp(48),dp(64)));tile.setOnClickListener(v->toggle(id));
        tile.setOnLongClickListener(v->{
            if(!canEdit())return true;if(!me().opened&&!view.rack.contains(id)){toast("Первый выход — только из своих фишек");return true;}
            List<Integer> moving=selected.contains(id)?new ArrayList<>(selected):Collections.singletonList(id);
            TileDrag data=new TileDrag(view.code,view.revision,moving);dragging=true;
            if(!tile.startDragAndDrop(ClipData.newPlainText("rummikub-tile","tile"),new View.DragShadowBuilder(tile),data,0))dragging=false;
            return true;
        });
        tile.setOnDragListener(dropTarget(row,row<0?null:id,true,row<0));
    }
    private View.OnDragListener dropTarget(int row,Integer anchor,boolean after,boolean rack){
        return (target,event)->{
            if(!(event.getLocalState() instanceof TileDrag))return false;TileDrag drag=(TileDrag)event.getLocalState();
            boolean valid=myTurn()&&!requestBusy&&view.code.equals(drag.room)&&view.revision==drag.revision;
            switch(event.getAction()){
                case DragEvent.ACTION_DRAG_STARTED:return valid;
                case DragEvent.ACTION_DRAG_ENTERED:target.setAlpha(.65f);return true;
                case DragEvent.ACTION_DRAG_LOCATION:if(target instanceof TileView)((TileView)target).insertion(event.getX()<target.getWidth()/2f?-1:1);return true;
                case DragEvent.ACTION_DRAG_EXITED:case DragEvent.ACTION_DRAG_ENDED:target.setAlpha(1);if(target instanceof TileView)((TileView)target).insertion(0);if(event.getAction()==DragEvent.ACTION_DRAG_ENDED)dragging=false;return true;
                case DragEvent.ACTION_DROP:
                    target.setAlpha(1);dragging=false;if(!valid)return false;
                    if(rack){selected.clear();selected.addAll(drag.tiles);returnToRack();return true;}
                    if(anchor!=null&&drag.tiles.contains(anchor))return true;
                    moveTiles(drag.tiles,row,anchor,anchor==null?after:event.getX()>=target.getWidth()/2f);return true;
                default:return true;
            }
        };
    }
    private void toggle(int id){if(!canEdit())return;if(!me().opened&&!view.rack.contains(id)){toast("Стол можно менять после первого выхода");return;}if(!selected.remove(id))selected.add(id);gameScreen();}
    private void placeAt(int row,Integer anchor,boolean after){if(!canEdit())return;if(selected.isEmpty()){toast("Сначала выберите фишки или перетащите их");return;}moveTiles(new ArrayList<>(selected),row,anchor,after);}
    private void moveTiles(Collection<Integer> tiles,int row,Integer anchor,boolean after){
        if(!canEdit())return;
        if(!me().opened&&(!view.rack.containsAll(tiles)||(row>=0&&!Collections.disjoint(draft.get(row),Rules.flatten(view.board))))){toast("Первый выход собирается из своих фишек в новых рядах");return;}
        try{draft=DraftBoard.move(draft,tiles,row,anchor,after);selected.clear();markDirty();gameScreen();}catch(IllegalArgumentException invalid){toast(invalid.getMessage());}
    }
    private void returnToRack(){
        if(!canEdit())return;if(!view.rack.containsAll(selected)){toast("Фишки общего стола нельзя забирать себе");return;}
        for(List<Integer> meld:draft)meld.removeAll(selected);draft.removeIf(List::isEmpty);selected.clear();markDirty();gameScreen();
    }
    private void markDirty(){dirty=!draft.equals(view.board);draftSerial=Math.max(draftSerial,view.draftVersion)+1;if(online){handler.removeCallbacks(sendDraft);handler.postDelayed(sendDraft,120);}else{local.game.stage("local",view.revision,draftSerial,draft);saveLocal();}}
    private final Runnable sendDraft=()->editSignal();
    private void editSignal(){
        if(view==null||!myTurn()||editBusy||requestBusy||!screen.equals("game")||draftSerial<=syncedDraftSerial)return;
        JsonObject b=roomBody();b.addProperty("editing",dirty);b.add("draft",JSON.toJsonTree(draft));b.addProperty("draftVersion",draftSerial);
        String url=endpoint,session=token;int requestEpoch=epoch;long sent=draftSerial,revision=view.revision;editBusy=true;
        editTask=network.submit(()->{
            boolean ok=false;try{Api.request(url,session,"room/edit",b);ok=true;}catch(Exception ignored){ }
            final boolean saved=ok;handler.post(()->{if(isDestroyed()||requestEpoch!=epoch||view==null||view.revision!=revision)return;editBusy=false;editTask=null;
                if(saved)syncedDraftSerial=Math.max(syncedDraftSerial,sent);
                if(connectionLabel!=null)connectionLabel.setText(saved?(draftSerial>syncedDraftSerial?"Сохраняем черновик…":"● Черновик сохранён"):"Черновик ещё не сохранён · проверяем связь");
                if(draftSerial>sent&&myTurn())handler.post(sendDraft);
            });
        });
    }
    private void resetDraft(){if(!canEdit())return;draft=Rules.copyBoard(view.board);selected.clear();markDirty();gameScreen();}
    private void commit(){
        if(!canEdit())return;
        try{Rules.validateMove(view.board,view.rack,me().opened,draft);}catch(IllegalArgumentException e){toast(e.getMessage());return;}
        if(online){JsonObject b=new JsonObject();b.add("board",JSON.toJsonTree(draft));roomAction("play",b);}
        else{local.game.play("local",view.revision,draft,System.currentTimeMillis());saveLocal();accept(local.view("local"),true);}
    }
    private void draw(){if(!canEdit())return;if(online)roomAction("draw",new JsonObject());else{local.game.draw("local",view.revision,System.currentTimeMillis());saveLocal();accept(local.view("local"),true);}}
    private long remainingSeconds(){return view==null?0:TurnAlerts.seconds(view.deadline,System.currentTimeMillis());}
    private void updateClock(){
        if(clockLabel==null||view==null)return;
        long seconds=remainingSeconds();String value=view.finished?"✓":view.deadline==0?"∞":String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);
        clockLabel.setText(value);int urgency=TurnAlerts.urgency(view.deadline,System.currentTimeMillis());int color=urgency==2?0xFFFF6B6B:urgency==1?0xFFFFD166:LIME;
        clockLabel.setTextColor(color);clockLabel.setBackground(shape((color&0x00FFFFFF)|0x22000000,12));clockLabel.setContentDescription("Осталось на ход: "+(view.deadline==0?"без ограничения":seconds+" секунд"));
        if(active&&screen.equals("game")&&!view.finished){TurnAlerts.Event event=alerts.update(view.code,view.revision,myTurn(),view.deadline>0,seconds);if(event!=TurnAlerts.Event.NONE)signal(event==TurnAlerts.Event.WARNING);}
    }
    private void signal(boolean warning){
        if(tones!=null&&prefs.getBoolean("sound",true))tones.startTone(warning?ToneGenerator.TONE_PROP_BEEP2:ToneGenerator.TONE_PROP_ACK,warning?220:120);
        if(prefs.getBoolean("vibration",true)){android.os.Vibrator v=getSystemService(android.os.Vibrator.class);if(v!=null&&v.hasVibrator())v.vibrate(warning?VibrationEffect.createWaveform(new long[]{0,70,65,70},-1):VibrationEffect.createOneShot(65,VibrationEffect.DEFAULT_AMPLITUDE));}
    }
    private void controlsHelp(){
        ScrollView content=new ScrollView(this);LinearLayout list=column();list.setPadding(dp(16),dp(10),dp(16),dp(10));content.addView(list);
        String[][] items={{"bot","Робот — тренировка или управление ботами"},{"users","Игроки — сетевая игра и комнаты"},{"play","Треугольник — начать или продолжить партию"},{"key","Ключ — вход, регистрация или код комнаты"},{"settings","Шестерёнка — настройки и адрес сервера"},{"share","Три точки с линиями — поделиться кодом комнаты"},{"save","Дискета — сохранить настройки"},{"check","Подтвердить — завершить ход и сохранить весь допустимый стол"},{"plus","Плюс слева / справа — вставить выбранные фишки в начало / конец ряда. Плюс под столом — новый ряд"},{"undo","Стрелка назад — полностью вернуть стол к началу своего хода"},{"draw","Фишка с плюсом — взять из банка и закончить ход; черновик отменяется"},{"return","Стрелка вниз — вернуть выбранные свои фишки на подставку"},{"sort","Сортировка — переключить порядок по числу / цвету"},{"clear","Крестик — снять выделение"},{"home","Дом — вернуться в меню"},{"exit","Дверь — покинуть сетевую комнату"},{"help","Вопрос — эта справка"}};
        for(String[] item:items){LinearLayout r=row();list.addView(r);ActionButton icon=new ActionButton(this,item[0],item[1],false);r.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));TextView text=label(r,item[1],13,INK,false);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(10),dp(8),0,dp(8));text.setLayoutParams(p);}
        space(list,10);label(list,"Перетаскивание: удерживайте фишку и ведите на + или на левую / правую половину фишки в ряду. Зелёная черта показывает точку вставки. На подставку возвращаются только свои фишки. Выбранную группу тоже можно перетащить.",14,INK,false);
        space(list,10);label(list,"Таймер: жёлтый за 20 секунд, красный за 10. На 10 секундах своего хода звучит предупреждение и вибрация. При нуле учитывается последний сохранённый черновик (статус под кнопками). Правильные комбинации сохраняются; ошибочные свои фишки возвращаются. Незавершённые связанные перестройки общего стола восстанавливаются. Если допустимого хода нет, берётся одна фишка. Первый выход по-прежнему от 30 очков.",14,INK,false);
        new AlertDialog.Builder(this).setTitle("Руммикуб · кнопки и жесты").setView(content).setPositiveButton("Понятно",null).setNeutralButton("Правила",(d,w)->rules()).show();
    }
    private void botStep(){
        if(botBusy||local==null||local.game==null||local.game.finished||!local.game.current().bot)return;
        botBusy=true;Game copy=JSON.fromJson(JSON.toJson(local.game),Game.class);Room target=local;long revision=copy.revision;
        worker.submit(()->{
            List<List<Integer>> move=Bot.choose(copy.current().rack,copy.board,copy.current().opened);
            handler.postDelayed(()->{
                botBusy=false;if(isDestroyed()||local!=target||local.game.revision!=revision)return;
                if(move==null)local.game.draw(local.game.current().id,revision,System.currentTimeMillis());else local.game.play(local.game.current().id,revision,move,System.currentTimeMillis());
                saveLocal();if(!online&&screen.equals("game"))accept(local.view("local"),true);
            },700);
        });
    }
    private void saveLocal(){if(local!=null&&prefs!=null)prefs.edit().putString("local",JSON.toJson(local)).apply();}
    private JsonObject roomBody(){JsonObject b=new JsonObject();b.addProperty("code",view.code);b.addProperty("revision",view.revision);return b;}
    private void roomAction(String action,JsonObject extra){
        if(view==null)return;JsonObject b=roomBody();for(Map.Entry<String,JsonElement> e:extra.entrySet())b.add(e.getKey(),e.getValue());
        request("room/"+action,b,result->{if(action.equals("leave")){view=null;home();}else accept(JSON.fromJson(result,Room.View.class),true);});
    }
    private void leaveDialog(){new AlertDialog.Builder(this).setTitle("Покинуть комнату?").setMessage(view.finished||!view.started?"Вы сможете присоединиться к другой комнате.":"Ваше место до конца партии займёт бот.").setPositiveButton("Покинуть",(d,w)->roomAction("leave",new JsonObject())).setNegativeButton("Остаться",null).show();}
    private interface Result{void accept(JsonObject object);}
    private void request(String route,JsonObject body,Result callback){
        if(requestBusy){toast("Дождитесь ответа сервера");return;}requestBusy=true;int requestEpoch=epoch;String url=endpoint,session=token;
        requestStarted=SystemClock.elapsedRealtime();
        connectionDialog=new AlertDialog.Builder(this).setTitle("Подключение к игре")
            .setMessage("Ожидаем ответ сервера…\n\n"+url).setNegativeButton("Отмена",(d,w)->{epoch++;if(requestTask!=null)requestTask.cancel(true);requestBusy=false;dismissConnectionDialog();home();}).setCancelable(false).show();
        if(connectionLabel!=null)connectionLabel.setText(ServerWarmup.isRender(url)?"Подключаемся… Первое подключение может занять около минуты":"Подключаемся…");
        requestTask=network.submit(()->{
            try{JsonObject result=Api.request(url,session,route,body);handler.post(()->{if(isDestroyed()||requestEpoch!=epoch)return;requestBusy=false;requestTask=null;dismissConnectionDialog();callback.accept(result);});}
            catch(Exception e){handler.post(()->{if(isDestroyed()||requestEpoch!=epoch)return;requestBusy=false;requestTask=null;dismissConnectionDialog();networkError(e,true);});}
        });
    }
    private void poll(){
        if(pollBusy||requestBusy||editBusy||view==null)return;pollBusy=true;lastPoll=System.currentTimeMillis();JsonObject b=roomBody();
        long sent=draftSerial;if(myTurn()&&sent>syncedDraftSerial){b.add("draft",JSON.toJsonTree(draft));b.addProperty("draftVersion",sent);}
        String url=endpoint,session=token;int requestEpoch=epoch;
        pollTask=network.submit(()->{
            try{JsonObject result=Api.request(url,session,"room/state",b);handler.post(()->{if(isDestroyed()||requestEpoch!=epoch||!online)return;pollBusy=false;pollTask=null;if(b.has("draft"))syncedDraftSerial=Math.max(syncedDraftSerial,sent);accept(JSON.fromJson(result,Room.View.class),false);});}
            catch(Exception e){handler.post(()->{if(isDestroyed()||requestEpoch!=epoch)return;pollBusy=false;pollTask=null;networkError(e,false);});}
        });
    }
    private void networkError(Exception e,boolean show){
        String message=e instanceof Api.Failure?e.getMessage():ConnectionErrors.describe(e);
        if(connectionLabel!=null)connectionLabel.setText("○ "+message);
        if(e instanceof Api.Failure&&((Api.Failure)e).status==401){token="";vault.clear();auth(false);show=true;}
        if(show) {
            AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("Не удалось подключиться")
                .setMessage(message+"\n\nСервер: "+endpoint).setPositiveButton("Закрыть",null);
            if(!(e instanceof Api.Failure))dialog.setNeutralButton("Проверить в браузере",(d,w)->{
                try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(endpoint.replaceAll("/+$","")+"/health")));}
                catch(ActivityNotFoundException missing){toast("На телефоне не найден браузер");}
            });
            dialog.show();
        }
    }
    private void dismissConnectionDialog(){if(connectionDialog!=null){connectionDialog.dismiss();connectionDialog=null;}}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void rules(){
        String text="Цель — первым выложить все фишки. У каждого в начале 14; всего 106, включая 2 джокера.\n\n"+
            "ГРУППА: 3–4 одинаковых числа разных цветов. Например, три семёрки.\n\nПОСЛЕДОВАТЕЛЬНОСТЬ: от 3 чисел подряд одного цвета. После 13 нельзя ставить 1.\n\n"+
            "ПЕРВЫЙ ВЫХОД: один или несколько новых рядов суммой от 30, только из своих фишек. Джокер имеет значение заменённого числа.\n\n"+
            "ПОСЛЕ ВЫХОДА: добавляйте фишки и перестраивайте стол. В конце хода каждый ряд должен быть правильным, все старые фишки остаются на столе, добавляется хотя бы одна своя. Освобождённый джокер используется на столе в этот же ход.\n\n"+
            "НЕТ ХОДА: возьмите одну фишку — ход сразу закончится. Если банк пуст, пропустите. Полный круг пропусков завершает партию; выигрывает наименьшая сумма на подставке.\n\n"+
            "ТАЙМЕР: в сетевой комнате выбирается 60, 120 секунд или без ограничения. При истечении времени сохраняются допустимые комбинации. Ошибочные свои фишки возвращаются, незавершённые перестройки восстанавливаются. Если допустимого хода нет — одна фишка из банка. Для тренировки таймер выбирается при старте.\n\n"+
            "ОЧКИ: проигравшие теряют сумму оставшихся фишек, джокер стоит 30. Победитель получает их сумму. При пустом банке считаются разницы с минимальной суммой.\n\n"+
            "УПРАВЛЕНИЕ: нажмите фишки в нужном порядке, затем + у ряда. Удерживайте фишку для перетаскивания. Левая или правая половина фишки в ряду задаёт точку вставки. Плюсы есть с обеих сторон ряда. «Отменить ход» возвращает исходный стол. Точки внизу фишек помогают отличать цвета.\n\n"+
            "Основа: классические правила rummikub.com. Вариант приложения: один раунд, случайный первый игрок; при равенстве минимума после круга пропусков побеждает первый в порядке мест.";
        TextView t=new TextView(this);t.setText(text);t.setTextSize(15);t.setTextColor(INK);t.setPadding(dp(24),dp(12),dp(24),dp(16));t.setLineSpacing(dp(4),1);
        ScrollView s=new ScrollView(this);s.addView(t);new AlertDialog.Builder(this).setTitle("Как играть в Руммикуб").setView(s).setPositiveButton("За стол!",null).show();
    }
}
