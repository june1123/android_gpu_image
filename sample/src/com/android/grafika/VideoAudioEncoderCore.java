/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * This class wraps up the core components used for surface-input video encoding.
 * <p>
 * Once created, frames are fed to the input surface.  Remember to provide the presentation
 * time stamp, and always call drainEncoder() before swapBuffers() to ensure that the
 * producer side doesn't get backed up.
 * <p>
 * This class is not thread-safe, with one exception: it is valid to use the input surface
 * on one thread, and drain the output on a different thread.
 */
public class VideoAudioEncoderCore extends VideoEncoderCore{
    private static final String TAG = "VideoAudioEncoderCore";
    private static final boolean VERBOSE = true;

    // TODO: these ought to be configurable as well
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 30;               // 30fps
    private static final int IFRAME_INTERVAL = 5;           // 5 seconds between I-frames

    private Surface mInputSurface;
    private MediaMuxer mMuxer;
    private MediaCodec mEncoder;
    private MediaCodec.BufferInfo mVideoBufferInfo;

    private int mOutputAudioTrack;
    private int mOutputVideoTrack;

    private boolean mMuxerStarted;
    private AudioEncodingContext mAudioEncodingContext;

    /**
     * Configures encoder and muxer state, and prepares the input Surface.
     */
    public VideoAudioEncoderCore(int width, int height, int bitRate, File outputFile)
            throws IOException {
        super(width, height, bitRate, outputFile);

        mVideoBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mEncoder.createInputSurface();
        mEncoder.start();

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        mMuxer = new MediaMuxer(outputFile.toString(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        prepareAudioRecording();

        mOutputAudioTrack = -1;
        mOutputVideoTrack = -1;
        mMuxerStarted = false;
    }

    /**
     * Returns the encoder's input surface.
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    /**
     * Releases encoder resources.
     */
    public void release() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }

        if( mAudioEncodingContext != null ) {
            if( mAudioEncodingContext.audioEncoder != null ) {
                mAudioEncodingContext.audioEncoder.stop();
                mAudioEncodingContext.audioEncoder.release();
            }

            if( mAudioEncodingContext.audioRecord != null ) {
                mAudioEncodingContext.audioRecord.stop();
                mAudioEncodingContext.audioRecord.release();
            }
        }
        if (mMuxer != null) {
            // TODO: stop() throws an exception if you haven't fed it any data.  Keep track
            //       of frames submitted, and don't call stop() if we haven't written anything.
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
    }

    private MediaFormat mEncoderOutputAudioFormat;
    private MediaFormat mEncoderOutputVideoFormat;
    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     * <p>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     * <p>
     * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
     * not recording audio.
     */
    public void drainEncoder(boolean endOfStream) {
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            mEncoder.signalEndOfInputStream();
            mAudioEncodingContext.audioRecorderDone = true;
        }

        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (mEncoderOutputVideoFormat == null || mMuxerStarted) {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mVideoBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);
                mEncoderOutputVideoFormat = newFormat;

            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mVideoBufferInfo.size = 0;
                }

