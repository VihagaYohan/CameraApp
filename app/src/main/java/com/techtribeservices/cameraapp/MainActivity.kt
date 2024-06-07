package com.techtribeservices.cameraapp

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever.BitmapParams
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.LifecycleCameraController
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.techtribeservices.cameraapp.databinding.ActivityMainBinding
import com.techtribeservices.cameraapp.ml.AutoModel1
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    lateinit var textureView: TextureView
    lateinit var cameraManager: CameraManager
    lateinit var handler: Handler
    lateinit var cameraDevice: CameraDevice
    lateinit var imageView: ImageView
    lateinit var bitmap: Bitmap
    lateinit var model: AutoModel1
    lateinit var imageProcessor: ImageProcessor
    lateinit var labels:List<String>
    val paint = Paint()
    var colors = listOf<Int>(
        Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.GRAY, Color.BLACK,
        Color.DKGRAY, Color.MAGENTA, Color.YELLOW, Color.RED
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        get_Permission()

        labels = FileUtil.loadLabels(this, "labels.txt")
        model = AutoModel1.newInstance(this)
        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(300,300, ResizeOp.ResizeMethod.BILINEAR))
            .build()

        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        imageView = findViewById(R.id.imageView)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        textureView = findViewById(R.id.textureView)
        textureView.surfaceTextureListener = object:TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                open_Camera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {

            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                bitmap = textureView.bitmap!!

                // Creates inputs for reference.
                var image = TensorImage.fromBitmap(bitmap)
                image = imageProcessor.process(image)

                // Runs model inference and gets result.
                val outputs = model.process(image)
                val locations = outputs.locationsAsTensorBuffer.floatArray
                val classes = outputs.classesAsTensorBuffer.floatArray
                val scores = outputs.scoresAsTensorBuffer.floatArray
                val numberOfDetections = outputs.numberOfDetectionsAsTensorBuffer.floatArray

                var mutable = bitmap.copy(
                    Bitmap.Config.ARGB_8888,
                    true
                )
                val canvas = Canvas(mutable)

                val h = mutable.height
                val w = mutable.width

                paint.textSize = h/15f
                paint.strokeWidth = h/85f
                var x = 0
                scores.forEachIndexed { index, fl ->
                    x = index
                    x *= 4
                    if(fl > 0.5) {
                        paint.setColor(colors.get(index))
                        paint.style = Paint.Style.STROKE
                        canvas.drawRect(RectF(
                            locations.get(x+1)*w,
                            locations.get(x)*h,
                            locations.get(x+3)*w,
                            locations.get(x+2)*h
                        ), paint)
                        paint.style = Paint.Style.FILL
                        canvas.drawText(labels.get(
                            classes.get(index).toInt()
                        ) + " " + fl.toString(),
                            locations.get(x+1) * w,
                            locations.get(x)*h, paint)
                    }
                }
                imageView.setImageBitmap(mutable)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Releases model resources if no longer used.
        model.close()
    }

    @SuppressLint("MissingPermission")
    fun open_Camera() {
        cameraManager.openCamera(
            cameraManager.cameraIdList[0],

            object:CameraDevice.StateCallback(){
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera

                    var surfaceTexture = textureView.surfaceTexture
                    var surface = Surface(surfaceTexture)

                    var captureRequest =  cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    captureRequest.addTarget(surface)

                    cameraDevice.createCaptureSession(
                        listOf(surface),
                        object: CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.setRepeatingRequest(
                                    captureRequest.build(),
                                    null,
                                    null)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                TODO("Not yet implemented")
                            }

                        },
                        handler)
                }

                override fun onDisconnected(camera: CameraDevice) {

                }

                override fun onError(camera: CameraDevice, error: Int) {

                }

            },
            handler
        )
    }

    fun get_Permission() {
        if(ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            get_Permission()
        }
    }
}