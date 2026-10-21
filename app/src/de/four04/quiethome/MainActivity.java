package de.four04.quiethome;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.*;
import android.net.wifi.*;
import android.media.tv.*;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Small native TV launcher. All wallpaper rotation is local and stops when another app opens. */
public final class MainActivity extends Activity {
    private static final int WHITE = 0xfff6f8f7, MUTED = 0xffc3cecb, ACCENT = 0xffc0e7da;
    private static final String[] DEFAULT_DOCK = {"com.netflix.ninja","com.disney.disneyplus","com.cbs.ca","com.amazon.firetv.youtube","com.amazon.firebat","org.jellyfin.androidtv"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String,Bitmap> pictures = new LinkedHashMap<String,Bitmap>(16,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Bitmap> entry) { return size() > 14; }
    };
    private SharedPreferences prefs;
    private FrameLayout root, body;
    private LinearLayout nav;
    private FrameLayout gear, networkButton, inputsButton;
    private Dialog settingsDialog;
    private TextView date, clock;
    private ImageView background;
    private QuietVideoView backgroundVideo;
    private Bitmap currentWallpaper;
    private List<App> apps = new ArrayList<>();
    private List<String> dock = new ArrayList<>();
    private ResumeStore.Result resume = new ResumeStore.Result();
    private final List<BackgroundEntry> backgrounds = new ArrayList<>();
    private int page, wallpaperIndex, generation, wallpaperGeneration;
    private boolean visible, diagnosticMode;
    private View firstCard;
    private final Runnable tick = new Runnable() { @Override public void run() { updateClock(); main.postDelayed(this, 15000); }};
    private final Runnable rotate = new Runnable() { @Override public void run() { if (visible) nextWallpaper(); }};
    private final Runnable idleScreensaver = new Runnable() { @Override public void run() {
        if(!visible||settingsDialog!=null)return;
        try{startActivity(new Intent(MainActivity.this,ScreensaverActivity.class).putExtra("idle",true));}catch(RuntimeException ex){android.util.Log.w("QuietHome","Could not start screensaver",ex);}
    }};
    private static final class App {
        String pkg, label; Drawable icon; Intent intent;
    }
    private static final class BackgroundEntry {
        final File file;final String asset,name;final boolean video;
        BackgroundEntry(File file,String name,boolean video){this.file=file;this.asset=null;this.name=name;this.video=video;}
        BackgroundEntry(String asset,String name){this.file=null;this.asset=asset;this.name=name;this.video=false;}
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("quiet", MODE_PRIVATE);
        dock.addAll(Arrays.asList(prefs.getString("dock",TextUtils.join(",",DEFAULT_DOCK)).split(",")));
        dock.removeAll(Collections.singleton(""));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this); root.setBackgroundColor(0xff152b32); setContentView(root);
        backgroundVideo=new QuietVideoView(this,"home-background");backgroundVideo.setVisibility(View.GONE);root.addView(backgroundVideo,new FrameLayout.LayoutParams(-1,-1));
        background = new ImageView(this); background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(background,new FrameLayout.LayoutParams(-1,-1));
        View tint = new View(this);
        tint.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x6606090d,0x1106090d,0xdd06090d}));
        root.addView(tint,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout frame = new LinearLayout(this); frame.setOrientation(LinearLayout.VERTICAL); frame.setPadding(dp(36),dp(24),dp(36),dp(20));
        root.addView(frame,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout timeBlock = new LinearLayout(this); timeBlock.setOrientation(LinearLayout.VERTICAL);
        date = text("",12,MUTED); date.setLetterSpacing(0.16f); timeBlock.addView(date);
        clock = text("",48,WHITE); clock.setTypeface(android.graphics.Typeface.create("sans-serif-light",0)); timeBlock.addView(clock);
        header.addView(timeBlock,new LinearLayout.LayoutParams(0,dp(92),1));
        TextView brand = text("Quiet Home",13,MUTED); brand.setPadding(0,dp(9),0,0);
        header.addView(brand);
        inputsButton=headerControl(header,"hdmi","HDMI-Eingänge",()->{diagnosticMode=false;inputSettings();});
        networkButton=headerControl(header,"wifi","Netzwerk",()->{diagnosticMode=false;networkSettings();});
        gear=headerControl(header,"gear","Einstellungen",()->{diagnosticMode=false;settings();});
        frame.addView(header);
        body = new FrameLayout(this); frame.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout navOuter = new LinearLayout(this); navOuter.setGravity(Gravity.CENTER); navOuter.setPadding(0,dp(16),0,0);
        nav = new LinearLayout(this); nav.setGravity(Gravity.CENTER); nav.setPadding(dp(7),dp(6),dp(7),dp(6)); nav.setBackground(glass(false,28));
        navOuter.addView(nav,new LinearLayout.LayoutParams(-2,dp(54))); frame.addView(navOuter);
        String[] names = {"Home","Weiterschauen","Apps"};
        for (int i=0;i<names.length;i++) {
            final int index=i; TextView button=text(names[i],15,WHITE); button.setGravity(Gravity.CENTER); button.setFocusable(true);
            button.setPadding(dp(20),0,dp(20),0);
            button.setOnFocusChangeListener((v,focus)->{ styleNav(); if(focus) switchPage(index); });
            button.setOnClickListener(v->{switchPage(index);if(firstCard!=null)firstCard.requestFocus();});
            nav.addView(button,new LinearLayout.LayoutParams(-2,-1));
        }
        loadBackgroundEntries();
        wallpaperIndex=prefs.getInt("wallpaperIndex",0);
        if(!backgrounds.isEmpty()) showWallpaper(wallpaperIndex%backgrounds.size());
        page="continue".equals(getIntent().getStringExtra("page"))?1:0;
        updateClock(); styleNav();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        loadBackgroundEntries();
        diagnosticMode=intent.getBooleanExtra("diagnostic",false);
        if(settingsDialog!=null)settingsDialog.dismiss();
        switchPage("continue".equals(intent.getStringExtra("page"))?1:0);
        if(nav.hasFocus())nav.getChildAt(page).requestFocus();
        if(intent.getBooleanExtra("nextWallpaper",false))nextWallpaper();
        refresh();
        String photo=intent.getStringExtra("wallpaper");
        if(photo!=null)for(int i=0;i<backgrounds.size();i++)if(photo.equals(backgrounds.get(i).name)){wallpaperIndex=i;prefs.edit().putInt("wallpaperIndex",i).apply();showWallpaper(i);break;}
        String localKind=intent.getStringExtra("localBackgroundKind");
        if(localKind!=null)for(int i=0;i<backgrounds.size();i++){BackgroundEntry item=backgrounds.get(i);if(item.file!=null&&(!"video".equals(localKind)||item.video)){wallpaperIndex=i;prefs.edit().putInt("wallpaperIndex",i).apply();showWallpaper(i);break;}}
        String requested=intent.getStringExtra("page");
        if("settings".equals(requested))main.post(this::settings);
        if("network".equals(requested))main.post(this::networkSettings);
        if("inputs".equals(requested))main.post(this::inputSettings);
        if("wallpapers".equals(requested))main.post(this::wallpaperSettings);
        if("screensaver".equals(requested))main.post(this::screensaverSettings);
    }
    @Override protected void onResume() {
        super.onResume(); visible=true;
        main.removeCallbacks(tick); main.post(tick);
        loadBackgroundEntries();if(!backgrounds.isEmpty()){wallpaperIndex=Math.floorMod(wallpaperIndex,backgrounds.size());showWallpaper(wallpaperIndex);}
        scheduleScreensaver();
        refresh();
    }
    @Override protected void onPause() {
        visible=false; generation++;wallpaperGeneration++;main.removeCallbacks(tick); main.removeCallbacks(rotate); main.removeCallbacks(idleScreensaver);backgroundVideo.stop();backgroundVideo.setVisibility(View.GONE);super.onPause();
    }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); io.shutdownNow();if(currentWallpaper!=null&&!currentWallpaper.isRecycled())currentWallpaper.recycle();currentWallpaper=null;super.onDestroy(); }
    @Override public void onUserInteraction(){super.onUserInteraction();scheduleScreensaver();}
    private void scheduleScreensaver(){main.removeCallbacks(idleScreensaver);if(!visible)return;int minutes=prefs.getInt("screensaverMinutes",5);if(minutes>0)main.postDelayed(idleScreensaver,minutes*60000L);}
    private int dp(float n){ return Math.round(getResources().getDisplayMetrics().density*n); }
    private TextView text(String value,float size,int color) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setFontFeatureSettings("tnum"); return v;
    }
    private GradientDrawable glass(boolean focus,int radius) {
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,focus?new int[]{0xe7d2eee5,0xe5b2d7cb}:new int[]{0xbd35474a,0xb51c282e});
        d.setCornerRadius(dp(radius)); d.setStroke(dp(1),focus?0xffedfff8:0x66e6efed); return d;
    }
    private void focusStyle(View v) {
        v.setFocusable(true); v.setFocusableInTouchMode(false); v.setBackground(glass(false,16));
        v.setOnFocusChangeListener((view,focused)->{
            view.setBackground(glass(focused,16)); view.animate().scaleX(focused?1.035f:1).scaleY(focused?1.035f:1).setDuration(130).start();
            if(view instanceof LinearLayout) {
                LinearLayout group=(LinearLayout)view;
                for(int i=0;i<group.getChildCount();i++) if(group.getChildAt(i) instanceof TextView)((TextView)group.getChildAt(i)).setTextColor(focused?0xff17342e:WHITE);
            }
            if(view instanceof TextView)((TextView)view).setTextColor(focused?0xff17342e:WHITE);
        });
    }
    private void updateClock() {
        Date now=new Date(); Locale locale=Locale.getDefault();
        date.setText(new SimpleDateFormat("EEE, dd. MMM yyyy",locale).format(now).toUpperCase(locale));
        clock.setText(android.text.format.DateFormat.getTimeFormat(this).format(now));
        if(networkButton!=null)networkButton.setContentDescription("Netzwerk: "+networkName());
    }
    private void styleNav() {
        if(nav==null)return;
        for(int i=0;i<nav.getChildCount();i++) {
            TextView button=(TextView)nav.getChildAt(i); boolean active=i==page;
            button.setBackground(active?glass(true,23):button.hasFocus()?glass(false,23):null); button.setTextColor(active?0xff17342e:WHITE);
        }
    }
    private void switchPage(int next){ if(page==next && body.getChildCount()>0)return; page=next; render(); styleNav(); }
    private void refresh() {
        final int request=++generation;
        io.execute(()->{
            List<App> found=installedApps(); ResumeStore.Result entries=ResumeStore.load(this);
            android.util.Log.i("QuietHome","Resume source: "+entries.providerStatus+" Entries: "+entries.items.size());
            main.post(()->{ if(!visible || request!=generation)return; apps=found; resume=entries; render(); if(firstCard!=null && !nav.hasFocus() && !gear.hasFocus() && !networkButton.hasFocus() && !inputsButton.hasFocus() && settingsDialog==null)firstCard.requestFocus(); });
        });
    }
    private List<App> installedApps() {
        PackageManager pm=getPackageManager(); Map<String,App> unique=new LinkedHashMap<>();
        for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}) {
            Intent query=new Intent(Intent.ACTION_MAIN).addCategory(category);
            for(ResolveInfo info:pm.queryIntentActivities(query,0)) {
                String pkg=info.activityInfo.packageName;
                if(pkg.equals(getPackageName()) || unique.containsKey(pkg))continue;
                if((info.activityInfo.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)!=0 && !ResumeStore.STREAMING.contains(pkg))continue;
                App app=new App(); app.pkg=pkg; app.label=String.valueOf(info.loadLabel(pm));
                app.intent=new Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(new ComponentName(pkg,info.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { app.icon=info.loadIcon(pm); } catch(RuntimeException ignored) { }
                unique.put(pkg,app);
            }
        }
        List<App> list=new ArrayList<>(unique.values()); Collections.sort(list,(a,b)->a.label.compareToIgnoreCase(b.label)); return list;
    }
    private App find(String pkg){ for(App a:apps)if(a.pkg.equals(pkg))return a;return null; }
    private String appName(String pkg){ App app=find(pkg); return app==null?pkg:app.label; }
    private void openApp(String pkg){ App app=find(pkg); if(app==null){toast("Diese App ist nicht verfügbar.");return;} launch(app.intent); }
    private void launch(Intent intent){try{startActivity(intent);}catch(RuntimeException ex){toast("Die App konnte nicht geöffnet werden.");}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void render() {
        body.removeAllViews(); firstCard=null;
        body.setBackground(page==0?null:glass(false,18));
        int inset=page==0?0:dp(16); body.setPadding(inset,inset,inset,inset);
        if(page==0)renderHome(); else if(page==1)renderResume(); else renderApps();
    }
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void renderHome() {
        LinearLayout content=vertical();
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM); lp.bottomMargin=dp(4); body.addView(content,lp);
        TextView label=text("DEINE STREAMING-APPS",11,MUTED); label.setLetterSpacing(0.14f); label.setPadding(dp(3),0,0,dp(12));content.addView(label);
        LinearLayout row=new LinearLayout(this);row.setClipChildren(false);row.setClipToPadding(false);row.setPadding(dp(3),dp(4),dp(3),dp(4));content.addView(row);
        List<App> available=new ArrayList<>();for(String pkg:dock){App app=find(pkg);if(app!=null)available.add(app);}
        if(available.isEmpty()){content.addView(text("Wähle deine Apps in den Einstellungen.",17,WHITE));return;}
        for(int i=0;i<Math.min(available.size(),8);i++){
            App app=available.get(i);View tile=appTile(app);LinearLayout.LayoutParams card=new LinearLayout.LayoutParams(0,dp(94),1);if(i>0)card.leftMargin=dp(10);row.addView(tile,card);
            if(firstCard==null)firstCard=tile;
        }

    }
    private View appTile(App app){
        LinearLayout tile=vertical();tile.setGravity(Gravity.CENTER);tile.setPadding(dp(8),dp(8),dp(8),dp(8));focusStyle(tile);tile.setContentDescription(app.label);tile.setNextFocusUpId(gear.getId());
        ImageView icon=new RoundedIconView(this);if(app.icon!=null)icon.setImageDrawable(app.icon);tile.addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
        TextView label=text(app.label,12,WHITE);label.setSingleLine();label.setEllipsize(TextUtils.TruncateAt.END);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(8),0,0);tile.addView(label,new LinearLayout.LayoutParams(-1,-2));
        tile.setOnClickListener(v->launch(app.intent));tile.setOnLongClickListener(v->{editApp(app);return true;});return tile;
    }
    private void renderApps(){
        LinearLayout layout=vertical();body.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        TextView title=text("Alle Apps",23,WHITE);title.setPadding(dp(3),dp(4),0,dp(12));layout.addView(title);
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);scroll.setPadding(dp(6),dp(6),dp(6),dp(6));layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout grid=vertical();scroll.addView(grid);int columns=6;
        for(int start=0;start<apps.size();start+=columns){
            LinearLayout row=new LinearLayout(this);row.setClipChildren(false);row.setPadding(0,dp(4),0,dp(7));grid.addView(row);
            for(int j=0;j<columns;j++){
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(92),1);if(j>0)lp.leftMargin=dp(10);
                if(start+j<apps.size()){View v=appTile(apps.get(start+j));row.addView(v,lp);if(firstCard==null)firstCard=v;}else row.addView(new View(this),lp);
            }
        }
    }
    private void renderResume(){
        LinearLayout layout=vertical();body.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        TextView title=text("Weiterschauen",26,WHITE);title.setPadding(dp(3),dp(6),0,dp(8));layout.addView(title);
        if(resume.items.isEmpty()){
            TextView empty=text("Hier erscheinen deine freigegebenen Filme und Serien.",18,WHITE);empty.setPadding(dp(3),dp(14),0,dp(8));layout.addView(empty);
            TextView explanation=text(resume.providerStatus+"\n\nOptionaler Medienverlauf kann Titel aus unterstützten Apps ab jetzt lokal merken. Er synchronisiert keine vollständigen Netflix-, Disney+- oder Paramount+-Konten.",13,MUTED);explanation.setMaxWidth(dp(640));explanation.setPadding(dp(3),0,0,dp(16));layout.addView(explanation);
            TextView setup=text("Medienverlauf einrichten",14,WHITE);setup.setGravity(Gravity.CENTER);setup.setPadding(dp(18),dp(10),dp(18),dp(10));focusStyle(setup);setup.setOnClickListener(v->mediaAccess());layout.addView(setup,new LinearLayout.LayoutParams(-2,-2));firstCard=setup;
            return;
        }
        TextView info=text("Fortsetzen = Wiedergabelink   ·   App öffnen = zuletzt erkannter Titel, ohne garantierten Episodensprung",11,MUTED);info.setPadding(dp(3),0,0,dp(14));layout.addView(info);
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setClipToPadding(false);scroll.setPadding(dp(6),dp(8),dp(6),dp(8));layout.addView(scroll);
        LinearLayout row=new LinearLayout(this);row.setClipChildren(false);scroll.addView(row);
        for(ResumeStore.Item item:resume.items){
            LinearLayout tile=vertical();tile.setPadding(dp(12),dp(12),dp(12),dp(12));focusStyle(tile);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(220),dp(187));lp.rightMargin=dp(12);row.addView(tile,lp);
            ImageView art=new ImageView(this);art.setScaleType(ImageView.ScaleType.CENTER_CROP);art.setBackgroundColor(0xff1b343b);tile.addView(art,new LinearLayout.LayoutParams(-1,dp(86)));
            App app=find(item.pkg);if(app!=null && app.icon!=null)art.setImageDrawable(app.icon);loadArt(item.art,art);
            TextView name=text(item.title,15,WHITE);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);name.setPadding(0,dp(9),0,0);tile.addView(name);
            TextView service=text(appName(item.pkg)+" · "+(item.direct?"Fortsetzen":"App öffnen"),10,MUTED);service.setSingleLine();service.setEllipsize(TextUtils.TruncateAt.END);tile.addView(service);
            if(item.duration>0){ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgress((int)Math.min(1000,1000.0*item.position/item.duration));tile.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));}
            tile.setOnClickListener(v->resumeItem(item));tile.setContentDescription(item.title+", "+appName(item.pkg));if(firstCard==null)firstCard=tile;
        }
    }
    private void resumeItem(ResumeStore.Item item){
        if(item.direct){
            try{Intent intent=Intent.parseUri(item.uri,Intent.URI_INTENT_SCHEME);intent.setSelector(null);intent.setComponent(null);intent.setPackage(item.pkg);intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(intent.resolveActivity(getPackageManager())!=null){startActivity(intent);return;}}
            catch(Exception ignored){}
        }
        if(item.activity!=null){try{item.activity.send();return;}catch(PendingIntent.CanceledException ignored){}}
        openApp(item.pkg);
    }
    private void loadArt(String uri,ImageView image){
        if(uri==null||uri.isEmpty())return;image.setTag(uri);
        Bitmap cached=pictures.get(uri);if(cached!=null){image.setImageBitmap(cached);return;}
        final int request=generation;
        io.execute(()->{
            Bitmap bitmap=null;HttpURLConnection connection=null;
            try{
                Uri parsed=Uri.parse(uri);InputStream input;
                if("https".equals(parsed.getScheme())){
                    connection=(HttpURLConnection)new URL(uri).openConnection();connection.setConnectTimeout(4000);connection.setReadTimeout(4000);connection.setInstanceFollowRedirects(false);
                    if(connection.getResponseCode()!=200)return;input=connection.getInputStream();
                }else if("content".equals(parsed.getScheme())||"file".equals(parsed.getScheme()))input=getContentResolver().openInputStream(parsed);else return;
                try(InputStream stream=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int count,total=0;
                    while((count=stream.read(buffer))!=-1){total+=count;if(total>3*1024*1024)return;out.write(buffer,0,count);}
                    byte[] bytes=out.toByteArray();BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    options.inSampleSize=1;while(options.outWidth/options.inSampleSize>700||options.outHeight/options.inSampleSize>700)options.inSampleSize*=2;
                    options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.RGB_565;bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                }
            }catch(Exception ignored){}finally{if(connection!=null)connection.disconnect();}
            final Bitmap ready=bitmap;if(ready!=null)main.post(()->{if(isDestroyed()||request!=generation)return;pictures.put(uri,ready);if(uri.equals(image.getTag()))image.setImageBitmap(ready);});
        });
    }
    private void loadBackgroundEntries(){
        String selected=backgrounds.isEmpty()?null:backgrounds.get(Math.floorMod(wallpaperIndex,backgrounds.size())).name;backgrounds.clear();Set<String> seen=new HashSet<>();
        File[] roots=getExternalMediaDirs();if(roots!=null)for(File root:roots)if(root!=null)addBackgroundFolder(new File(root,"Screensaver"),seen);
        addBackgroundFolder(getExternalFilesDir("Screensaver"),seen);
        if(selected!=null)for(int i=0;i<backgrounds.size();i++)if(selected.equals(backgrounds.get(i).name)){wallpaperIndex=i;break;}
        android.util.Log.i("QuietHome","Shared background/screensaver media: "+backgrounds.size());
    }
    private void addBackgroundFolder(File folder,Set<String> seen){File[] files=folder==null?null:folder.listFiles();if(files==null)return;Arrays.sort(files,(a,b)->a.getName().compareToIgnoreCase(b.getName()));for(File file:files){if(!file.isFile()||!seen.add(file.getAbsolutePath()))continue;String lower=file.getName().toLowerCase(Locale.ROOT);if(lower.matches(".*\\.(jpg|jpeg|png|webp)$"))backgrounds.add(new BackgroundEntry(file,file.getName(),false));else if(lower.matches(".*\\.(mp4|m4v|webm|mkv)$"))backgrounds.add(new BackgroundEntry(file,file.getName(),true));}}
    private void nextWallpaper(){if(backgrounds.isEmpty())return;wallpaperIndex=(wallpaperIndex+1)%backgrounds.size();prefs.edit().putInt("wallpaperIndex",wallpaperIndex).apply();showWallpaper(wallpaperIndex);}
    private void showWallpaper(int index){
        if(backgrounds.isEmpty())return;main.removeCallbacks(rotate);final int token=++wallpaperGeneration;final BackgroundEntry entry=backgrounds.get(Math.floorMod(index,backgrounds.size()));
        backgroundVideo.stop();backgroundVideo.setVisibility(View.GONE);background.setVisibility(View.VISIBLE);
        if(entry.video){
            backgroundVideo.setVisibility(View.VISIBLE);
            backgroundVideo.play(entry.file.getAbsolutePath(),backgrounds.size()==1,true,()->{if(!visible||token!=wallpaperGeneration)return;backgroundVideo.setVisibility(View.VISIBLE);background.setVisibility(View.GONE);},()->{backgroundVideo.setVisibility(View.GONE);if(visible&&token==wallpaperGeneration)main.postDelayed(rotate,500);},()->{android.util.Log.w("QuietHome","Background video failed: "+entry.name);backgroundVideo.setVisibility(View.GONE);background.setVisibility(View.VISIBLE);if(visible&&token==wallpaperGeneration)main.postDelayed(rotate,1500);});
            return;
        }
        io.execute(()->{
            Bitmap bitmap=null;
            try(InputStream in=entry.file!=null?new BufferedInputStream(new FileInputStream(entry.file)):getAssets().open(entry.asset)){bitmap=decodeBackground(in);if(bitmap==null){android.util.Log.e("QuietHome","Background decoder returned null: "+entry.name);return;}}
            catch(IOException|RuntimeException ex){android.util.Log.e("QuietHome","Background load failed: "+entry.name,ex);}
            final Bitmap ready=bitmap;if(ready!=null)main.post(()->{
                if(isDestroyed()||!visible||token!=wallpaperGeneration){ready.recycle();return;}Bitmap previous=currentWallpaper;currentWallpaper=ready;
                // A root drawable also covers firmware-specific ImageView/layout issues.
                BitmapDrawable drawable=new BitmapDrawable(getResources(),ready);drawable.setGravity(Gravity.FILL);
                root.setBackground(drawable);background.setImageBitmap(ready);background.invalidate();
                if(previous!=null&&previous!=ready&&!previous.isRecycled())previous.recycle();android.util.Log.i("QuietHome","Background applied: "+entry.name+" view="+background.getWidth()+"x"+background.getHeight());
                main.postDelayed(rotate,Math.max(30,prefs.getInt("wallpaperSeconds",300))*1000L);
            });
        });
    }
    private Bitmap decodeBackground(InputStream input)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1)out.write(buffer,0,count);byte[] bytes=out.toByteArray();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);int sample=1,target=Math.max(getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels);while(Math.max(bounds.outWidth,bounds.outHeight)/sample>target*2)sample*=2;BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);}
    private void saveDock(){prefs.edit().putString("dock",TextUtils.join(",",dock)).apply();render();}
    private FrameLayout headerControl(LinearLayout header,String kind,String description,Runnable action){
        FrameLayout control=new FrameLayout(this);control.setId(View.generateViewId());control.setFocusable(true);control.setContentDescription(description);control.setBackground(glass(false,22));
        QuietIconView icon=new QuietIconView(this,kind);control.addView(icon,new FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER));
        control.setOnFocusChangeListener((v,focus)->{control.setBackground(glass(focus,22));icon.setColor(focus?0xff17342e:WHITE);});control.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(dp(44),dp(44));space.leftMargin=dp(10);header.addView(control,space);return control;
    }
    private String networkName(){
        try{
            ConnectivityManager manager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);if(manager==null)return "Nicht verfügbar";
            Network active=manager.getActiveNetwork();NetworkCapabilities caps=manager.getNetworkCapabilities(active);
            if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "Ethernet verbunden";
            boolean wifi=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){
                for(Network candidate:manager.getAllNetworks()){
                    NetworkCapabilities physical=manager.getNetworkCapabilities(candidate);if(physical==null)continue;
                    if(physical.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "Ethernet · VPN aktiv";
                    if(physical.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))wifi=true;
                }
            }
            if(wifi){
                WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);WifiInfo info=wm==null?null:wm.getConnectionInfo();String name=info==null?null:info.getSSID();
                if(name!=null&&!name.equals("<unknown ssid>")&&!name.equals("0x")&&!name.isEmpty())return "WLAN · "+name.replace("\"","");
                return "WLAN verbunden";
            }
            return active==null?"Nicht verbunden":"Netzwerk verbunden";
        }catch(RuntimeException ex){return "Netzwerkstatus nicht verfügbar";}
    }
    private void openSystem(Intent... choices){
        for(Intent choice:choices){try{startActivity(choice);return;}catch(RuntimeException ignored){}}
        toast("Diese Einstellung ist auf diesem TV nicht direkt verfügbar.");
    }
    private void networkSettings(){
        LinearLayout content=panel("Netzwerk",networkName(),this::settings);
        settingRow(content,"WLAN & Ethernet","Verbindung in den TV-Einstellungen verwalten",null,()->openSystem(new Intent(Settings.ACTION_WIFI_SETTINGS),new Intent(Settings.ACTION_WIRELESS_SETTINGS),new Intent(Settings.ACTION_SETTINGS)));
        settingRow(content,"Status aktualisieren",null,"↻",this::networkSettings);
        paragraph(content,"Ein Netzwerkname erscheint nur, wenn Fire OS ihn freigibt. Quiet Home benötigt dafür keinen Standortzugriff.");
    }
    private void inputSettings(){
        LinearLayout content=panel("HDMI-Eingänge","Deine Geräte und die vom TV gemeldete Verbindung",this::settings);int found=0;
        try{
            TvInputManager manager=(TvInputManager)getSystemService(TV_INPUT_SERVICE);
            if(manager!=null)for(TvInputInfo info:manager.getTvInputList()){
                if(info.getType()!=TvInputInfo.TYPE_HDMI||info.getParentId()!=null)continue;
                CharSequence custom=info.loadCustomLabel(this);String label=String.valueOf(custom==null?info.loadLabel(this):custom);
                int state=manager.getInputState(info.getId());String status=state==TvInputManager.INPUT_STATE_CONNECTED?"Verbunden":state==TvInputManager.INPUT_STATE_CONNECTED_STANDBY?"Standby":"Nicht verbunden";
                settingRow(content,label,status,null,()->openSystem(new Intent(Intent.ACTION_VIEW,TvContract.buildChannelUriForPassthroughInput(info.getId())),new Intent("com.amazon.tv.inputpreference.action.LAUNCH_INPUTS"),new Intent(Settings.ACTION_SETTINGS)));found++;
            }
        }catch(RuntimeException ex){android.util.Log.w("QuietHome","TV input list unavailable",ex);}
        if(found==0)paragraph(content,"Fire OS gibt hier keine direkte HDMI-Liste frei. Über die TV-Eingangsauswahl kannst du trotzdem zu deinen Geräten wechseln.");
        settingRow(content,"TV-Eingangsauswahl öffnen","HDMI-Geräte auswählen oder umbenennen",null,()->openSystem(new Intent("com.amazon.tv.inputpreference.action.LAUNCH_INPUTS"),new Intent("android.settings.TV_INPUT_SETTINGS"),new Intent(Settings.ACTION_SETTINGS)));
    }
    private LinearLayout panel(String title,String subtitle,Runnable back){
        if(settingsDialog!=null)settingsDialog.dismiss();Dialog dialog=new Dialog(this);settingsDialog=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell=vertical();shell.setPadding(dp(24),dp(22),dp(24),dp(20));
        GradientDrawable surface=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xf02e4245,0xf017242a});surface.setCornerRadius(dp(26));surface.setStroke(dp(1),0x66d6ece5);shell.setBackground(surface);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);LinearLayout titles=vertical();titles.addView(text(title,25,WHITE));
        TextView caption=text(subtitle,11,MUTED);caption.setPadding(0,dp(6),dp(6),0);titles.addView(caption);heading.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout close=new FrameLayout(this);close.setContentDescription(back==null?"Schließen":"Zurück");close.setFocusable(true);close.setBackground(glass(false,18));
        QuietIconView icon=new QuietIconView(this,back==null?"close":"back");close.addView(icon,new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER));
        close.setOnFocusChangeListener((v,focus)->{close.setBackground(glass(focus,18));icon.setColor(focus?0xff17342e:WHITE);});close.setOnClickListener(v->{dialog.dismiss();if(back!=null)back.run();});heading.addView(close,new LinearLayout.LayoutParams(dp(36),dp(36)));shell.addView(heading);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.setPadding(dp(3),dp(18),dp(3),dp(3));
        LinearLayout contents=vertical();contents.setClipChildren(false);scroll.addView(contents);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));dialog.setContentView(shell);Window window=dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(0.32f);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        dialog.setOnKeyListener((d,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP){dialog.dismiss();if(back!=null)back.run();return true;}return key==KeyEvent.KEYCODE_BACK;});
        dialog.setOnDismissListener(d->{if(settingsDialog==dialog){settingsDialog=null;gear.requestFocus();}});dialog.show();
        WindowManager.LayoutParams parameters=window.getAttributes();parameters.gravity=Gravity.RIGHT|Gravity.CENTER_VERTICAL;parameters.width=Math.min(dp(420),getResources().getDisplayMetrics().widthPixels-dp(48));parameters.height=getResources().getDisplayMetrics().heightPixels-dp(48);parameters.x=dp(24);window.setAttributes(parameters);
        contents.post(()->{if(diagnosticMode)close.requestFocus();else if(contents.getChildCount()>0)contents.getChildAt(0).requestFocus();});return contents;
    }
    private void rowColors(View view,boolean focus){
        if(view instanceof TextView)((TextView)view).setTextColor(focus?0xff17342e:WHITE);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)rowColors(group.getChildAt(i),focus);}
    }
    private TextView settingRow(LinearLayout parent,String label,String detail,String value,Runnable action){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(15),dp(12),dp(13),dp(12));row.setFocusable(true);row.setBackground(glass(false,16));
        LinearLayout words=vertical();words.addView(text(label,15,WHITE));
        if(detail!=null&&!detail.isEmpty()){TextView hint=text(detail,11,MUTED);hint.setPadding(0,dp(4),dp(4),0);words.addView(hint);}
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));TextView status=text(value==null?"›":value,14,MUTED);status.setGravity(Gravity.CENTER);row.addView(status);
        row.setOnFocusChangeListener((v,focus)->{row.setBackground(glass(focus,16));rowColors(row,focus);});row.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(-1,-2);space.bottomMargin=dp(8);parent.addView(row,space);return status;
    }
    private void paragraph(LinearLayout parent,String message){TextView copy=text(message,14,MUTED);copy.setLineSpacing(dp(5),1);copy.setPadding(dp(4),dp(4),dp(4),dp(20));parent.addView(copy);}
    private void editApp(App app){
        LinearLayout content=panel(app.label,"Dock bearbeiten",this::chooseDock);
        if(dock.contains(app.pkg)){
            settingRow(content,"Nach links",null,"‹",()->{int i=dock.indexOf(app.pkg);if(i>0)Collections.swap(dock,i,i-1);saveDock();editApp(app);});
            settingRow(content,"Nach rechts",null,"›",()->{int i=dock.indexOf(app.pkg);if(i<dock.size()-1)Collections.swap(dock,i,i+1);saveDock();editApp(app);});
            settingRow(content,"Aus Dock entfernen",null,"−",()->{if(dock.size()<2){toast("Mindestens eine App bleibt im Dock.");return;}dock.remove(app.pkg);saveDock();chooseDock();});
        }else settingRow(content,"Zum Dock hinzufügen",null,"+",()->{if(dock.size()>=8){toast("Maximal acht Apps im Dock.");return;}dock.add(app.pkg);saveDock();chooseDock();});
    }
    private void chooseDock(){
        LinearLayout content=panel("Dein Dock","Eine bis acht Apps · Auswahl wird direkt gespeichert",this::settings);
        for(App app:apps){final TextView[] status=new TextView[1];status[0]=settingRow(content,app.label,null,dock.contains(app.pkg)?"✓":"+",()->{
            if(dock.contains(app.pkg)){if(dock.size()<2){toast("Mindestens eine App bleibt im Dock.");return;}dock.remove(app.pkg);}else{if(dock.size()>=8){toast("Maximal acht Apps im Dock.");return;}dock.add(app.pkg);}
            saveDock();status[0].setText(dock.contains(app.pkg)?"✓":"+");
        });View row=(View)status[0].getParent();row.setOnLongClickListener(v->{editApp(app);return true;});}
    }
    private String wallpaperTitle(String name){
        int dot=name.lastIndexOf('.');String clean=dot>0?name.substring(0,dot):name;
        return clean.replace('_',' ').replace('-',' ');
    }
    private void wallpaperSettings(){
        LinearLayout content=panel("Hintergrund","Dieselben lokalen Fotos und Videos wie im Bildschirmschoner",this::settings);
        settingRow(content,"Nächster Hintergrund","Fotos wechseln; Videos laufen einmal und bleiben stumm",null,this::nextWallpaper);
        for(int minutes:new int[]{1,2,5,10})settingRow(content,"Alle "+minutes+(minutes==1?" Minute":" Minuten"),null,prefs.getInt("wallpaperSeconds",300)==minutes*60?"✓":"",()->{
            prefs.edit().putInt("wallpaperSeconds",minutes*60).apply();if(!backgrounds.isEmpty()&&!backgrounds.get(Math.floorMod(wallpaperIndex,backgrounds.size())).video){main.removeCallbacks(rotate);main.postDelayed(rotate,minutes*60000L);}wallpaperSettings();
        });
        for(int i=0;i<backgrounds.size();i++){final int index=i;BackgroundEntry item=backgrounds.get(i);settingRow(content,wallpaperTitle(item.name),item.video?"Lokales Video · stumm":"Lokales Foto",Math.floorMod(wallpaperIndex,backgrounds.size())==i?"✓":"",()->{
            wallpaperIndex=index;prefs.edit().putInt("wallpaperIndex",index).apply();showWallpaper(index);wallpaperSettings();
        });}
        if(backgrounds.isEmpty())paragraph(content,"Diese öffentliche Version enthält keine eingebauten Fotos oder Videos. Eigene Medien bleiben ausschließlich auf deinem TV.");
    }
    private int screensaverMediaCount(){Set<String> seen=new HashSet<>();File[] roots=getExternalMediaDirs();if(roots!=null)for(File root:roots)if(root!=null)countScreensaverFolder(new File(root,"Screensaver"),seen);countScreensaverFolder(getExternalFilesDir("Screensaver"),seen);return seen.size();}
    private void countScreensaverFolder(File folder,Set<String> seen){File[] files=folder==null?null:folder.listFiles();if(files==null)return;for(File file:files){if(!file.isFile())continue;String lower=file.getName().toLowerCase(Locale.ROOT);if(lower.matches(".*\\.(jpg|jpeg|png|webp|mp4|m4v|webm|mkv)$"))seen.add(file.getAbsolutePath());}}
    private void screensaverSettings(){
        int count=screensaverMediaCount();int minutes=prefs.getInt("screensaverMinutes",5);boolean sound=prefs.getBoolean("screensaverSound",false),shuffle=prefs.getBoolean("screensaverShuffle",true);
        LinearLayout content=panel("Bildschirmschoner",count+" lokale "+(count==1?"Datei":"Dateien")+" · Fotos und Videos",this::settings);
        settingRow(content,"Jetzt ansehen","Mit einer beliebigen Fernbedienungstaste beenden",null,()->{if(settingsDialog!=null)settingsDialog.dismiss();startActivity(new Intent(this,ScreensaverActivity.class).putExtra("preview",true));});
        for(int option:new int[]{0,2,5,10,15,30}){String label=option==0?"Automatischer Start: aus":"Start nach "+option+" Minuten";settingRow(content,label,null,minutes==option?"✓":"",()->{prefs.edit().putInt("screensaverMinutes",option).apply();scheduleScreensaver();screensaverSettings();});}
        settingRow(content,"Zufällige Reihenfolge","Bei jedem Start neu mischen",shuffle?"An":"Aus",()->{prefs.edit().putBoolean("screensaverShuffle",!shuffle).apply();screensaverSettings();});
        settingRow(content,"Videoton","Standardmäßig bleibt der Bildschirmschoner leise",sound?"An":"Aus",()->{prefs.edit().putBoolean("screensaverSound",!sound).apply();screensaverSettings();});
        paragraph(content,"Medien werden lokal vom TV-Ordner Quiet Home/Screensaver gelesen und zugleich als Hintergrund verwendet. Die Synchronisierung kopiert neue Dateien dorthin, ohne sie in die Launcher-App einzubauen.");
    }
    private void settings(){
        LinearLayout content=panel("Einstellungen","Quiet Home · dein persönlicher Startbildschirm",null);
        settingRow(content,"Apps im Dock","Apps auswählen und mit langem OK anordnen",dock.size()+" Apps",this::chooseDock);
        settingRow(content,"Hintergrund","Lokale Fotos und Videos wechseln",screensaverMediaCount()+" eigene Medien",this::wallpaperSettings);
        settingRow(content,"Bildschirmschoner","Lokale Fotos und Videos",screensaverMediaCount()+" Medien",this::screensaverSettings);
        settingRow(content,"Weiterschauen","Optionalen lokalen Medienverlauf einrichten",null,this::mediaAccess);
        settingRow(content,"Home-Taste","Quiet Home als Startbildschirm verwenden",null,()->{prefs.edit().putBoolean("homeHelper",true).apply();try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(RuntimeException ex){toast("Fire OS blendet diese Einstellung aus. Nutze die mitgelieferte Einrichtung.");}});
        settingRow(content,"Amazon-Startseite","Home-Helfer für 30 Sekunden pausieren",null,()->{prefs.edit().putLong("helperPausedUntil",System.currentTimeMillis()+30000).apply();settingsDialog.dismiss();launch(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage("com.amazon.tv.launcher"));});
        settingRow(content,"Netzwerk",networkName(),null,this::networkSettings);
        settingRow(content,"HDMI-Eingänge","Geräte auswählen und Verbindungsstatus ansehen",null,this::inputSettings);
        settingRow(content,"TV-Einstellungen","Bild, Ton und Verbindungen",null,()->openSystem(new Intent(Settings.ACTION_SETTINGS)));
        settingRow(content,"Über Quiet Home","Version 1.10 · Lizenz und Hinweise",null,this::about);
    }
    private void about(){
        LinearLayout content=panel("Quiet Home","Version 1.10",this::settings);
        paragraph(content,"Natives Streaming-Home ohne Werbung oder Analytik. Die öffentliche Version enthält keine eingebauten Fotos, Videos oder ortsbezogenen Medien. Eigene Hintergründe und Bildschirmschoner-Medien bleiben ausschließlich lokal auf dem TV.\n\nWeiterschauen nutzt ausschließlich freigegebene Watch-Next-Einträge und optional lokal erkannten Medienverlauf. Verfügbarkeit und Episodensprünge hängen von jeder Streaming-App ab.\n\nDesign-Inspiration: Sva Launcher von clay-36. Netzwerk- und Eingangssteuerung inspiriert von cleanyourtv / Gathin23. Eigenständige Implementierung.");
        settingRow(content,"Lizenz & Beiträge","Nicht-kommerzielle Nutzung und Mitwirkung",null,this::licenseInfo);settingRow(content,"Zurück zu den Einstellungen",null,"‹",this::settings);
    }
    private void licenseInfo(){
        LinearLayout content=panel("Lizenz & Beiträge","PolyForm Noncommercial 1.0.0",this::about);
        try(InputStream input=getAssets().open("LICENSE-SUMMARY.txt");ByteArrayOutputStream data=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)data.write(buffer,0,count);paragraph(content,new String(data.toByteArray(),"UTF-8"));
        }catch(IOException ex){paragraph(content,"Die Lizenzhinweise konnten nicht geladen werden.");}settingRow(content,"Zurück",null,"‹",this::about);
    }
    private void mediaAccess(){
        LinearLayout content=panel("Weiterschauen","Optionaler Medienverlauf",this::settings);
        paragraph(content,"Nach deiner Freigabe merkt Quiet Home lokal Titel, Bild und Wiedergabeposition aus unterstützten Streaming-Mediensitzungen. Kein Konto-Login, kein Upload. Die Systemberechtigung heißt Benachrichtigungszugriff; Quiet Home verarbeitet nur die unterstützten Streaming-Apps.\n\nOhne Wiedergabelink öffnet eine Karte lediglich die App. Bereits vor der Einrichtung gesehene Titel werden nicht rückwirkend importiert.");
        settingRow(content,"Systemeinstellung öffnen","Zugriff erst dort selbst freigeben",null,()->{try{startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}catch(RuntimeException ex){toast("Fire OS blendet diese Einstellung aus. Die mitgelieferte Einrichtung bietet einen separaten optionalen Schritt.");}});
    }
    @Override public boolean onKeyDown(int key,KeyEvent event){if(key==KeyEvent.KEYCODE_MENU){settings();return true;}return super.onKeyDown(key,event);}
    @Override public void onBackPressed(){if(settingsDialog!=null){settingsDialog.dismiss();return;}if(page!=0){switchPage(0);if(firstCard!=null)firstCard.requestFocus();}else if(firstCard!=null)firstCard.requestFocus();}
}