                if (mVideoBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mVideoBufferInfo.offset);
                    encodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);

                    mMuxer.writeSampleData(mOutputVideoTrack, encodedData, mVideoBufferInfo);
                    if (VERBOSE) {
                        Log.d(TAG, "sent " + mVideoBufferInfo.size + " bytes to muxer, ts=" +
                                mVideoBufferInfo.presentationTimeUs);
                    }
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }

        encodeAudioLoop(mAudioEncodingContext);

        if (!mMuxerStarted && mEncoderOutputAudioFormat != null && mEncoderOutputVideoFormat != null) {

            Log.d(TAG, "muxer: adding video track.");
            mOutputVideoTrack = mMuxer.addTrack(mEncoderOutputVideoFormat);


            Log.d(TAG, "muxer: adding audio track.");
            mOutputAudioTrack = mMuxer.addTrack(mEncoderOutputAudioFormat);

            Log.d(TAG, "muxer: starting");
            mMuxer.start();
            mMuxerStarted = true;
        }
    }

    //------------------
    // For audio ..
    private static final String OUTPUT_AUDIO_MIME_TYPE = "audio/mp4a-latm"; // Advanced Audio Coding
    private static final int OUTPUT_AUDIO_CHANNEL_COUNT = 1; // Must match the input stream.
    private static final int OUTPUT_AUDIO_BIT_RATE = 128 * 1024;
    private static final int OUTPUT_AUDIO_AAC_PROFILE =
            MediaCodecInfo.CodecProfileLevel.AACObjectLC;
    private static final int OUTPUT_AUDIO_SAMPLE_RATE_HZ = 44100; // Must match the input stream.

    private void prepareAudioRecording() throws IOException {

        if( mAudioEncodingContext == null ) {
            mAudioEncodingContext = new AudioEncodingContext();

            MediaCodecInfo audioCodecInfo = selectCodec(OUTPUT_AUDIO_MIME_TYPE);
            MediaFormat outputAudioFormat = MediaFormat.createAudioFormat(
                            OUTPUT_AUDIO_MIME_TYPE, OUTPUT_AUDIO_SAMPLE_RATE_HZ,
                            OUTPUT_AUDIO_CHANNEL_COUNT);
            outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BIT_RATE);
            outputAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, OUTPUT_AUDIO_AAC_PROFILE);

            outputAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 100);

            // Create a MediaCodec for the desired codec, then configure it as an encoder with
            // our desired properties. Request a Surface to use for input.
            mAudioEncodingContext.audioEncoder = createAudioEncoder(audioCodecInfo, outputAudioFormat);

            int minBufferSize = AudioRecord.getMinBufferSize(RECORDER_SAMPLERATE, RECORDER_CHANNELS, RECORDER_AUDIO_ENCODING);
            mAudioEncodingContext.audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    RECORDER_SAMPLERATE, RECORDER_CHANNELS,
                    RECORDER_AUDIO_ENCODING, minBufferSize);
            mAudioEncodingContext.minBufferSize = minBufferSize;

            mAudioEncodingContext.audioRecord.startRecording();

            mAudioEncodingContext.audioEncoderInputBuffers = mAudioEncodingContext.audioEncoder.getInputBuffers();
            mAudioEncodingContext.audioEncoderOutputBufferInfo = new MediaCodec.BufferInfo();
        }
    }

    private static final int TIMEOUT_USEC = 10000;

    class AudioEncodingContext {
        boolean audioRecorderDone = false;
        boolean audioEncoderDone = false;
        MediaCodec.BufferInfo audioEncoderOutputBufferInfo;
        MediaCodec audioEncoder;

        ByteBuffer [] audioEncoderInputBuffers;

        int audioEncodedFrameCount;

        AudioRecord audioRecord;
        long audioStartTime = 0;
        public int minBufferSize;
    }

    public static final int SAMPLE_RATE = 44100;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int FRAMES_PER_BUFFER = 24; // 1 sec @ 1024 samples/frame (aac)
    private static final int SAMPLES_PER_FRAME = 1024;

    private static final int RECORDER_SAMPLERATE = 44100;
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_ELEMENTS_TO_REC = 1024; // want to play 2048 (2K) since 2 bytes we use only 1024
    private static final int BYTES_PER_ELEMENT = 2; // 2 bytes in 16bit format;

    private void encodeLoopWrap() {

        while( true ) {
            encodeAudioLoop(mAudioEncodingContext);
        }
    }

    private void encodeAudioLoop(AudioEncodingContext encodeContext) {

        // Feed the pending decoded audio buffer to the audio encoder.
        if (VERBOSE) {
            Log.d(TAG, "encode audio loop: " + encodeContext.audioEncoderDone);
        }

        while (!encodeContext.audioEncoderDone) {
            int encoderInputBufferIndex = encodeContext.audioEncoder.dequeueInputBuffer(TIMEOUT_USEC);
            if (encoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) Log.d(TAG, "no audio encoder input buffer");
                break;
            }
            if (VERBOSE) {
                Log.d(TAG, "audio encoder: returned input buffer: " + encoderInputBufferIndex);
            }
            ByteBuffer encoderInputBuffer = encodeContext.audioEncoderInputBuffers[encoderInputBufferIndex];

            byte[] dataBuffer = new byte[encodeContext.minBufferSize];
            int readLength = encodeContext.audioRecord.read(dataBuffer, 0, encodeContext.minBufferSize);
            if (readLength == AudioRecord.ERROR_BAD_VALUE || readLength == AudioRecord.ERROR_INVALID_OPERATION) {
                Log.e("AudioSoftwarePoller", "Read error");
            } else {
                if (VERBOSE) {
                    Log.d(TAG, " audio read from record : " + readLength);
                }
                long curNanoTime = System.nanoTime();
                if( encodeContext.audioStartTime == 0 ) {
                    encodeContext.audioStartTime = curNanoTime;
                }

                long presentationTime = (curNanoTime ) / 1000;
                if (readLength >= 0) {
                    encoderInputBuffer.position(0);
                    encoderInputBuffer.put(dataBuffer, 0, readLength);

                    if (VERBOSE) {
                        Log.d(TAG, " put data to encoder record : " + readLength + " / time : " + presentationTime);
                    }

                    if( encodeContext.audioRecorderDone ) {
                        encodeContext.audioEncoder.queueInputBuffer(
                                encoderInputBufferIndex,
                                0,
                                readLength,
                                presentationTime,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        encodeContext.audioEncoder.queueInputBuffer(
                                encoderInputBufferIndex,
                                0,
                                readLength,
                                presentationTime,
                                0);
                    }
                }
                // We enqueued a pending frame, let's try something else next.
                break;
            }
        }

        // Poll frames from the audio encoder and send them to the muxer.
        ByteBuffer[] encoderOutputBuffers = encodeContext.audioEncoder.getOutputBuffers();
        while (!encodeContext.audioEncoderDone
                && (mEncoderOutputAudioFormat == null || mMuxerStarted)) {

            int encoderOutputBufferIndex = encodeContext.audioEncoder.dequeueOutputBuffer(
                    encodeContext.audioEncoderOutputBufferInfo, TIMEOUT_USEC);

            if (encoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (VERBOSE) Log.d(TAG, "no audio encoder output buffer");
                break;
            } else if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                if (VERBOSE) Log.d(TAG, "audio encoder: output buffers changed");
                encoderOutputBuffers = encodeContext.audioEncoder.getOutputBuffers();
            } else if (encoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (VERBOSE) Log.d(TAG, "audio encoder: output format changed");
                if (mOutputAudioTrack >= 0) {
                    throw new IllegalStateException("audio encoder changed its output format again?");
                }

                mEncoderOutputAudioFormat = encodeContext.audioEncoder.getOutputFormat();
                break;
            } else {

                if (!mMuxerStarted) {
                    throw new IllegalStateException("should be start muxing!!");
                }

                if (VERBOSE) {
                    Log.d(TAG, "audio encoder: returned output buffer: "
                            + encoderOutputBufferIndex);
                    Log.d(TAG, "audio encoder: returned buffer of size "
                            + encodeContext.audioEncoderOutputBufferInfo.size);
                }

                ByteBuffer encoderOutputBuffer =
                        encoderOutputBuffers[encoderOutputBufferIndex];
                if ((encodeContext.audioEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        != 0) {
                    if (VERBOSE) Log.d(TAG, "audio encoder: codec config buffer");
                    // Simply ignore codec config buffers.
                    encodeContext.audioEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
                    break;
                }
                if (VERBOSE) {
                    Log.d(TAG, "audio encoder: returned buffer for time "
                            + encodeContext.audioEncoderOutputBufferInfo.presentationTimeUs);
                }

                MediaCodec.BufferInfo encoderBufferInfo = encodeContext.audioEncoderOutputBufferInfo;
                if (encoderBufferInfo.size != 0) {
                    encoderOutputBuffer.position(encoderBufferInfo.offset);
                    encoderOutputBuffer.limit(encoderBufferInfo.offset + encoderBufferInfo.size);
                    mMuxer.writeSampleData(
                            mOutputAudioTrack, encoderOutputBuffer, encoderBufferInfo);
                }
                if ((encodeContext.audioEncoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        != 0) {
                    if (VERBOSE) Log.d(TAG, "audio encoder: EOS");
                    encodeContext.audioEncoderDone = true;
                }
                encodeContext.audioEncoder.releaseOutputBuffer(encoderOutputBufferIndex, false);
                encodeContext.audioEncodedFrameCount++;
                // We enqueued an encodedWe enqueued an encodedWe enqueued an encoded frame, let's try something else next.
                break;
            }
        }
    }

    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no match was
     * found.
     */
    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    private MediaCodec createAudioEncoder(MediaCodecInfo codecInfo, MediaFormat format) throws IOException {
        MediaCodec encoder = MediaCodec.createByCodecName(codecInfo.getName());
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        return encoder;
    }

}
