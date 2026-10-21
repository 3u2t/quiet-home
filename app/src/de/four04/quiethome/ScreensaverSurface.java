package de.four04.quiethome;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Full-screen local photo/video player shared by the preview and DreamService. */
final class ScreensaverSurface extends FrameLayout {
    private static final String[] IMAGE_EXTENSIONS={".jpg",".jpeg",".png",".webp"};
    private static final String[] VIDEO_EXTENSIONS={".mp4",".m4v",".webm",".mkv"};
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final boolean preferVideo;
    private final ImageView image;
    private final QuietVideoView video;
    private final LinearLayout clockBlock;
    private final TextView clock,date,caption;
    private final List<Entry> entries=new ArrayList<>();
    private int index=-1,generation;
    private boolean running;
    private Bitmap shownBitmap;
    private final Runnable clockTick=new Runnable(){@Override public void run(){updateClock();main.postDelayed(this,15000);}};
    private final Runnable advance=new Runnable(){@Override public void run(){showNext();}};

    private static final class Entry {
        final File file; final String asset,name; final boolean video;
        Entry(File file,String name,boolean video){this.file=file;this.asset=null;this.name=name;this.video=video;}
        Entry(String asset,String name){this.file=null;this.asset=asset;this.name=name;this.video=false;}
    }

