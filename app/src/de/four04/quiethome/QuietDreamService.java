package de.four04.quiethome;

import android.service.dreams.DreamService;

/** Lets Fire OS use the same local player when it honors Android DreamService. */
public final class QuietDreamService extends DreamService {
    private ScreensaverSurface surface;
    @Override public void onAttachedToWindow(){super.onAttachedToWindow();setInteractive(false);setFullscreen(true);setScreenBright(true);surface=new ScreensaverSurface(this);setContentView(surface);}
    @Override public void onDreamingStarted(){super.onDreamingStarted();surface.start();}
    @Override public void onDreamingStopped(){if(surface!=null)surface.stop();super.onDreamingStopped();}
    @Override public void onDetachedFromWindow(){if(surface!=null)surface.destroy();surface=null;super.onDetachedFromWindow();}
}
