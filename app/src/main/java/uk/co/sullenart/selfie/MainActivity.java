package uk.co.sullenart.selfie;

/*
TODO
Accept/reject picture by subsequent clicking.
Auto launch/quit app.
Icon.
 */

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Arrays;

import static android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY;
import static android.media.AudioManager.ACTION_HEADSET_PLUG;

public class MainActivity extends AppCompatActivity {
    public final static String TAG = "selfie";

    private Surface previewSurface;
    private SurfaceView surfaceView;
    private ImageReader imageReader;
    private String frontCameraId;
    private TextView errorTextView;
    private View previewView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        errorTextView = (TextView) findViewById(R.id.error_text);
        previewView = findViewById(R.id.preview);
        surfaceView = (SurfaceView) findViewById(R.id.preview_surface);

        frontCameraId = getFrontCameraId();
        if (frontCameraId == null) {
            showError("Front camera not found");
            return;
        }

        // TODO Get preview size from camera
        Size previewSize = new Size(1920, 1080);

        // Set preview view to chosen size
        ViewGroup.LayoutParams layout = previewView.getLayoutParams();
        layout.width = previewSize.getWidth();
        layout.height = previewSize.getHeight();
        previewView.setLayoutParams(layout);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Check for permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "No camera permission");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 0);
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "No write permission");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
            return;
        }

        // Listen for surface view created so we can start the preview
        surfaceView.getHolder().removeCallback(surfaceHolderListener);
        surfaceView.getHolder().addCallback(surfaceHolderListener);

        // Register headphone listeners
        registerReceiver(noisyListener, new IntentFilter(ACTION_AUDIO_BECOMING_NOISY));
        Log.d(TAG, "Noisy listener registered");

        registerReceiver(plugReceiver, new IntentFilter(ACTION_HEADSET_PLUG));
        Log.d(TAG, "Plug listener registered");
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Stop any camera usage
        if (cameraDevice != null) {
            cameraDevice.close();
        }

        unregisterReceiver(noisyListener);
        Log.d(TAG, "Noisy listener unregistered");

        unregisterReceiver(plugReceiver);
        Log.d(TAG, "Plug listener unregistered");
    }

    private void showError(String text) {
        errorTextView.setText(text);
        errorTextView.setVisibility(View.VISIBLE);
        previewView.setVisibility(View.GONE);
    }

    private void showError(Exception e) {
        Log.e(TAG, e.toString());
        showError(e.getMessage());
    }

    private String getFrontCameraId() {
        try {
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            //noinspection ConstantConditions
            String[] ids = cameraManager.getCameraIdList();
            for (String id : ids) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(id);
                if (((Integer) CameraMetadata.LENS_FACING_FRONT).equals(cameraCharacteristics.get(CameraCharacteristics.LENS_FACING))) {
                    Log.d(TAG, String.format("Found front camera id %s", id));
                    return id;
                }
            }
        } catch (CameraAccessException e) {
            showError(e);
        }

        return null;
    }

    private SurfaceHolder.Callback surfaceHolderListener = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder surfaceHolder) {
            Log.d(TAG, "Surface created");

            previewSurface = surfaceHolder.getSurface();
            startPreview();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        }

        @Override
        public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
        }
    };

    private void startPreview() {
        try {
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

            // Create an image reader with the maximum size of image available from the camera
            // (assumes the maximum size is the first in the list)
            CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(frontCameraId);
            StreamConfigurationMap configs = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            int width = 640;
            int height = 480;
            if (configs != null) {
                Size size = configs.getOutputSizes(ImageFormat.JPEG)[0];
                width = size.getWidth();
                height = size.getHeight();
            }
            Log.d(TAG, String.format("Image reader size %d x %d", width, height));
            imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(imageAvailableListener, null);

            cameraManager.openCamera(frontCameraId, cameraOpenHandler, null);
        } catch (SecurityException | CameraAccessException e) {
            showError(e);
        }
    }

    private CameraDevice.StateCallback cameraOpenHandler = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Log.d(TAG, String.format("Camera opened id %s", cameraDevice.getId()));

            try {
                MainActivity.this.cameraDevice = cameraDevice;
                cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()), cameraSessionHandler, null);
            } catch (CameraAccessException e) {
                showError(e);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.d(TAG, String.format("Camera disconnected id %s", cameraDevice.getId()));
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
        }
    };

    private CameraCaptureSession.StateCallback cameraSessionHandler = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "Session created");
            try {
                cameraCaptureSession = session;

                CaptureRequest.Builder builder = cameraCaptureSession.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(previewSurface);
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY);
                builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE);
                cameraCaptureSession.setRepeatingRequest(builder.build(), null, null);
            } catch (CameraAccessException e) {
                showError(e);
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            showError("Session configuration failed");
        }
    };

    public void onTakePicture(View view) {
        try {
            CaptureRequest.Builder builder = cameraCaptureSession.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(imageReader.getSurface());
            cameraCaptureSession.capture(builder.build(), cameraCaptureHandler, null);
        } catch (CameraAccessException e) {
            showError(e);
        }
    }

    private ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            String filename = String.format("selfie_%d.jpg", System.currentTimeMillis());
            File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Selfie");
            //noinspection ResultOfMethodCallIgnored
            path.mkdirs();
            File file = new File(path, filename);

            try (Image image = imageReader.acquireLatestImage();
                 FileOutputStream stream = new FileOutputStream(file);
                 FileChannel channel = stream.getChannel()) {
                channel.write(image.getPlanes()[0].getBuffer());
                MediaScannerConnection.scanFile(MainActivity.this, new String[]{file.getAbsolutePath()}, null, null);
                Toast.makeText(MainActivity.this, "Photo captured", Toast.LENGTH_SHORT).show();
                Log.d(TAG, String.format("Image captured %d x %d to %s", image.getWidth(), image.getHeight(), file.toString()));
            } catch (IOException e) {
                showError(e);
            }
        }
    };

    private CameraCaptureSession.CaptureCallback cameraCaptureHandler = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            Log.d(TAG, "Capture started");
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            Log.d(TAG, "Capture completed");
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            Log.d(TAG, "Capture failed");
        }
    };

    private BroadcastReceiver noisyListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Noisy audio received");
            onTakePicture(null);
        }
    };

    private BroadcastReceiver plugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra("state", 0);
            Log.d(TAG, String.format("Headphone %s", state == 0 ? "unplugged" : "plugged"));
        }
    };
}
