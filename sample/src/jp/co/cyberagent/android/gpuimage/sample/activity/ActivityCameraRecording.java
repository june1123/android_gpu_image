/*
 * Copyright (C) 2012 CyberAgent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.co.cyberagent.android.gpuimage.sample.activity;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import com.android.grafika.TextureMovieEncoder2;
import com.android.grafika.VideoEncoderCore;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.FullFrameRect;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.Texture2dProgram;
import com.android.grafika.gles.WindowSurface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;

import jp.co.cyberagent.android.gpuimage.GPUImageRenderer;
import jp.co.cyberagent.android.gpuimage.Rotation;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageYUVFilter;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools.FilterAdjuster;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools.OnGpuImageFilterChosenListener;
import jp.co.cyberagent.android.gpuimage.sample.R;
import jp.co.cyberagent.android.gpuimage.sample.utils.CameraHelper;
import jp.co.cyberagent.android.gpuimage.sample.utils.CameraHelper.CameraInfo2;

public class ActivityCameraRecording extends Activity implements OnSeekBarChangeListener, OnClickListener, SurfaceHolder.Callback,
        Choreographer.FrameCallback {

    private static final String TAG = "ActivityCameraRecoding";

    //private GPUImage mGPUImage;
    private CameraHelper mCameraHelper;
    private CameraLoader mCamera;
    private GPUImageFilter mFilter;
    private FilterAdjuster mFilterAdjuster;


    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_record);
        ((SeekBar) findViewById(R.id.seekBar)).setOnSeekBarChangeListener(this);
        findViewById(R.id.button_choose_filter).setOnClickListener(this);
        findViewById(R.id.button_capture).setOnClickListener(this);

        //mGPUImage = new GPUImage(this);
        //mGPUImage.setGLSurfaceView((GLSurfaceView) findViewById(R.id.surfaceView));

        SurfaceView sv = (SurfaceView) findViewById(R.id.surfaceView);
        sv.getHolder().addCallback(this);

        mCameraHelper = new CameraHelper(this);
        mCamera = new CameraLoader();

        View cameraSwitchView = findViewById(R.id.img_switch_camera);
        cameraSwitchView.setOnClickListener(this);
        if (!mCameraHelper.hasFrontCamera() || !mCameraHelper.hasBackCamera()) {
            cameraSwitchView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCamera.onResume();

        // If we already have a Surface, we just need to resume the frame notifications.
        if (mRenderThread != null) {
            Log.d(TAG, "onResume re-hooking choreographer");
            Choreographer.getInstance().postFrameCallback(this);
        }

        updateControls();
    }

    private void updateControls() {
        TextView recordView = (TextView) findViewById(R.id.record_state);
        recordView.setText(mRecordingEnabled ? "recording" : "idle");
    }

    @Override
    protected void onPause() {
        super.onPause();

        mCamera.onPause();
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void onClick(final View v) {
        switch (v.getId()) {
            case R.id.button_choose_filter:
                GPUImageFilterTools.showDialog(this, new OnGpuImageFilterChosenListener() {

                    @Override
                    public void onGpuImageFilterChosenListener(final GPUImageFilter filter) {
                        switchFilterTo(filter);
                    }
                });
                break;

            case R.id.button_capture:
                toggleRecord();
                break;

            case R.id.img_switch_camera:
                mCamera.switchCamera();
                break;
        }
    }

    private void toggleRecord() {
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            mRecordingEnabled = !mRecordingEnabled;
            updateControls();
            rh.setRecordingEnabled(mRecordingEnabled);
        }
    }

    public static final int MEDIA_TYPE_IMAGE = 1;
    public static final int MEDIA_TYPE_VIDEO = 2;

    private static File getOutputMediaFile(final int type) {
        // To be safe, you should check that the SDCard is mounted
        // using Environment.getExternalStorageState() before doing this.

        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "MyCameraApp");
        // This location works best if you want the created images to be shared
        // between applications and persist after your app has been uninstalled.

        // Create the storage directory if it does not exist
        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                Log.d("MyCameraApp", "failed to create directory");
                return null;
            }
        }

        // Create a media file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File mediaFile;
        if (type == MEDIA_TYPE_IMAGE) {
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
                    "IMG_" + timeStamp + ".jpg");
        } else if (type == MEDIA_TYPE_VIDEO) {
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
                    "VID_" + timeStamp + ".mp4");
        } else {
            return null;
        }

        return mediaFile;
    }

    private void switchFilterTo(final GPUImageFilter filter) {
        if (mFilter == null
                || (filter != null && !mFilter.getClass().equals(filter.getClass()))) {
            mFilter = filter;

            GPUImageFilterGroup gpuImageFilterGroup = new GPUImageFilterGroup();
            gpuImageFilterGroup.addFilter(new GPUImageYUVFilter());
            gpuImageFilterGroup.addFilter(mFilter);

            RenderHandler rh = mRenderThread.getHandler();
            if (rh != null) {
                rh.changeFilter(gpuImageFilterGroup);
            }
            mFilterAdjuster = new FilterAdjuster(mFilter);
        }
    }

    @Override
    public void onProgressChanged(final SeekBar seekBar, final int progress,
                                  final boolean fromUser) {
        if (mFilterAdjuster != null) {
            mFilterAdjuster.adjust(progress);
        }
    }

    @Override
    public void onStartTrackingTouch(final SeekBar seekBar) {
    }

    @Override
    public void onStopTrackingTouch(final SeekBar seekBar) {
    }

    private class CameraLoader {

        private int mCurrentCameraId = 0;
        private Camera mCameraInstance;

        public void onResume() {
            setUpCamera(mCurrentCameraId);
        }

        public void onPause() {
            releaseCamera();
        }

        public void switchCamera() {
            releaseCamera();
            mCurrentCameraId = (mCurrentCameraId + 1) % mCameraHelper.getNumberOfCameras();
            setUpCamera(mCurrentCameraId);
        }

        private void setUpCamera(final int id) {
            mCameraInstance = getCameraInstance(id);
            Parameters parameters = mCameraInstance.getParameters();
            // TODO adjust by getting supportedPreviewSizes and then choosing
            // the best one for screen size (best fill screen)
            if (parameters.getSupportedFocusModes().contains(
                    Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            mCameraInstance.setParameters(parameters);

            int orientation = mCameraHelper.getCameraDisplayOrientation(
                    ActivityCameraRecording.this, mCurrentCameraId);
            CameraInfo2 cameraInfo = new CameraInfo2();
            mCameraHelper.getCameraInfo(mCurrentCameraId, cameraInfo);

            if( mRenderThread != null ) {
                RenderHandler rh = mRenderThread.getHandler();
                if (rh != null) {
                    rh.switchCamera(mCameraInstance, orientation);
                }
            }
        }

        /**
         * A safe way to get an instance of the Camera object.
         */
        private Camera getCameraInstance(final int id) {
            Camera c = null;
            try {
                c = mCameraHelper.openCamera(id);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return c;
        }

        private void releaseCamera() {
            mCameraInstance.setPreviewCallback(null);
            mCameraInstance.release();
            mCameraInstance = null;
        }
    }


    // See the (lengthy) notes at the top of HardwareScalerActivity for thoughts about
    // Activity / Surface lifecycle management.

    private boolean mRecordingEnabled = false;          // controls button state

    private RenderThread mRenderThread;


    private static final int RECMETHOD_DRAW_TWICE = 0;
    private static final int RECMETHOD_FBO = 1;

    public static long getDisplayRefreshNsec(Activity activity) {
        Display display = ((WindowManager)
                activity.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        double displayFps = display.getRefreshRate();
        long refreshNs = Math.round(1000000000L / displayFps);
        Log.d(TAG, "refresh rate is " + displayFps + " fps --> " + refreshNs + " ns");
        return refreshNs;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated holder=" + holder);

        File outputDir = new File(Environment.getExternalStorageDirectory(), "debug");
        if( !outputDir.exists() ) {
            outputDir.mkdirs();
        }

        File outputFile = new File(outputDir, "fbo-gl-recording.mp4");
        SurfaceView sv = (SurfaceView) findViewById(R.id.surfaceView);
        mRenderThread = new RenderThread(sv.getHolder(), new ActivityHandler(this), outputFile,
                getDisplayRefreshNsec(this));
        mRenderThread.setName("RecordFBO GL render");
        mRenderThread.start();
        mRenderThread.waitUntilReady();

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendSurfaceCreated();
        }

        // start the draw events
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + holder);
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendSurfaceChanged(format, width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed holder=" + holder);

        // We need to wait for the render thread to shut down before continuing because we
        // don't want the Surface to disappear out from under it mid-render.  The frame
        // notifications will have been stopped back in onPause(), but there might have
        // been one in progress.
        //
        // TODO: the RenderThread doesn't currently wait for the encoder / muxer to stop,
        //       so we can't use this as an indication that the .mp4 file is complete.

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendShutdown();
            try {
                mRenderThread.join();
            } catch (InterruptedException ie) {
                // not expected
                throw new RuntimeException("join was interrupted", ie);
            }
        }
        mRenderThread = null;
        mRecordingEnabled = false;

        // If the callback was posted, remove it.  Without this, we could get one more
        // call on doFrame().
        Choreographer.getInstance().removeFrameCallback(this);
        Log.d(TAG, "surfaceDestroyed complete");
    }

    /*
     * Choreographer callback, called near vsync.
     *
     * @see android.view.Choreographer.FrameCallback#doFrame(long)
     */
    @Override
    public void doFrame(long frameTimeNanos) {
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            Choreographer.getInstance().postFrameCallback(this);
            rh.sendDoFrame(frameTimeNanos);
        }
    }

    /**
     * Updates the FPS counter.
     * <p/>
     * Called periodically from the render thread (via ActivityHandler).
     */
    void handleUpdateFps(int tfps, int dropped) {
        String str = String.format("%f", tfps / 1000.0f, dropped);
        TextView tv = (TextView) findViewById(R.id.frameRateValue_text);
        tv.setText(str);
    }


    /**
     * Handles messages sent from the render thread to the UI thread.
     * <p/>
     * The object is created on the UI thread, and all handlers run there.
     */
    static class ActivityHandler extends Handler {
        private static final int MSG_UPDATE_FPS = 1;

        // Weak reference to the Activity; only access this from the UI thread.
        private WeakReference<ActivityCameraRecording> mWeakActivity;

        public ActivityHandler(ActivityCameraRecording activity) {
            mWeakActivity = new WeakReference<ActivityCameraRecording>(activity);
        }

        /**
         * Send an FPS update.  "fps" should be in thousands of frames per second
         * (i.e. fps * 1000), so we can get fractional fps even though the Handler only
         * supports passing integers.
         * <p/>
         * Call from non-UI thread.
         */
        public void sendFpsUpdate(int tfps, int dropped) {
            sendMessage(obtainMessage(MSG_UPDATE_FPS, tfps, dropped));
        }

        @Override  // runs on UI thread
        public void handleMessage(Message msg) {
            int what = msg.what;
            //Log.d(TAG, "ActivityHandler [" + this + "]: what=" + what);

            ActivityCameraRecording activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "ActivityHandler.handleMessage: activity is null");
                return;
            }

            switch (what) {
                case MSG_UPDATE_FPS:
                    activity.handleUpdateFps(msg.arg1, msg.arg2);
                    break;
                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }


    /**
     * This class handles all OpenGL rendering.
     * <p/>
     * We use Choreographer to coordinate with the device vsync.  We deliver one frame
     * per vsync.  We can't actually know when the frame we render will be drawn, but at
     * least we get a consistent frame interval.
     * <p/>
     * Start the render thread after the Surface has been created.
     */
    private static class RenderThread extends Thread {
        // Object must be created on render thread to get correct Looper, but is used from
        // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
        // constructed object.
        private volatile RenderHandler mHandler;

        // Handler we can send messages to if we want to update the app UI.
        private ActivityHandler mActivityHandler;

        // Used to wait for the thread to start.
        private Object mStartLock = new Object();
        private boolean mReady = false;

        private volatile SurfaceHolder mSurfaceHolder;  // may be updated by UI thread
        private EglCore mEglCore;
        private WindowSurface mWindowSurface;

        // Orthographic projection matrix.
        private float[] mDisplayProjectionMatrix = new float[16];

        private final float[] mIdentityMatrix;

        // Previous frame time.
        private long mPrevTimeNanos;

        // FPS / drop counter.
        private long mRefreshPeriodNanos;
        private long mFpsCountStartNanos;
        private int mFpsCountFrame;
        private int mDroppedFrames;
        private boolean mPreviousWasDropped;

        // Used for off-screen rendering.
        private int mOffscreenTexture;
        private int mFramebuffer;
        private int mDepthBuffer;
        private FullFrameRect mFullScreen;

        // Used for recording.
        private boolean mRecordingEnabled;
        private File mOutputFile;
        private WindowSurface mInputWindowSurface;
        private TextureMovieEncoder2 mVideoEncoder;
        private int mRecordMethod;
        private boolean mRecordedPrevious;
        private Rect mVideoRect;
        private GPUImageRenderer gpuImageRenderer;

        /**
         * Pass in the SurfaceView's SurfaceHolder.  Note the Surface may not yet exist.
         */
        public RenderThread(SurfaceHolder holder, ActivityHandler ahandler, File outputFile,
                            long refreshPeriodNs) {
            mSurfaceHolder = holder;
            mActivityHandler = ahandler;
            mOutputFile = outputFile;
            mRefreshPeriodNanos = refreshPeriodNs;

            mVideoRect = new Rect();

            mIdentityMatrix = new float[16];
            Matrix.setIdentityM(mIdentityMatrix, 0);

            mRecordMethod = RECMETHOD_FBO;
        }

        /**
         * Thread entry point.
         * <p/>
         * The thread should not be started until the Surface associated with the SurfaceHolder
         * has been created.  That way we don't have to wait for a separate "surface created"
         * message to arrive.
         */
        @Override
        public void run() {
            Looper.prepare();
            mHandler = new RenderHandler(this);
            mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
            gpuImageRenderer = new GPUImageRenderer(new GPUImageYUVFilter());

            synchronized (mStartLock) {
                mReady = true;
                mStartLock.notify();    // signal waitUntilReady()
            }

            Looper.loop();

            Log.d(TAG, "looper quit");
            releaseGl();
            mEglCore.release();

            synchronized (mStartLock) {
                mReady = false;
            }
        }

        /**
         * Waits until the render thread is ready to receive messages.
         * <p/>
         * Call from the UI thread.
         */
        public void waitUntilReady() {
            synchronized (mStartLock) {
                while (!mReady) {
                    try {
                        mStartLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }

        /**
         * Shuts everything down.
         */
        private void shutdown() {
            Log.d(TAG, "shutdown");
            stopEncoder();
            Looper.myLooper().quit();
        }

        /**
         * Returns the render thread's Handler.  This may be called from any thread.
         */
        public RenderHandler getHandler() {
            return mHandler;
        }

        /**
         * Prepares the surface.
         */
        private void surfaceCreated() {
            Surface surface = mSurfaceHolder.getSurface();
            prepareGl(surface);
            gpuImageRenderer.onSurfaceCreated(null, null);
        }

        /**
         * Prepares window surface and GL state.
         */
        private void prepareGl(Surface surface) {
            Log.d(TAG, "prepareGl");

            mWindowSurface = new WindowSurface(mEglCore, surface, false);
            mWindowSurface.makeCurrent();

            // Used for blitting texture to FBO.
            mFullScreen = new FullFrameRect(
                    new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D));
        }

        /**
         * Handles changes to the size of the underlying surface.  Adjusts viewport as needed.
         * Must be called before we start drawing.
         * (Called from RenderHandler.)
         */
        private void surfaceChanged(int width, int height) {
            Log.d(TAG, "surfaceChanged " + width + "x" + height);

            prepareFramebuffer(width, height);

            gpuImageRenderer.onSurfaceChanged(null, width, height);
//            // Use full window.
//            GLES20.glViewport(0, 0, width, height);
//
//            // Simple orthographic projection, with (0,0) in lower-left corner.
//            Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);

        }

        /**
         * Prepares the off-screen framebuffer.
         */
        private void prepareFramebuffer(int width, int height) {
            GlUtil.checkGlError("prepareFramebuffer start");

            int[] values = new int[1];

            // Create a texture object and bind it.  This will be the color buffer.
            GLES20.glGenTextures(1, values, 0);
            GlUtil.checkGlError("glGenTextures");
            mOffscreenTexture = values[0];   // expected > 0
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mOffscreenTexture);
            GlUtil.checkGlError("glBindTexture " + mOffscreenTexture);

            // Create texture storage.
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

            // Set parameters.  We're probably using non-power-of-two dimensions, so
            // some values may not be available for use.
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GlUtil.checkGlError("glTexParameter");

            // Create framebuffer object and bind it.
            GLES20.glGenFramebuffers(1, values, 0);
            GlUtil.checkGlError("glGenFramebuffers");
            mFramebuffer = values[0];    // expected > 0
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
            GlUtil.checkGlError("glBindFramebuffer " + mFramebuffer);

            // Create a depth buffer and bind it.
            GLES20.glGenRenderbuffers(1, values, 0);
            GlUtil.checkGlError("glGenRenderbuffers");
            mDepthBuffer = values[0];    // expected > 0
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, mDepthBuffer);
            GlUtil.checkGlError("glBindRenderbuffer " + mDepthBuffer);

            // Allocate storage for the depth buffer.
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16,
                    width, height);
            GlUtil.checkGlError("glRenderbufferStorage");

            // Attach the depth buffer and the texture (color buffer) to the framebuffer object.
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                    GLES20.GL_RENDERBUFFER, mDepthBuffer);
            GlUtil.checkGlError("glFramebufferRenderbuffer");
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, mOffscreenTexture, 0);
            GlUtil.checkGlError("glFramebufferTexture2D");

            // See if GLES is happy with all this.
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("Framebuffer not complete, status=" + status);
            }

            // Switch back to the default framebuffer.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            GlUtil.checkGlError("prepareFramebuffer done");
        }

        private void switchCamera(Camera camera, int degrees){

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            SurfaceTexture surfaceTexture = new SurfaceTexture(textures[0]);
            try {
                camera.setPreviewTexture(surfaceTexture);
                camera.startPreview();

                final Camera.Size previewSize = camera.getParameters().getPreviewSize();
                gpuImageRenderer.setInputSurfaceTexture(surfaceTexture, previewSize.width, previewSize.height);

                Rotation rotation = Rotation.NORMAL;
                switch (degrees) {
                    case 90:
                        rotation = Rotation.ROTATION_90;
                        break;
                    case 180:
                        rotation = Rotation.ROTATION_180;
                        break;
                    case 270:
                        rotation = Rotation.ROTATION_270;
                        break;
                }
                gpuImageRenderer.setRotation(rotation);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        /**
         * Releases most of the GL resources we currently hold.
         * <p/>
         * Does not release EglCore.
         */
        private void releaseGl() {
            GlUtil.checkGlError("releaseGl start");

            int[] values = new int[1];

            if (mWindowSurface != null) {
                mWindowSurface.release();
                mWindowSurface = null;
            }
            if (gpuImageRenderer != null) {
                gpuImageRenderer.deleteImage();
                gpuImageRenderer = null;
            }
            if (mOffscreenTexture > 0) {
                values[0] = mOffscreenTexture;
                GLES20.glDeleteTextures(1, values, 0);
                mOffscreenTexture = -1;
            }
            if (mFramebuffer > 0) {
                values[0] = mFramebuffer;
                GLES20.glDeleteFramebuffers(1, values, 0);
                mFramebuffer = -1;
            }
            if (mDepthBuffer > 0) {
                values[0] = mDepthBuffer;
                GLES20.glDeleteRenderbuffers(1, values, 0);
                mDepthBuffer = -1;
            }
            if (mFullScreen != null) {
                mFullScreen.release(false); // TODO: should be "true"; must ensure mEglCore current
                mFullScreen = null;
            }

            GlUtil.checkGlError("releaseGl done");

            mEglCore.makeNothingCurrent();
        }

        /**
         * Updates the recording state.  Stops or starts recording as needed.
         */
        private void setRecordingEnabled(boolean enabled) {
            if (enabled == mRecordingEnabled) {
                return;
            }
            if (enabled) {
                startEncoder();
            } else {
                stopEncoder();
            }
            mRecordingEnabled = enabled;
        }

        /**
         * Changes the method we use to render frames to the encoder.
         */
        private void setRecordMethod(int recordMethod) {
            Log.d(TAG, "RT: setRecordMethod " + recordMethod);
            mRecordMethod = recordMethod;
        }

        /**
         * Creates the video encoder object and starts the encoder thread.  Creates an EGL
         * surface for encoder input.
         */
        private void startEncoder() {
            Log.d(TAG, "starting to record");
            // Record at 1280x720, regardless of the window dimensions.  The encoder may
            // explode if given "strange" dimensions, e.g. a width that is not a multiple
            // of 16.  We can box it as needed to preserve dimensions.
            final int BIT_RATE = 4000000;   // 4Mbps
            final int VIDEO_WIDTH = 1280;
            final int VIDEO_HEIGHT = 720;
            int windowWidth = mWindowSurface.getWidth();
            int windowHeight = mWindowSurface.getHeight();
            float windowAspect = (float) windowHeight / (float) windowWidth;
            int outWidth, outHeight;
            if (VIDEO_HEIGHT > VIDEO_WIDTH * windowAspect) {
                // limited by narrow width; reduce height
                outWidth = VIDEO_WIDTH;
                outHeight = (int) (VIDEO_WIDTH * windowAspect);
            } else {
                // limited by short height; restrict width
                outHeight = VIDEO_HEIGHT;
                outWidth = (int) (VIDEO_HEIGHT / windowAspect);
            }
            int offX = (VIDEO_WIDTH - outWidth) / 2;
            int offY = (VIDEO_HEIGHT - outHeight) / 2;
            mVideoRect.set(offX, offY, offX + outWidth, offY + outHeight);
            Log.d(TAG, "Adjusting window " + windowWidth + "x" + windowHeight +
                    " to +" + offX + ",+" + offY + " " +
                    mVideoRect.width() + "x" + mVideoRect.height());

            VideoEncoderCore encoderCore;
            try {
                encoderCore = new VideoEncoderCore(VIDEO_WIDTH, VIDEO_HEIGHT,
                        BIT_RATE, mOutputFile);
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
            mInputWindowSurface = new WindowSurface(mEglCore, encoderCore.getInputSurface(), true);
            mVideoEncoder = new TextureMovieEncoder2(encoderCore);
        }

        /**
         * Stops the video encoder if it's running.
         */
        private void stopEncoder() {
            if (mVideoEncoder != null) {
                Log.d(TAG, "stopping recorder, mVideoEncoder=" + mVideoEncoder);
                mVideoEncoder.stopRecording();
                // TODO: wait (briefly) until it finishes shutting down so we know file is
                //       complete, or have a callback that updates the UI
                mVideoEncoder = null;
            }
            if (mInputWindowSurface != null) {
                mInputWindowSurface.release();
                mInputWindowSurface = null;
            }
        }

        /**
         * Advance state and draw frame in response to a vsync event.
         */
        private void doFrame(long timeStampNanos) {
            // If we're not keeping up 60fps -- maybe something in the system is busy, maybe
            // recording is too expensive, maybe the CPU frequency governor thinks we're
            // not doing and wants to drop the clock frequencies -- we need to drop frames
            // to catch up.  The "timeStampNanos" value is based on the system monotonic
            // clock, as is System.nanoTime(), so we can compare the values directly.
            //
            // Our clumsy collision detection isn't sophisticated enough to deal with large
            // time gaps, but it's nearly cost-free, so we go ahead and do the computation
            // either way.
            //
            // We can reduce the overhead of recording, as well as the size of the movie,
            // by recording at ~30fps instead of the display refresh rate.  As a quick hack
            // we just record every-other frame, using a "recorded previous" flag.

            update(timeStampNanos);

            long diff = System.nanoTime() - timeStampNanos;
            long max = mRefreshPeriodNanos - 2000000;   // if we're within 2ms, don't bother
            if (diff > max) {
                // too much, drop a frame
                Log.d(TAG, "diff is " + (diff / 1000000.0) + " ms, max " + (max / 1000000.0) +
                        ", skipping render");
                mRecordedPrevious = false;
                mPreviousWasDropped = true;
                mDroppedFrames++;
                return;
            }

            boolean swapResult;

            if (!mRecordingEnabled || mRecordedPrevious) {
                mRecordedPrevious = false;
                // Render the scene, swap back to front.
                draw();
                swapResult = mWindowSurface.swapBuffers();
            } else {
                mRecordedPrevious = true;

                if (mRecordMethod == RECMETHOD_DRAW_TWICE) {
                    //Log.d(TAG, "MODE: draw 2x");

                    // Draw for display, swap.
                    draw();
                    swapResult = mWindowSurface.swapBuffers();

                    // Draw for recording, swap.
                    mVideoEncoder.frameAvailableSoon();
                    mInputWindowSurface.makeCurrent();
                    // If we don't set the scissor rect, the glClear() we use to draw the
                    // light-grey background will draw outside the viewport and muck up our
                    // letterboxing.  Might be better if we disabled the test immediately after
                    // the glClear().  Of course, if we were clearing the frame background to
                    // black it wouldn't matter.
                    //
                    // We do still need to clear the pixels outside the scissor rect, of course,
                    // or we'll get garbage at the edges of the recording.  We can either clear
                    // the whole thing and accept that there will be a lot of overdraw, or we
                    // can issue multiple scissor/clear calls.  Some GPUs may have a special
                    // optimization for zeroing out the color buffer.
                    //
                    // For now, be lazy and zero the whole thing.  At some point we need to
                    // examine the performance here.
                    GLES20.glClearColor(0f, 0f, 0f, 1f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                    GLES20.glViewport(mVideoRect.left, mVideoRect.top,
                            mVideoRect.width(), mVideoRect.height());
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                    GLES20.glScissor(mVideoRect.left, mVideoRect.top,
                            mVideoRect.width(), mVideoRect.height());
                    draw();
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                    mInputWindowSurface.setPresentationTime(timeStampNanos);
                    mInputWindowSurface.swapBuffers();

                    // Restore.
                    GLES20.glViewport(0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight());
                    mWindowSurface.makeCurrent();
                } else {

                    //Log.d(TAG, "MODE: offscreen + blit 2x");
                    // Render offscreen.
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
                    GlUtil.checkGlError("glBindFramebuffer");
                    draw();

                    // Blit to display.
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    GlUtil.checkGlError("glBindFramebuffer");
                    mFullScreen.drawFrame(mOffscreenTexture, mIdentityMatrix);
                    swapResult = mWindowSurface.swapBuffers();

                    // Blit to encoder.
                    mVideoEncoder.frameAvailableSoon();
                    mInputWindowSurface.makeCurrent();
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);    // again, only really need to
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);     //  clear pixels outside rect
                    GLES20.glViewport(mVideoRect.left, mVideoRect.top,
                            mVideoRect.width(), mVideoRect.height());
                    mFullScreen.drawFrame(mOffscreenTexture, mIdentityMatrix);
                    mInputWindowSurface.setPresentationTime(timeStampNanos);
                    mInputWindowSurface.swapBuffers();

                    // Restore previous values.
                    GLES20.glViewport(0, 0, mWindowSurface.getWidth(), mWindowSurface.getHeight());
                    mWindowSurface.makeCurrent();
                }
            }

            mPreviousWasDropped = false;

            if (!swapResult) {
                // This can happen if the Activity stops without waiting for us to halt.
                Log.w(TAG, "swapBuffers failed, killing renderer thread");
                shutdown();
                return;
            }

            // Update the FPS counter.
            //
            // Ideally we'd generate something approximate quickly to make the UI look
            // reasonable, then ease into longer sampling periods.
            final int NUM_FRAMES = 120;
            final long ONE_TRILLION = 1000000000000L;
            if (mFpsCountStartNanos == 0) {
                mFpsCountStartNanos = timeStampNanos;
                mFpsCountFrame = 0;
            } else {
                mFpsCountFrame++;
                if (mFpsCountFrame == NUM_FRAMES) {
                    // compute thousands of frames per second
                    long elapsed = timeStampNanos - mFpsCountStartNanos;
                    mActivityHandler.sendFpsUpdate((int) (NUM_FRAMES * ONE_TRILLION / elapsed),
                            mDroppedFrames);

                    // reset
                    mFpsCountStartNanos = timeStampNanos;
                    mFpsCountFrame = 0;
                }
            }
        }

        /**
         * We use the time delta from the previous event to determine how far everything
         * moves.  Ideally this will yield identical animation sequences regardless of
         * the device's actual refresh rate.
         */
        private void update(long timeStampNanos) {
            // Compute time from previous frame.
            long intervalNanos;
            if (mPrevTimeNanos == 0) {
                intervalNanos = 0;
            } else {
                intervalNanos = timeStampNanos - mPrevTimeNanos;

                final long ONE_SECOND_NANOS = 1000000000L;
                if (intervalNanos > ONE_SECOND_NANOS) {
                    // A gap this big should only happen if something paused us.  We can
                    // either cap the delta at one second, or just pretend like this is
                    // the first frame and not advance at all.
                    Log.d(TAG, "Time delta too large: " +
                            (double) intervalNanos / ONE_SECOND_NANOS + " sec");
                    intervalNanos = 0;
                }
            }
            mPrevTimeNanos = timeStampNanos;
        }

        /**
         * Draws the scene.
         */
        private void draw() {
            GlUtil.checkGlError("draw start");

            gpuImageRenderer.onDrawFrame(null);

            GlUtil.checkGlError("draw done");
        }

        public void changeFilter(GPUImageFilter filter) {
            gpuImageRenderer.setFilter(filter);
        }
    }

    /**
     * Handler for RenderThread.  Used for messages sent from the UI thread to the render thread.
     * <p/>
     * The object is created on the render thread, and the various "send" methods are called
     * from the UI thread.
     */
    private static class RenderHandler extends Handler {
        private static final int MSG_SURFACE_CREATED = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_DO_FRAME = 2;
        private static final int MSG_RECORDING_ENABLED = 3;
        private static final int MSG_RECORD_METHOD = 4;
        private static final int MSG_SHUTDOWN = 5;
        private static final int MSG_FILTER_CHANGED = 6;
        private static final int MSG_SWITCH_CAMERA = 7;

        // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
        // but no real harm in it.
        private WeakReference<RenderThread> mWeakRenderThread;

        /**
         * Call from render thread.
         */
        public RenderHandler(RenderThread rt) {
            mWeakRenderThread = new WeakReference<RenderThread>(rt);
        }

        /**
         * Sends the "surface created" message.
         * <p/>
         * Call from UI thread.
         */
        public void sendSurfaceCreated() {
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CREATED));
        }

        /**
         * Sends the "surface changed" message, forwarding what we got from the SurfaceHolder.
         * <p/>
         * Call from UI thread.
         */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format,
                                       int width, int height) {
            // ignore format
            sendMessage(obtainMessage(RenderHandler.MSG_SURFACE_CHANGED, width, height));
        }

        /**
         * Sends the "do frame" message, forwarding the Choreographer event.
         * <p/>
         * Call from UI thread.
         */
        public void sendDoFrame(long frameTimeNanos) {
            sendMessage(obtainMessage(RenderHandler.MSG_DO_FRAME,
                    (int) (frameTimeNanos >> 32), (int) frameTimeNanos));
        }

        /**
         * Enable or disable recording.
         * <p/>
         * Call from non-UI thread.
         */
        public void setRecordingEnabled(boolean enabled) {
            sendMessage(obtainMessage(MSG_RECORDING_ENABLED, enabled ? 1 : 0, 0));
        }

        /**
         * Set the method used to render a frame for the encoder.
         * <p/>
         * Call from non-UI thread.
         */
        public void setRecordMethod(int recordMethod) {
            sendMessage(obtainMessage(MSG_RECORD_METHOD, recordMethod, 0));
        }

        public void changeFilter(GPUImageFilter filter) {
            sendMessage(obtainMessage(MSG_FILTER_CHANGED, filter));
        }

        /**
         * Sends the "shutdown" message, which tells the render thread to halt.
         * <p/>
         * Call from UI thread.
         */
        public void sendShutdown() {
            sendMessage(obtainMessage(RenderHandler.MSG_SHUTDOWN));
        }

        @Override  // runs on RenderThread
        public void handleMessage(Message msg) {
            int what = msg.what;
            //Log.d(TAG, "RenderHandler [" + this + "]: what=" + what);

            RenderThread renderThread = mWeakRenderThread.get();
            if (renderThread == null) {
                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null");
                return;
            }

            switch (what) {
                case MSG_SURFACE_CREATED:
                    renderThread.surfaceCreated();
                    break;
                case MSG_SURFACE_CHANGED:
                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_DO_FRAME:
                    long timestamp = (((long) msg.arg1) << 32) |
                            (((long) msg.arg2) & 0xffffffffL);
                    renderThread.doFrame(timestamp);
                    break;
                case MSG_RECORDING_ENABLED:
                    renderThread.setRecordingEnabled(msg.arg1 != 0);
                    break;
                case MSG_RECORD_METHOD:
                    renderThread.setRecordMethod(msg.arg1);
                    break;
                case MSG_SHUTDOWN:
                    renderThread.shutdown();
                    break;
                case MSG_FILTER_CHANGED:
                    renderThread.changeFilter((GPUImageFilter) msg.obj);
                    break;
                case MSG_SWITCH_CAMERA:
                    Camera camera = (Camera) ((Object[])msg.obj)[0];
                    int degress = (Integer) ((Object[])msg.obj)[1];

                    renderThread.switchCamera(camera, degress);
                    break;
                default:
                    throw new RuntimeException("unknown message " + what);
            }
        }

        public void switchCamera(Camera camera, int degress) {
            Object [] params = new Object[2];
            params[0] = camera;
            params[1] = degress;
            sendMessage(obtainMessage(MSG_SWITCH_CAMERA, params));
        }
    }
}
