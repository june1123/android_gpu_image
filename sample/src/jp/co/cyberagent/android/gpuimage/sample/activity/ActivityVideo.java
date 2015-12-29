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
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

import jp.co.cyberagent.android.gpuimage.GPUImage;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageYUVFilter;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools.FilterAdjuster;
import jp.co.cyberagent.android.gpuimage.sample.GPUImageFilterTools.OnGpuImageFilterChosenListener;
import jp.co.cyberagent.android.gpuimage.sample.R;

public class ActivityVideo extends Activity implements OnSeekBarChangeListener,
        OnClickListener {

    private static final int REQUEST_PICK_VIDEO = 1;

    private VideoSurfaceView mVideoView;
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_camera);
        ((SeekBar) findViewById(R.id.seekBar)).setOnSeekBarChangeListener(this);
        findViewById(R.id.button_choose_filter).setOnClickListener(this);

        glSurfaceView = (GLSurfaceView) findViewById(R.id.surfaceView);

        mGPUImage = new GPUImage(this);
        mGPUImage.setGLSurfaceView(glSurfaceView);

        Intent photoPickerIntent = new Intent(Intent.ACTION_PICK);
        photoPickerIntent.setType("video/*");
        startActivityForResult(photoPickerIntent, REQUEST_PICK_VIDEO);

        //handleVideo(Uri.parse("content://media/external/video/media/1066"));
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        switch (requestCode) {
            case REQUEST_PICK_VIDEO:
                if (resultCode == RESULT_OK) {
                    handleVideo(data.getData());
                } else {
                    finish();
                }
                break;

            default:
                super.onActivityResult(requestCode, resultCode, data);
                break;
        }
    }

    private GLSurfaceView glSurfaceView;
    private GPUImage mGPUImage;
    private GPUImageFilter mFilter;
    private FilterAdjuster mFilterAdjuster;
    private MediaPlayer mediaPlayer;

    private boolean mediaPlayerPaused = false;
    @Override
    protected void onResume() {
        super.onResume();

        if( mediaPlayer != null && mediaPlayerPaused) {
            mediaPlayer.start();
        }

        mediaPlayerPaused = false;
    }

    @Override
    protected void onPause() {
        super.onPause();

        if( mediaPlayer != null && mediaPlayer.isPlaying() )  {
            mediaPlayerPaused = true;
            mediaPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if( mediaPlayer != null ) {
            mediaPlayer.release();
        }
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
            break;

            case R.id.img_switch_camera:
                break;
        }
    }

    private void switchFilterTo(final GPUImageFilter filter) {
        if (mFilter == null
                || (filter != null && !mFilter.getClass().equals(filter.getClass()))) {
            mFilter = filter;

            GPUImageFilterGroup gpuImageFilterGroup = new GPUImageFilterGroup();
            gpuImageFilterGroup.addFilter(new GPUImageYUVFilter());
            gpuImageFilterGroup.addFilter(mFilter);

            mGPUImage.setFilter(gpuImageFilterGroup);
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

    private Uri selectedVideoUri;
    private void handleVideo(Uri videoUri) {
        this.selectedVideoUri = videoUri;

        try {
            Log.e("eee", "selectedVideoUri " + selectedVideoUri );
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, selectedVideoUri);
            mGPUImage.setUpMediaPlayer(mediaPlayer);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        // playback();
    }

    private void playback() {
        if( selectedVideoUri == null) {
            return;
        }

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, selectedVideoUri);
            mGPUImage.setUpMediaPlayer(mediaPlayer);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

}
