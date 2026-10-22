package de.four04.quiethome;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;

/** Remote-friendly preview and reliable launcher-idle screensaver host. */
public final class ScreensaverActivity extends Activity {
    private ScreensaverSurface surface;
    private boolean diagnosticMode;
    @Override public void onCreate(Bundle state){super.onCreate(state);diagnosticMode=getIntent().getBooleanExtra("diagnostic",false);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);surface=new ScreensaverSurface(this,getIntent().getBooleanExtra("preferVideo",false));setContentView(surface);}
    @Override protected void onResume(){super.onResume();surface.start();}
    @Override protected void onPause(){surface.stop();super.onPause();}
    @Override protected void onDestroy(){surface.destroy();super.onDestroy();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(diagnosticMode){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getKeyCode()==KeyEvent.KEYCODE_BACK)finish();return true;}if(event.getAction()==KeyEvent.ACTION_DOWN){finish();return true;}return true;}
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){if(!diagnosticMode)finish();return true;}
}
