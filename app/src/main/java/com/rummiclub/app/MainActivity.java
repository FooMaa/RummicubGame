package com.rummiclub.app;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
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
    private SharedPreferences prefs;
    private SessionVault vault;
    private String token = "", endpoint = "", screen = "home", playerName = "Игрок";
    private LinearLayout root;
    private ScrollView scroll;
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
                if (!online && screen.equals("game")) botStep();
                updateClock();
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("rummiclub", MODE_PRIVATE); vault = new SessionVault(this);
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
    @Override protected void onPause() { super.onPause(); active = false; handler.removeCallbacks(ticker); saveLocal(); }
    @Override protected void onDestroy() { super.onDestroy(); dismissConnectionDialog(); epoch++; handler.removeCallbacksAndMessages(null); worker.shutdownNow(); network.shutdownNow(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (view != null) { out.putString("view", JSON.toJson(view)); out.putString("draft", JSON.toJson(draft)); out.putBoolean("dirty", dirty); out.putBoolean("online", online); out.putString("screen", screen); }
    }
    @Override public void onBackPressed() {
        if (screen.equals("home")) super.onBackPressed();
        else if (dirty) new AlertDialog.Builder(this).setTitle("Вернуться в меню?").setMessage("Черновик будет отменён. В сетевой партии таймер продолжит идти.").setPositiveButton("В меню", (d,w) -> { resetDraft(); home(); }).setNegativeButton("Остаться", null).show();
        else home();
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
    private Button button(LinearLayout parent, String value, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setTextSize(14); b.setAllCaps(false); b.setTextColor(primary ? BG : INK);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setBackground(shape(primary ? LIME : 0xFF2B4038, 16));
        b.setPadding(dp(14), dp(8), dp(14), dp(8)); b.setMinHeight(dp(48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(10); parent.addView(b, params);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private void pair(LinearLayout parent, String a, Runnable aa, String b, Runnable bb) {
        LinearLayout r = row(); parent.addView(r);
        Button left = button(r, a, false, aa), right = button(r, b, false, bb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1); lp.setMargins(0, dp(8), dp(5), 0); left.setLayoutParams(lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(48), 1); rp.setMargins(dp(5), dp(8), 0, 0); right.setLayoutParams(rp);
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = column(); c.setPadding(dp(20),dp(20),dp(20),dp(20)); c.setBackground(shape(CARD,24));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin = dp(16); parent.addView(c,p); return c;
    }
    private void base(String section) {
        connectionLabel = null; clockLabel = null;
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG); scroll.setClipToPadding(false);
        root = column(); root.setPadding(dp(22),dp(20),dp(22),dp(28));
        scroll.addView(root); setContentView(scroll);
        LinearLayout nav = row(); root.addView(nav);
        TextView brand = new TextView(this); brand.setText("rummi club"); brand.setTextColor(INK); brand.setTextSize(21); brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        nav.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView tag = new TextView(this); tag.setText(section); tag.setTextColor(LIME); tag.setTextSize(10); tag.setLetterSpacing(.1f); tag.setPadding(dp(12),dp(9),dp(12),dp(9)); tag.setBackground(shape(CARD,20)); nav.addView(tag);
        brand.setContentDescription("Rummi Club — главное меню"); brand.setOnClickListener(v -> onBackPressed()); space(root,24);
    }
    private void navigate(String destination) {
        if (!screen.equals(destination)) {
            dismissConnectionDialog();
            epoch++; screen = destination;
            if (requestTask != null) requestTask.cancel(true);
            if (pollTask != null) pollTask.cancel(true);
            if (editTask != null) editTask.cancel(true);
            requestBusy = false; pollBusy = false;
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
                local = new Room(); local.code="TRAINING"; local.host="local"; local.turnSeconds=0;
                local.members.add(new Game.Player("local",playerName,false));
                String[] names={"Атлас","Луна","Орион"};
                for(int i=0;i<=which;i++) local.members.add(new Game.Player("bot-"+i,names[i],true));
                local.game=Game.start(local.members,new java.security.SecureRandom(),0,System.currentTimeMillis());
                saveLocal(); online=false; accept(local.view("local"),true);
            };
            if(local!=null && local.game!=null && !local.game.finished) new AlertDialog.Builder(this).setTitle("Начать заново?").setMessage("Текущая тренировка будет заменена.").setPositiveButton("Начать",(x,w)->start.run()).setNegativeButton("Отмена",null).show();
            else start.run();
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
        LinearLayout c=card(root); label(c,register?"Создать аккаунт":"Вход в клуб",22,INK,true);
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
        boolean boardChanged=reset||view==null||!Objects.equals(view.code,incoming.code)||view.revision!=incoming.revision||view.started!=incoming.started;
        boolean changed=boardChanged||!JSON.toJson(view.players).equals(JSON.toJson(incoming.players))||!Objects.equals(view.host,incoming.host);
        view=incoming;
        if(boardChanged){draft=Rules.copyBoard(view.board);selected.clear();dirty=false;}
        if(changed)renderRoom();
        if(connectionLabel!=null)connectionLabel.setText(online?"● Подключено · стол синхронизирован":"● Тренировка · без интернета");
    }
    private void renderRoom(){if(view.started){navigate("game");gameScreen();}else{navigate("lobby");lobby();}}
    private void lobby(){
        base("КОМНАТА");eyebrow(root,"ПРИГЛАСИТЕ СВОЮ КОМПАНИЮ");space(root,12);label(root,"Начнём, когда\nвсе соберутся.",32,INK,true);
        LinearLayout c=card(root);eyebrow(c,"КОД КОМНАТЫ");TextView code=label(c,view.code,36,LIME,true);code.setLetterSpacing(.15f);
        button(c,"Поделиться кодом",false,()->{
            Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TEXT,"Заходи в Rummi Club! Код комнаты: "+view.code+"\nСервер: "+endpoint);startActivity(Intent.createChooser(intent,"Пригласить друзей"));
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
    private boolean canEdit(){if(!myTurn()){toast("Дождитесь своего хода");return false;}if(requestBusy){toast("Ход отправляется…");return false;}return true;}
    private Room.Seat me(){for(Room.Seat p:view.players)if(p.id.equals(view.me))return p;throw new IllegalStateException();}
    private void gameScreen(){
        int oldY=scroll==null?0:scroll.getScrollY();base(online?view.code:"ТРЕНИРОВКА");root.setPadding(dp(14),dp(12),dp(14),dp(24));
        LinearLayout top=row();root.addView(top); TextView title=label(top,view.finished?"Партия завершена":myTurn()?"Ваш ход.":"Ход соперника",28,INK,true);title.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        clockLabel=new TextView(this);clockLabel.setTextColor(LIME);clockLabel.setTextSize(18);top.addView(clockLabel);updateClock();
        space(root,8);label(root,view.message==null?"Соберите свою первую комбинацию":view.message,13,MUTED,false);
        LinearLayout seats=row();space(root,16);root.addView(seats);
        for(Room.Seat p:view.players){
            LinearLayout seat=column();seat.setPadding(dp(8),dp(12),dp(8),dp(12));seat.setBackground(shape(p.id.equals(view.turn)&&!view.finished?0xFF344D34:CARD,16));
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,-2,1);sp.setMargins(dp(2),0,dp(2),0);seats.addView(seat,sp);
            TextView name=label(seat,p.id.equals(view.me)?"Вы":p.name,12,INK,true);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label(seat,p.count+" фишек",12,MUTED,false);label(seat,p.opened?"В игре":p.bot?"Бот · 30+":"Выход 30+",10,p.opened?LIME:MUTED,false);
        }
        if(view.finished){
            LinearLayout result=card(root);eyebrow(result,"РЕЗУЛЬТАТ");label(result,view.me.equals(view.winner)?"Отличная партия!":"До новой встречи за столом!",25,LIME,true);
            for(Room.Seat p:view.players)label(result,p.name+"     "+(p.score>0?"+":"")+p.score,16,INK,p.id.equals(view.winner));
            if(!online)button(result,"Ещё партию",true,this::newLocalDialog);else if(view.host.equals(view.me))button(result,"Ещё партию с этой компанией",true,()->roomAction("rematch",new JsonObject()));
        }
        LinearLayout table=card(root);table.setPadding(dp(14),dp(14),dp(14),dp(14));eyebrow(table,"ОБЩИЙ СТОЛ  ·  БАНК "+view.poolCount);space(table,10);
        if(draft.isEmpty()){
            label(table,"Первый ряд — за вами",18,INK,true);space(table,6);label(table,"Выберите фишки и создайте ряд. Первый выход — от 30 очков.",13,MUTED,false);space(table,4);
        }
        for(int i=0;i<draft.size();i++){
            final int index=i;List<Integer> meld=draft.get(i);
            label(table,"РЯД "+(i+1)+(Rules.meldValue(meld)<0?"  ·  нужно собрать комбинацию":""),10,Rules.meldValue(meld)<0?0xFFF2B18C:MUTED,true);
            HorizontalScrollView horizontal=new HorizontalScrollView(this);LinearLayout tiles=row();horizontal.addView(tiles);table.addView(horizontal,new LinearLayout.LayoutParams(-1,dp(66)));
            for(int id:meld)addTile(tiles,id,()->toggle(id),()->place(index,id));
            Button add=new Button(this);add.setText("+");add.setTextColor(LIME);add.setTextSize(22);add.setContentDescription("Добавить выбранные фишки в ряд "+(i+1));add.setBackground(shape(0xFF2B4038,12));tiles.addView(add,new LinearLayout.LayoutParams(dp(48),dp(54)));add.setOnClickListener(v->place(index,null));
            space(table,8);
        }
        if(myTurn())button(table,"+ Новый ряд",false,()->place(-1,null));
        if(myTurn()){
            space(root,16);eyebrow(root,me().opened?"СОБИРАЙТЕ И ПЕРЕСТРАИВАЙТЕ":"ПЕРВЫЙ ВЫХОД · МИНИМУМ 30 ОЧКОВ");
            label(root,selected.isEmpty()?"Нажмите на фишки, чтобы выбрать их. Удерживайте фишку в ряду, чтобы вставить выбранные перед ней.":"Выбрано: "+selected.size()+". Нажмите + у ряда или создайте новый.",12,MUTED,false);
        }
        LinearLayout rack=card(root);rack.setPadding(dp(12),dp(14),dp(12),dp(14));eyebrow(rack,"ВАША ПОДСТАВКА");space(rack,10);
        List<Integer> remaining=new ArrayList<>(view.rack);remaining.removeAll(Rules.flatten(draft));
        remaining.sort((a,b)->sortByNumber?Integer.compare(tileNumberKey(a),tileNumberKey(b)):Integer.compare(tileColorKey(a),tileColorKey(b)));
        int columns=Math.max(4,(getResources().getDisplayMetrics().widthPixels-dp(52))/dp(48));
        LinearLayout tileRow=null;for(int i=0;i<remaining.size();i++){if(i%columns==0){tileRow=row();rack.addView(tileRow);}int id=remaining.get(i);addTile(tileRow,id,()->toggle(id),()->toggle(id));}
        if(remaining.isEmpty())label(rack,"Все фишки на столе. Подтвердите ход!",14,LIME,false);
        pair(rack,sortByNumber?"По цвету":"По числу",()->{sortByNumber=!sortByNumber;gameScreen();},"Снять выбор",()->{selected.clear();gameScreen();});
        if(myTurn()&&!selected.isEmpty())button(rack,"Вернуть выбранные на подставку",false,this::returnToRack);
        if(myTurn()){
            Button finish=button(root,"Подтвердить ход   ✓",true,this::commit);finish.setEnabled(dirty);finish.setAlpha(dirty?1:.45f);
            pair(root,"Отменить ход",this::resetDraft,view.poolCount==0?"Пропустить":"Взять фишку",()->{
                if(dirty)new AlertDialog.Builder(this).setTitle("Взять фишку?").setMessage("Черновик будет отменён, ход закончится.").setPositiveButton("Взять",(d,w)->draw()).setNegativeButton("Отмена",null).show();else draw();
            });
        }
        pair(root,"Правила",this::rules,online?"Выйти из комнаты":"В меню",online?this::leaveDialog:this::home);
        connectionLabel=label(root,online?"● Подключено · стол синхронизирован":"● Тренировка · без интернета",11,MUTED,false);
        scroll.post(()->scroll.scrollTo(0,oldY));
    }
    private int tileColorKey(int id){return Rules.joker(id)?1000:Rules.color(id)*100+Rules.number(id);}
    private int tileNumberKey(int id){return Rules.joker(id)?1000:Rules.number(id)*10+Rules.color(id);}
    private void addTile(LinearLayout parent,int id,Runnable click,Runnable longClick){
        TileView tile=new TileView(this,id,selected.contains(id));parent.addView(tile,new LinearLayout.LayoutParams(dp(48),dp(64)));
        tile.setOnClickListener(v->click.run());tile.setOnLongClickListener(v->{longClick.run();return true;});
    }
    private void toggle(int id){if(!canEdit())return;if(!me().opened&&!view.rack.contains(id)){toast("Стол можно менять после первого выхода");return;}if(!selected.remove(id))selected.add(id);gameScreen();}
    private void place(int row,Integer anchor){
        if(!canEdit())return;if(selected.isEmpty()){toast("Сначала выберите фишки");return;}
        if(!me().opened&&row>=0&&!Collections.disjoint(draft.get(row),Rules.flatten(view.board))){toast("Первый выход собирается в новых рядах");return;}
        if(anchor!=null&&selected.contains(anchor)){toast("Точка вставки не должна входить в выбор");return;}
        List<Integer> destination=row<0?new ArrayList<>():draft.get(row);
        for(List<Integer> meld:draft)meld.removeAll(selected);
        int at=anchor==null?destination.size():destination.indexOf(anchor);
        if(at<0)at=destination.size();destination.addAll(at,new ArrayList<>(selected));
        if(row<0)draft.add(destination);draft.removeIf(List::isEmpty);selected.clear();markDirty();gameScreen();
    }
    private void returnToRack(){
        if(!canEdit())return;if(!view.rack.containsAll(selected)){toast("Фишки общего стола нельзя забирать себе");return;}
        for(List<Integer> meld:draft)meld.removeAll(selected);draft.removeIf(List::isEmpty);selected.clear();markDirty();gameScreen();
    }
    private void markDirty(){boolean was=dirty;dirty=!draft.equals(view.board);if(online&&was!=dirty)editSignal();}
    private void editSignal(){
        if(view==null)return;JsonObject b=roomBody();b.addProperty("editing",dirty);String url=endpoint,session=token;
        editTask=network.submit(()->{try{Api.request(url,session,"room/edit",b);}catch(Exception ignored){/* authoritative deadline still applies; next state poll reconciles */}});
    }
    private void resetDraft(){if(!canEdit())return;draft=Rules.copyBoard(view.board);selected.clear();markDirty();gameScreen();}
    private void commit(){
        if(!canEdit())return;
        try{Rules.validateMove(view.board,view.rack,me().opened,draft);}catch(IllegalArgumentException e){toast(e.getMessage());return;}
        if(online){JsonObject b=new JsonObject();b.add("board",JSON.toJsonTree(draft));roomAction("play",b);}
        else{local.game.play("local",view.revision,draft,System.currentTimeMillis());saveLocal();accept(local.view("local"),true);}
    }
    private void draw(){if(!canEdit())return;if(online)roomAction("draw",new JsonObject());else{local.game.draw("local",view.revision,System.currentTimeMillis());saveLocal();accept(local.view("local"),true);}}
    private void updateClock(){if(clockLabel!=null&&view!=null){String value=view.finished?"✓":view.deadline==0?"∞":Math.max(0,(view.deadline-System.currentTimeMillis()+999)/1000)+" с";if(!value.contentEquals(clockLabel.getText()))clockLabel.setText(value);}}
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
        if(pollBusy||requestBusy||view==null)return;pollBusy=true;lastPoll=System.currentTimeMillis();JsonObject b=roomBody();String url=endpoint,session=token;int requestEpoch=epoch;
        pollTask=network.submit(()->{
            try{JsonObject result=Api.request(url,session,"room/state",b);handler.post(()->{if(isDestroyed()||requestEpoch!=epoch||!online)return;pollBusy=false;pollTask=null;accept(JSON.fromJson(result,Room.View.class),false);});}
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
            "ТАЙМЕР: в сетевой комнате выбирается 60, 120 секунд или без ограничения. При истечении времени — одна фишка; при незавершённом черновике — три. Тренировка без таймера.\n\n"+
            "ОЧКИ: проигравшие теряют сумму оставшихся фишек, джокер стоит 30. Победитель получает их сумму. При пустом банке считаются разницы с минимальной суммой.\n\n"+
            "УПРАВЛЕНИЕ: нажмите фишки в нужном порядке, затем + у ряда. Удерживайте фишку в ряду для вставки перед ней. «Отменить ход» возвращает исходный стол. Точки внизу фишек помогают отличать цвета.\n\n"+
            "Основа: классические правила rummikub.com. Вариант приложения: один раунд, случайный первый игрок; при равенстве минимума после круга пропусков побеждает первый в порядке мест.";
        TextView t=new TextView(this);t.setText(text);t.setTextSize(15);t.setTextColor(INK);t.setPadding(dp(24),dp(12),dp(24),dp(16));t.setLineSpacing(dp(4),1);
        ScrollView s=new ScrollView(this);s.addView(t);new AlertDialog.Builder(this).setTitle("Как играть в Rummi Club").setView(s).setPositiveButton("За стол!",null).show();
    }
}
