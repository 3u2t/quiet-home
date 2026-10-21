package de.four04.quiethome;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;

/** Local muted-by-default video surface that is visible to normal TV screenshots. */
final class QuietVideoView extends TextureView implements TextureView.SurfaceTextureListener {
    private MediaPlayer player;
    private final String role;
    private String path;
    private boolean looping,muted;
    private Runnable ready,complete,failed;
    private int generation;
    private boolean firstFrameReported;

    QuietVideoView(Context context,String videoRole){super(context);role=videoRole;setSurfaceTextureListener(this);setOpaque(true);setFocusable(false);}

    void play(String source,boolean shouldLoop,boolean shouldMute,Runnable onReady,Runnable onComplete,Runnable onFailed){
        stopPlayer();path=source;looping=shouldLoop;muted=shouldMute;ready=onReady;complete=onComplete;failed=onFailed;firstFrameReported=false;generation++;if(isAvailable())startPlayer(generation);
    }
    void stop(){generation++;path=null;ready=null;complete=null;failed=null;stopPlayer();}
    private void stopPlayer(){if(player!=null){try{player.stop();}catch(RuntimeException ignored){}player.reset();player.release();player=null;}}
    private void startPlayer(final int token){
        if(path==null||!isAvailable())return;stopPlayer();final MediaPlayer next=new MediaPlayer();player=next;
        try{
            next.setDataSource(path);Surface surface=new Surface(getSurfaceTexture());next.setSurface(surface);surface.release();next.setLooping(looping);
            next.setOnVideoSizeChangedListener((media,width,height)->crop(width,height));
            next.setOnPreparedListener(media->{if(token!=generation||media!=player)return;if(muted)media.setVolume(0f,0f);crop(media.getVideoWidth(),media.getVideoHeight());media.start();if(ready!=null)ready.run();});
            next.setOnCompletionListener(media->{if(token==generation&&media==player&&complete!=null)complete.run();});
            next.setOnErrorListener((media,what,extra)->{if(token==generation&&failed!=null)failed.run();return true;});next.prepareAsync();
        }catch(Exception ex){android.util.Log.w("QuietHome","Local video could not start: "+path,ex);stopPlayer();if(token==generation&&failed!=null)failed.run();}
    }
    private void crop(int videoWidth,int videoHeight){if(videoWidth<=0||videoHeight<=0||getWidth()<=0||getHeight()<=0)return;float video=(float)videoWidth/videoHeight,view=(float)getWidth()/getHeight(),scaleX=1f,scaleY=1f;if(video>view)scaleX=video/view;else scaleY=view/video;Matrix matrix=new Matrix();matrix.setScale(scaleX,scaleY,getWidth()/2f,getHeight()/2f);setTransform(matrix);}
    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface,int width,int height){if(path!=null)startPlayer(generation);}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int width,int height){if(player!=null)crop(player.getVideoWidth(),player.getVideoHeight());}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface){stopPlayer();return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface){if(firstFrameReported||path==null||player==null)return;try{if(player.isPlaying()){firstFrameReported=true;android.util.Log.i("QuietHome","Local video frame rendered: "+role+" "+new java.io.File(path).getName());}}catch(RuntimeException ignored){}}
}
