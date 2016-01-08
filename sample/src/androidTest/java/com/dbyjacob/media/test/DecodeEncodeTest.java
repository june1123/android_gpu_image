package com.dbyjacob.media.test;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.GLES20;
import android.os.Environment;
import android.test.AndroidTestCase;
import android.util.Log;
import android.view.Surface;

import com.android.grafika.TextureMovieEncoder2;
import com.android.grafika.VideoEncoderCore;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.WindowSurface;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import jp.co.cyberagent.android.gpuimage.GPUImage;
import jp.co.cyberagent.android.gpuimage.GPUImageRenderer;
import jp.co.cyberagent.android.gpuimage.PixelBuffer;
import jp.co.cyberagent.android.gpuimage.Rotation;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSobelEdgeDetection;
import jp.co.cyberagent.android.gpuimage.filter.GPUImageYUVFilter;

/**
 * Created by June on 2016. 1. 7..
 */
public class DecodeEncodeTest extends AndroidTestCase {
    public static final boolean VERBOSE = true;           // lots of logging
    public static final String TAG = "DecodeEncodeTest";

    private GPUImageFilter mFilter;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        GPUImageFilterGroup gpuImageFilterGroup = new GPUImageFilterGroup();
        gpuImageFilterGroup.addFilter(new GPUImageYUVFilter());
        gpuImageFilterGroup.addFilter(new GPUImageSobelEdgeDetection());
        mFilter = gpuImageFilterGroup;
    }

    public void testDecode() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                testEncode("sdcard/debug/source.mp4", "sdcard/debug/output.mp4");
            }
        });
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private File getDebugDir() {
        return new File(Environment.getExternalStorageDirectory(), "debug");
    }

    private void testEncode(String sourceFilePath, String dstFilePath) {

        GPUImageRenderer renderer = new GPUImageRenderer(mFilter);
        renderer.setRotation(Rotation.NORMAL,
                false, false);
        renderer.setScaleType(GPUImage.ScaleType.CENTER_INSIDE);

        try {
            extractMpegFrames(640, 480, sourceFilePath, dstFilePath, renderer);
        } catch (IOException e) {
            e.printStackTrace();
        }

        mFilter.destroy();
    }

    private void extractMpegFrames(int saveWidth, int saveHeight,
                                   String sourceFilepath,
                                   String dstFilePath, final GPUImageRenderer gpuImageRenderer) throws IOException {
        MediaCodec decoder = null;
        PixelBuffer pixelBuffer = null;
        MediaExtractor extractor = null;
        Surface outputSurface = null;

        try {
            File inputFile = new File(sourceFilepath);   // must be an absolute path
            // The MediaExtractor error messages aren't very useful.  Check to see if the input
            // file exists so we can throw a better one if it's not there.
            if (!inputFile.canRead()) {
                throw new FileNotFoundException("Unable to read " + inputFile);
            }

            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile.toString());
            final int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + inputFile);
            }
            extractor.selectTrack(trackIndex);

            final MediaFormat format = extractor.getTrackFormat(trackIndex);
            if (VERBOSE) {
                Log.d(TAG, "Video size is " + format.getInteger(MediaFormat.KEY_WIDTH) + "x" +
                        format.getInteger(MediaFormat.KEY_HEIGHT));
            }
            // Create a MediaCodec decoder, and configure it with the MediaFormat from the
            // extractor.  It's very important to use the format from the extractor because
            // it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
            String mime = format.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);

            // Could use width/height from the MediaFormat to get full-size frames.
            setupEncoder(new File(dstFilePath), saveWidth, saveHeight);
            mInputWindowSurface.makeCurrent();

            gpuImageRenderer.onSurfaceCreated(null, null);
            gpuImageRenderer.onSurfaceChanged(null, saveWidth, saveHeight);

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            SurfaceTexture surfaceTexture = new SurfaceTexture(textures[0]);

            gpuImageRenderer.setInputSurfaceTexture(surfaceTexture, saveWidth, saveHeight);
            DecodeContext pixelBufferWrap = new DecodeContext(gpuImageRenderer, saveWidth, saveHeight);
            Surface surface = new Surface(surfaceTexture);
            decoder.configure(format, surface, null, 0);
            decoder.start();

            try {
                doExtract(extractor, null, trackIndex, decoder, pixelBufferWrap);
            } catch (IOException e) {
                e.printStackTrace();
            }

        } finally {

            if( outputSurface != null ) {
                outputSurface.release();
            }
            // release everything we grabbed
            if (pixelBuffer != null) {
                pixelBuffer.destroy();
                pixelBuffer = null;
            }
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }

            if( mVideoEncoder != null ) {
                mVideoEncoder.stopRecording();
            }
        }
    }

    private int selectTrack(MediaExtractor extractor) {
        // Select the first video track we find, ignore the rest.
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }

        return -1;
    }

    /**
     * Work loop.
     */
    private void doExtract(MediaExtractor extractor, MediaMuxer mediaMuxer, int videoTrackIndex, MediaCodec decoder,
                           DecodeContext decodeContext) throws IOException {

        final int TIMEOUT_USEC = 10000;
        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int inputChunk = 0;
        int decodeCount = 0;
        long frameSaveTime = 0;

        boolean outputDone = false;
        boolean inputDone = false;


        while (!outputDone) {
            if (VERBOSE) Log.d(TAG, "loop");

            // Feed more data to the decoder.
            extractor.selectTrack(videoTrackIndex);
            if (!inputDone) {
                int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputBufIndex >= 0) {
                    ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                    // Read the sample data into the ByteBuffer.  This neither respects nor
                    // updates inputBuf's position, limit, etc.
                    int chunkSize = extractor.readSampleData(inputBuf, 0);
                    if (chunkSize < 0) {
                        // End of stream -- send empty frame with EOS flag set.
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                        if (VERBOSE) Log.d(TAG, "sent input EOS");
                    } else {
                        if (extractor.getSampleTrackIndex() != videoTrackIndex) {
                            Log.w(TAG, "WEIRD: got sample from track " +
                                    extractor.getSampleTrackIndex() + ", expected " + videoTrackIndex);
                        }

                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufIndex, 0, chunkSize,
                                presentationTimeUs, 0 /*flags*/);
                        if (VERBOSE) {
                            Log.d(TAG, "submitted frame " + inputChunk + " to dec, size=" +
                                    chunkSize);
                        }

                        // fillOtherChannel(extractor, mediaMuxer, videoTrackIndex);
                        inputChunk++;
                        extractor.advance();
                    }
                } else {
                    if (VERBOSE) Log.d(TAG, "input buffer not available");
                }
            }

            if (!outputDone) {
                int decoderStatus = decoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    if (VERBOSE) Log.d(TAG, "no output from decoder available");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not important for us, since we're using Surface
                    if (VERBOSE) Log.d(TAG, "decoder output buffers changed");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = decoder.getOutputFormat();
                    if (VERBOSE) Log.d(TAG, "decoder output format changed: " + newFormat);
                } else if (decoderStatus < 0) {
                    fail("unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
                } else { // decoderStatus >= 0
                    if (VERBOSE) Log.d(TAG, "surface decoder given buffer " + decoderStatus +
                            " (size=" + info.size + ")");
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (VERBOSE) Log.d(TAG, "output EOS");
                        outputDone = true;
                    }

                    boolean doRender = (info.size != 0);

                    // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                    // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
                    // that the texture will be available before the call returns, so we
                    // need to wait for the onFrameAvailable callback to fire.
                    decoder.releaseOutputBuffer(decoderStatus, doRender);
                    if (doRender) {
                        if (VERBOSE) Log.d(TAG, "awaiting decode of frame " + decodeCount);

                        mInputWindowSurface.makeCurrent();
                        decodeContext.awaitNewImage();
                        decodeContext.drawImage();
                        mVideoEncoder.frameAvailableSoon();
                        mInputWindowSurface.setPresentationTime(info.presentationTimeUs);
                        mInputWindowSurface.swapBuffers();
                        mEglCore.makeNothingCurrent();

                        if( false ) {
                            File debugDir = new File(Environment.getExternalStorageDirectory(), "debug");
                            debugDir.mkdirs();
                            File outputFile = new File(debugDir,
                                    String.format("frame-%02d.png", decodeCount));
                            long startWhen = System.nanoTime();
                            decodeContext.saveFrame(outputFile.toString());
                            frameSaveTime += System.nanoTime() - startWhen;
                        }
                        decodeCount++;
                    }
                }
            }
        }
    }

    class DecodeContext implements SurfaceTexture.OnFrameAvailableListener {

        private ByteBuffer mPixelBuf;                       // used by saveFrame()
        private GPUImageRenderer gpuImageRenderer;
        private boolean frameAvailable = false;
        private Object frameSyncObject = new Object();
        private int mWidth;
        private int mHeight;

        public DecodeContext(GPUImageRenderer gpuImageRenderer, int width, int height) {

            this.gpuImageRenderer = gpuImageRenderer;
            mWidth = width;
            mHeight = height;

            mPixelBuf = ByteBuffer.allocateDirect(mWidth * mHeight * 4);
            mPixelBuf.order(ByteOrder.LITTLE_ENDIAN);

            setFrameAvailableListener(gpuImageRenderer.getInputSurfaceTexture());
        }

        private void setFrameAvailableListener(SurfaceTexture surfaceTexture) {
            surfaceTexture.setOnFrameAvailableListener(this);
        }


        public void awaitNewImage() {

            final int TIMEOUT_MS = 2500;

            synchronized (frameSyncObject) {

                while (!frameAvailable) {
                    try {
                        // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                        // stalling the test if it doesn't arrive.
                        frameSyncObject.wait(TIMEOUT_MS);
                        if (!frameAvailable) {
                            // TODO: if "spurious wakeup", continue while loop
                            throw new RuntimeException("frame wait timed out");
                        }
                    } catch (InterruptedException ie) {
                        // shouldn't happen
                        throw new RuntimeException(ie);
                    }
                }
                frameAvailable = false;
            }

        }

        public void drawImage() {
            gpuImageRenderer.onDrawFrame(null);
        }

        // SurfaceTexture callback
        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            if (VERBOSE) Log.d(TAG, "new frame available");
            synchronized (frameSyncObject) {
                if (frameAvailable) {
                    throw new RuntimeException("mFrameAvailable already set, frame could be dropped");
                }
                frameAvailable = true;
                frameSyncObject.notifyAll();
            }
        }

        /**
         * Saves the current frame to disk as a PNG image.
         */
        public void saveFrame(String filename) throws IOException {
            // glReadPixels gives us a ByteBuffer filled with what is essentially big-endian RGBA
            // data (i.e. a byte of red, followed by a byte of green...).  To use the Bitmap
            // constructor that takes an int[] array with pixel data, we need an int[] filled
            // with little-endian ARGB data.
            //
            // If we implement this as a series of buf.get() calls, we can spend 2.5 seconds just
            // copying data around for a 720p frame.  It's better to do a bulk get() and then
            // rearrange the data in memory.  (For comparison, the PNG compress takes about 500ms
            // for a trivial frame.)
            //
            // So... we set the ByteBuffer to little-endian, which should turn the bulk IntBuffer
            // get() into a straight memcpy on most Android devices.  Our ints will hold ABGR data.
            // Swapping B and R gives us ARGB.  We need about 30ms for the bulk get(), and another
            // 270ms for the color swap.
            //
            // We can avoid the costly B/R swap here if we do it in the fragment shader (see
            // http://stackoverflow.com/questions/21634450/ ).
            //
            // Having said all that... it turns out that the Bitmap#copyPixelsFromBuffer()
            // method wants RGBA pixels, not ARGB, so if we create an empty bitmap and then
            // copy pixel data in we can avoid the swap issue entirely, and just copy straight
            // into the Bitmap from the ByteBuffer.
            //
            // Making this even more interesting is the upside-down nature of GL, which means
            // our output will look upside-down relative to what appears on screen if the
            // typical GL conventions are used.  (For ExtractMpegFrameTest, we avoid the issue
            // by inverting the frame when we render it.)
            //
            // Allocating large buffers is expensive, so we really want mPixelBuf to be
            // allocated ahead of time if possible.  We still get some allocations from the
            // Bitmap / PNG creation.

            mPixelBuf.rewind();
            GLES20.glReadPixels(0, 0, mWidth, mHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    mPixelBuf);

            BufferedOutputStream bos = null;
            try {
                bos = new BufferedOutputStream(new FileOutputStream(filename));
                Bitmap bmp = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
                mPixelBuf.rewind();
                bmp.copyPixelsFromBuffer(mPixelBuf);
                bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
                bmp.recycle();
            } finally {
                if (bos != null) bos.close();
            }
            if (VERBOSE) {
                Log.d(TAG, "Saved " + mWidth + "x" + mHeight + " frame as '" + filename + "'");
            }
        }
    }

    //=======================================
    // For encoder

    private TextureMovieEncoder2 mVideoEncoder;
    private WindowSurface mInputWindowSurface;
    private EglCore mEglCore;

    private void setupEncoder(File outputFile, int width, int height) {
        Log.d(TAG, "starting to record");
        // Record at 1280x720, regardless of the window dimensions.  The encoder may
        // explode if given "strange" dimensions, e.g. a width that is not a multiple
        // of 16.  We can box it as needed to preserve dimensions.
        final int BIT_RATE = 4000000;   // 4Mbps
        final int VIDEO_WIDTH = width;
        final int VIDEO_HEIGHT = height;

        VideoEncoderCore encoderCore;
        try {
            encoderCore = new VideoEncoderCore(VIDEO_WIDTH, VIDEO_HEIGHT,
                    BIT_RATE, outputFile);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }

        mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
        mInputWindowSurface = new WindowSurface(mEglCore, encoderCore.getInputSurface(), true);
        mVideoEncoder = new TextureMovieEncoder2(encoderCore);
    }
}