    ScreensaverSurface(Context context){this(context,false);}
    ScreensaverSurface(Context context,boolean startWithVideo){
        super(context);setBackgroundColor(Color.BLACK);setKeepScreenOn(true);
        preferVideo=startWithVideo;
        prefs=context.getSharedPreferences("quiet",Context.MODE_PRIVATE);
        video=new QuietVideoView(context,"screensaver");video.setVisibility(GONE);addView(video,new FrameLayout.LayoutParams(-1,-1));
        image=new ImageView(context);image.setScaleType(ImageView.ScaleType.CENTER_CROP);addView(image,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(context);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x66000000,0x00000000,0x33000000}));addView(shade,new FrameLayout.LayoutParams(-1,-1));
        clockBlock=new LinearLayout(context);clockBlock.setOrientation(LinearLayout.VERTICAL);clockBlock.setPadding(dp(18),dp(14),dp(18),dp(14));
        GradientDrawable glass=new GradientDrawable();glass.setColor(0x4a10191d);glass.setCornerRadius(dp(18));glass.setStroke(dp(1),0x55ffffff);clockBlock.setBackground(glass);
        date=label(13,0xffd8e1df);date.setLetterSpacing(0.14f);clockBlock.addView(date);
        clock=label(48,0xfff7faf9);clock.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));clockBlock.addView(clock);
        caption=label(12,0xffd8e1df);caption.setPadding(0,dp(4),0,0);clockBlock.addView(caption);
        addView(clockBlock,overlayParams(Gravity.TOP|Gravity.LEFT));
        setOnTouchListener((v,event)->false);
    }

    void start(){
        if(running)return;running=true;generation++;loadEntries();main.removeCallbacksAndMessages(null);main.post(clockTick);showNext();
    }
    void stop(){
        running=false;generation++;main.removeCallbacksAndMessages(null);video.stop();video.setVisibility(GONE);
    }
    void destroy(){stop();io.shutdownNow();if(shownBitmap!=null&&!shownBitmap.isRecycled())shownBitmap.recycle();shownBitmap=null;}
    int mediaCount(){return entries.size();}

    private void loadEntries(){
        entries.clear();Set<String> seen=new HashSet<>();
        File[] mediaRoots=getContext().getExternalMediaDirs();
        if(mediaRoots!=null)for(File root:mediaRoots)if(root!=null)addFolder(new File(root,"Screensaver"),seen);
        addFolder(getContext().getExternalFilesDir("Screensaver"),seen);
        if(prefs.getBoolean("screensaverShuffle",true)&&entries.size()>1)Collections.shuffle(entries,new Random());
        if(preferVideo)for(int i=0;i<entries.size();i++)if(entries.get(i).video){Collections.swap(entries,0,i);break;}
    }
    private void addFolder(File folder,Set<String> seen){File[] files=folder==null?null:folder.listFiles();if(files==null)return;Arrays.sort(files,(a,b)->a.getName().compareToIgnoreCase(b.getName()));for(File file:files){if(!file.isFile()||!seen.add(file.getAbsolutePath()))continue;String lower=file.getName().toLowerCase(Locale.ROOT);if(hasExtension(lower,IMAGE_EXTENSIONS))entries.add(new Entry(file,file.getName(),false));else if(hasExtension(lower,VIDEO_EXTENSIONS))entries.add(new Entry(file,file.getName(),true));}}
    private boolean hasExtension(String name,String[] extensions){for(String extension:extensions)if(name.endsWith(extension))return true;return false;}
    private void showNext(){
        main.removeCallbacks(advance);if(!running)return;if(entries.isEmpty()){caption.setText("Keine lokalen Medien");image.setImageDrawable(new ColorDrawable(0xff14242a));return;}
        index=(index+1)%entries.size();Entry entry=entries.get(index);moveClock(index);caption.setText(cleanName(entry.name));
        video.stop();video.setVisibility(GONE);image.setVisibility(VISIBLE);
        if(entry.video)showVideo(entry);else showImage(entry);
    }
    private void showVideo(Entry entry){
        image.setImageDrawable(new ColorDrawable(Color.BLACK));video.setVisibility(VISIBLE);video.play(entry.file.getAbsolutePath(),false,!prefs.getBoolean("screensaverSound",false),()->{if(!running)return;image.setVisibility(GONE);},()->{video.setVisibility(GONE);main.postDelayed(advance,700);},()->{android.util.Log.w("QuietHome","Screensaver video failed: "+entry.name);video.setVisibility(GONE);main.postDelayed(advance,1200);});
    }
    private void showImage(Entry entry){
        final int token=++generation;io.execute(()->{Bitmap bitmap=null;try{if(entry.file!=null)bitmap=decodeFile(entry.file);else try(InputStream input=getContext().getAssets().open(entry.asset)){bitmap=decodeStream(input);} }catch(IOException|RuntimeException ex){android.util.Log.w("QuietHome","Screensaver image failed: "+entry.name,ex);}final Bitmap ready=bitmap;main.post(()->{if(!running||token!=generation){if(ready!=null)ready.recycle();return;}if(ready==null){main.postDelayed(advance,1000);return;}if(shownBitmap!=null&&shownBitmap!=ready)shownBitmap.recycle();shownBitmap=ready;image.setImageBitmap(ready);image.setAlpha(0f);image.animate().alpha(1f).setDuration(700).start();main.postDelayed(advance,Math.max(5,prefs.getInt("screensaverPhotoSeconds",20))*1000L);});});
    }
    private Bitmap decodeFile(File file)throws IOException{try(InputStream input=new BufferedInputStream(new FileInputStream(file))){return decodeStream(input);}}
    private Bitmap decodeStream(InputStream input)throws IOException{
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);byte[] bytes=output.toByteArray();
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);int sample=1;int target=Math.max(getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels);while(Math.max(bounds.outWidth,bounds.outHeight)/sample>target*2)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
    }
    private String cleanName(String name){int dot=name.lastIndexOf('.');String value=dot>0?name.substring(0,dot):name;value=value.replaceFirst("^user-(?:tv-v\\d+-)?[A-Fa-f0-9]{8}-","");value=value.replace('_',' ').replace('-',' ');return value.length()>48?value.substring(0,48):value;}
    private void updateClock(){Date now=new Date();clock.setText(new SimpleDateFormat("HH:mm",Locale.GERMANY).format(now));date.setText(new SimpleDateFormat("EEE, d. MMM yyyy",Locale.GERMANY).format(now).toUpperCase(Locale.GERMANY));}
    private void moveClock(int position){int[] places={Gravity.TOP|Gravity.LEFT,Gravity.TOP|Gravity.RIGHT,Gravity.BOTTOM|Gravity.RIGHT,Gravity.BOTTOM|Gravity.LEFT};clockBlock.setLayoutParams(overlayParams(places[Math.floorMod(position,places.length)]));}
    private FrameLayout.LayoutParams overlayParams(int gravity){FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-2,-2,gravity);params.setMargins(dp(34),dp(28),dp(34),dp(28));return params;}
    private TextView label(float size,int color){TextView view=new TextView(getContext());view.setTextSize(size);view.setTextColor(color);view.setFontFeatureSettings("kern");return view;}
    private int dp(float value){return Math.round(getResources().getDisplayMetrics().density*value);}
}
