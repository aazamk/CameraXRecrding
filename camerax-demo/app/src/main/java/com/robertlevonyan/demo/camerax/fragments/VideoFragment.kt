package com.robertlevonyan.demo.camerax.fragments

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.MutableLiveData
import androidx.navigation.Navigation
import com.robertlevonyan.demo.camerax.R
import com.robertlevonyan.demo.camerax.databinding.FragmentVideoBinding
import com.robertlevonyan.demo.camerax.utils.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


@ExperimentalCamera2Interop
@SuppressLint("RestrictedApi")
class VideoFragment : BaseFragment<FragmentVideoBinding>(R.layout.fragment_video) {
    // An instance for display manager to get display change callbacks
    private val displayManager by lazy { requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    private var displayId = -1

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(requireContext()) }
    private lateinit var recordingState: VideoRecordEvent
    private val captureLiveStatus = MutableLiveData<String>()

    // Selector showing which camera is selected (front or back)
    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA


    // Selector showing is recording currently active
    private var isRecording = false

    // A lazy instance of the current fragment's view binding
    override val binding: FragmentVideoBinding by lazy { FragmentVideoBinding.inflate(layoutInflater) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        @SuppressLint("UnsafeExperimentalUsageError", "UnsafeOptInUsageError")
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@VideoFragment.displayId) {
                preview?.targetRotation = view.display.rotation
                videoCapture?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }


    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()

        displayManager.registerDisplayListener(displayListener, null)

