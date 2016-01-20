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
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewTreeObserver;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import java.io.File;

import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools.FilterAdjuster;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools.OnGpuImageFilterChosenListener;
import jp.co.cyberagent.android.gpuimage.sample.R;

public class ActivityCameraRecording extends Activity implements OnSeekBarChangeListener, OnClickListener {

    private static final String TAG = "ActivityCameraRecoding";

    private File mOutputFile;
    private FilterAdjuster mFilterAdjuster;
    private CameraRecordSurfaceView mCameraRecordSurfaceView;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        File outputDir = new File(Environment.getExternalStorageDirectory(), "debug");
        if( !outputDir.exists() ) {
            outputDir.mkdirs();
        }

        mOutputFile = new File(outputDir, "fbo-gl-recording.mp4");

        setContentView(R.layout.activity_camera);
        ((SeekBar) findViewById(R.id.seekBar)).setOnSeekBarChangeListener(this);
        findViewById(R.id.button_choose_filter).setOnClickListener(this);
        findViewById(R.id.button_capture).setOnClickListener(this);

        mCameraRecordSurfaceView = (CameraRecordSurfaceView) findViewById(R.id.surfaceView);
        View cameraSwitchView = findViewById(R.id.img_switch_camera);
        cameraSwitchView.setOnClickListener(this);

        mCameraRecordSurfaceView.openCamera(Camera.CameraInfo.CAMERA_FACING_FRONT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCameraRecordSurfaceView.onResume();

        updateControls();
    }

    private void updateControls() {
        TextView recordView = (TextView) findViewById(R.id.record_state);
        recordView.setText(mCameraRecordSurfaceView.isRecordingEnabled() ? "recording" : "idle");
    }

    @Override
    protected void onPause() {
        super.onPause();
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
                switchCamera();
                break;
        }
    }

    private void switchCamera(){

        int cameraFacing = 0;
        if( mCameraRecordSurfaceView.getCameraFacing() == Camera.CameraInfo.CAMERA_FACING_FRONT ) {
            cameraFacing = Camera.CameraInfo.CAMERA_FACING_BACK;
        } else {
            cameraFacing = Camera.CameraInfo.CAMERA_FACING_FRONT;
        }
        mCameraRecordSurfaceView.openCamera(cameraFacing);
    }

    private void toggleRecord() {
        boolean prev = mCameraRecordSurfaceView.isRecordingEnabled();
        mCameraRecordSurfaceView.setRecordingEnabled(!prev);
        updateControls();
    }

    private void switchFilterTo(final GPUImageFilter filter) {
        mCameraRecordSurfaceView.switchFilter(filter);
        mFilterAdjuster = new FilterAdjuster(filter);
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
}