        binding.run {
            viewFinder.addOnAttachStateChangeListener(object :
                View.OnAttachStateChangeListener {
                override fun onViewDetachedFromWindow(v: View) =
                    displayManager.registerDisplayListener(displayListener, null)

                override fun onViewAttachedToWindow(v: View) =
                    displayManager.unregisterDisplayListener(displayListener)
            })


            binding.btnRecordVideo.apply {
                setOnClickListener {
                    if (!this@VideoFragment::recordingState.isInitialized ||
                        recordingState is VideoRecordEvent.Finalize
                    ) {
                        recordVideo()
                    } else {
                        when (recordingState) {
                            is VideoRecordEvent.Start -> {
                                recording?.stop()
                                recording = null

                            }
                            else -> throw IllegalStateException("recordingState in unknown state")
                        }
                    }
                }
                isRecording = false
            }
            btnSwitchCamera.setOnClickListener { toggleCamera() }

        }
    }

    private fun initViews() {
        captureLiveStatus.observe(viewLifecycleOwner) {
            binding.captureStatus?.apply {
                post { text = it }
            }
        }
        adjustInsets()
    }

    /**
     * This methods adds all necessary margins to some views based on window insets and screen orientation
     * */
    private fun adjustInsets() {
        activity?.window?.fitSystemWindows()
        binding.btnRecordVideo.onWindowInsets { view, windowInsets ->
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                view.bottomMargin = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            }
        }
    }

    /**
     * Change the facing of camera
     *  toggleButton() function is an Extension function made to animate button rotation
     * */
    private fun toggleCamera() = binding.btnSwitchCamera.toggleButton(
        flag = lensFacing == CameraSelector.DEFAULT_BACK_CAMERA,
        rotationAngle = 180f,
        firstIcon = R.drawable.ic_outline_camera_rear,
        secondIcon = R.drawable.ic_outline_camera_front,
    ) {
        lensFacing = if (it) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        startCamera()
    }

    /**
     * Unbinds all the lifecycles from CameraX, then creates new with new parameters
     * */
    private fun startCamera() {
        // This is the Texture View where the camera will be rendered
        val viewFinder = binding.viewFinder

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // The display information
            val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
            // The ratio for the output image and preview
            val aspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
            // The display rotation
            val rotation = viewFinder.display.rotation

            val localCameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

            // The Configuration of camera preview
            preview = Preview.Builder()
                .setTargetAspectRatio(aspectRatio) // set the camera aspect ratio
                .setTargetRotation(rotation) // set the camera rotation
                .build()

            val cameraInfo = localCameraProvider.availableCameraInfos.filter {
                Camera2CameraInfo
                    .from(it)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            }

            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.SD, Quality.HD, Quality.FHD, Quality.UHD),
                FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
            )


            val recorder = Recorder.Builder().setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            localCameraProvider.unbindAll()

            try {
                camera = localCameraProvider.bindToLifecycle(
                    viewLifecycleOwner, // current lifecycle owner
                    lensFacing, // either front or back facing
                    preview, // camera preview use case
                    videoCapture, // video capture use case
                )

                // Attach the viewfinder's surface provider to preview use case
                preview?.setSurfaceProvider(viewFinder.surfaceProvider)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind use cases", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    /**
     * Navigate to PreviewFragment
     * */
    private fun openPreview() {
        view?.let { Navigation.findNavController(it).navigate(R.id.action_video_to_preview) }
    }

    var recording: Recording? = null

    @SuppressLint("MissingPermission")
    private fun recordVideo() {

        val name = "CameraX-recording-${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            requireContext().contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .setDurationLimitMillis(21000)
            .build()
        recording = videoCapture?.output
            ?.prepareRecording(requireContext(), mediaStoreOutput)
            ?.withAudioEnabled()
            ?.start(mainThreadExecutor) { event ->
                if (event !is VideoRecordEvent.Status)
                    recordingState = event
                updateUI(event)
            }
        isRecording = !isRecording
    }

    override fun onPermissionGranted() {
        // Each time apps is coming to foreground the need permission check is being processed
        binding.viewFinder.let { vf ->
            vf.post {
                // Setting current display ID
                displayId = vf.display.displayId
                startCamera()
            }
        }
    }
    override fun onBackPressed() = requireActivity().finish()

    override fun onStop() {
        super.onStop()
    }

    private fun updateUI(event: VideoRecordEvent) {
        val state = if (event is VideoRecordEvent.Status) recordingState.getNameString()
        else event.getNameString()
        when (event) {
            is VideoRecordEvent.Start -> {
                showUI(UiState.RECORDING, state)
            }

            is VideoRecordEvent.Finalize -> {
                showUI(UiState.FINALIZED, state)
            }
        }

        val stats = event.recordingStats
        val time = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(stats.recordedDurationNanos)
        val minutes: Long = time / 60
        val seconds: Long = time % 60
        val formattedDuration = String.format("%02d:%02d", minutes, seconds)
        var text = "$formattedDuration"
        if(event is VideoRecordEvent.Finalize)
            text = "${text}\nFile saved to: ${event.outputResults.outputUri}"

        captureLiveStatus.value = text
        Log.i(TAG, "recording event: $text")
    }

    private fun showUI(state: UiState, status: String = "idle") {
        binding.let {
            when (state) {
                UiState.IDLE -> {
                    it.btnRecordVideo.setImageResource(R.drawable.ic_start)
                    it.btnSwitchCamera.visibility = View.VISIBLE
                }

                UiState.RECORDING -> {
                    it.btnSwitchCamera.visibility = View.INVISIBLE
                    it.btnRecordVideo.setImageResource(R.drawable.ic_stop)
                    it.btnRecordVideo.isEnabled = true
                }

                UiState.FINALIZED -> {
                    it.btnRecordVideo.setImageResource(R.drawable.ic_start)
                }
            }
            it.captureStatus?.text = status
        }
    }

    enum class UiState {
        IDLE,       // Not recording, all UI controls are active.
        RECORDING,  // Camera is recording, only display Pause/Resume & Stop button.
        FINALIZED,  // Recording just completes, disable all RECORDING UI controls.
    }

    companion object {
        private const val TAG = "CameraXDemo"
        const val KEY_GRID = "sPrefGridVideo"

        private const val RATIO_4_3_VALUE = 4.0 / 3.0 // aspect ratio 4x3
        private const val RATIO_16_9_VALUE = 16.0 / 9.0 // aspect ratio 16x9
    }
}
