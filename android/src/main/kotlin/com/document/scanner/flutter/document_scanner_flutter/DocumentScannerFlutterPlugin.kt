package com.document.scanner.flutter.document_scanner_flutter

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StrictMode
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
//import com.scanlibrary.ScanActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import android.R.attr.data
import android.content.Context
import android.database.Cursor
import androidx.core.net.toFile
import androidx.core.content.FileProvider
import com.scanlibrary.ScanActivity
import com.scanlibrary.ScanConstants
import kotlin.collections.HashMap


/** DocumentScannerFlutterPlugin */
class DocumentScannerFlutterPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.ActivityResultListener {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private lateinit var call: MethodCall

    /// For activity binding
    private var activityPluginBinding: ActivityPluginBinding? = null
    private var result: Result? = null
    private var pendingIntent: Intent? = null
    private var pendingRequestCode: Int = -1

    /// For scanner library
    companion object {
        val SCAN_REQUEST_CODE: Int = 101
        private const val PERMISSION_REQUEST_CODE = 102
        private const val PHOTO_PICKER_REQUEST_CODE = 103
        private const val TAG = "DocumentScannerPlugin"
    }

    lateinit var mCurrentPhotoPath: String
    private val scannedBitmaps: ArrayList<Uri> = ArrayList()

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Log.d(TAG, "onAttachedToEngine: Plugin attached to engine")
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "document_scanner_flutter")
        channel.setMethodCallHandler(this)
        Log.d(TAG, "onAttachedToEngine: Method channel set up")
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        Log.d(TAG, "onMethodCall: Method called = ${call.method}")
        this.call = call
        this.result = result

        when (call.method) {
            "camera" -> {
                Log.d(TAG, "onMethodCall: Camera method invoked")
                camera()
            }
            "gallery" -> {
                Log.d(TAG, "onMethodCall: Gallery method invoked")
                gallery()
            }
            else -> {
                Log.w(TAG, "onMethodCall: Unknown method = ${call.method}")
                result.notImplemented()
            }
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Log.d(TAG, "onAttachedToActivity: Activity attached")
        activityPluginBinding = binding
        binding.addActivityResultListener(this)
        binding.addRequestPermissionsResultListener { requestCode, permissions, grantResults ->
            onRequestPermissionsResult(requestCode, permissions, grantResults)
            false // Return false to allow other listeners to process
        }
        
        // Set up global exception handler to catch any uncaught exceptions from ScanActivity
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            if (exception.stackTraceToString().contains("ScanActivity") || 
                exception.stackTraceToString().contains("scanlibrary")) {
                Log.e(TAG, "Uncaught exception in ScanActivity or scanlibrary", exception)
                Log.e(TAG, "Exception message: ${exception.message}")
                Log.e(TAG, "Exception stack trace: ${exception.stackTraceToString()}")
            }
            // Call the default handler
            defaultHandler?.uncaughtException(thread, exception)
        }
        
        Log.d(TAG, "onAttachedToActivity: Activity result listener added, activity = ${binding.activity?.javaClass?.name}")
    }
    
    private fun verifyAllPermissionsGranted(activity: Activity): Boolean {
        // Check camera permission
        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "verifyAllPermissionsGranted: CAMERA permission not granted")
            return false
        }
        
        // Check storage permissions based on API level
        // For Android 13+ (API 33+), we use system photo picker which doesn't require READ_MEDIA_IMAGES
        if (Build.VERSION.SDK_INT < 33) {
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "verifyAllPermissionsGranted: READ_EXTERNAL_STORAGE permission not granted")
                return false
            }
            // WRITE_EXTERNAL_STORAGE is also needed for older Android versions
            if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "verifyAllPermissionsGranted: WRITE_EXTERNAL_STORAGE permission not granted")
                return false
            }
        }
        // Android 13+ uses system photo picker - no READ_MEDIA_IMAGES permission needed
        
        Log.d(TAG, "verifyAllPermissionsGranted: All permissions verified and granted")
        return true
    }

    private fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            Log.d(TAG, "onRequestPermissionsResult: Permission request completed")
            Log.d(TAG, "onRequestPermissionsResult: Permissions requested = ${permissions.joinToString()}")
            Log.d(TAG, "onRequestPermissionsResult: Grant results = ${grantResults.joinToString()}")
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Log.d(TAG, "onRequestPermissionsResult: All permissions granted, verifying before launch")
                val intent = pendingIntent
                val reqCode = pendingRequestCode
                if (intent != null && reqCode != -1) {
                    val activity = activityPluginBinding?.activity
                    if (activity != null) {
                        // Double-check permissions are actually granted before launching
                        if (!verifyAllPermissionsGranted(activity)) {
                            Log.e(TAG, "onRequestPermissionsResult: Permissions verification failed, cannot launch")
                            pendingIntent = null
                            pendingRequestCode = -1
                            result?.error("PERMISSION_VERIFICATION_FAILED", "Permissions were not properly granted", null)
                            return
                        }
                        
                        // Small delay to ensure permission state is fully committed
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            pendingIntent = null
                            pendingRequestCode = -1
                            
                            // Verify one more time after delay
                            if (!verifyAllPermissionsGranted(activity)) {
                                Log.e(TAG, "onRequestPermissionsResult: Permissions verification failed after delay")
                                result?.error("PERMISSION_VERIFICATION_FAILED", "Permissions were not properly granted", null)
                                return@postDelayed
                            }
                            
                            Log.d(TAG, "onRequestPermissionsResult: About to launch activity = ${intent.component}")
                            Log.d(TAG, "onRequestPermissionsResult: Intent extras = ${intent.extras?.keySet()}")
                            try {
                                activity.startActivityForResult(intent, reqCode)
                                Log.d(TAG, "onRequestPermissionsResult: Activity launched successfully after permissions granted")
                                
                                // Check activity state after launch
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    Log.d(TAG, "onRequestPermissionsResult: Checking activity state 1 second after launch")
                                    val currentActivity = activityPluginBinding?.activity
                                    if (currentActivity != null) {
                                        Log.d(TAG, "onRequestPermissionsResult: Current activity = ${currentActivity.javaClass.name}")
                                        Log.d(TAG, "onRequestPermissionsResult: isFinishing = ${currentActivity.isFinishing}")
                                        Log.d(TAG, "onRequestPermissionsResult: isDestroyed = ${currentActivity.isDestroyed}")
                                    }
                                }, 1000)
                            } catch (e: Exception) {
                                Log.e(TAG, "onRequestPermissionsResult: ERROR launching activity after permissions", e)
                                result?.error("LAUNCH_FAILED", "Failed to launch activity after permissions: ${e.message}", e.stackTraceToString())
                            }
                        }, 300) // 300ms delay to ensure permissions are fully committed
                    } else {
                        Log.e(TAG, "onRequestPermissionsResult: Activity is null, cannot launch")
                        pendingIntent = null
                        pendingRequestCode = -1
                        result?.error("ACTIVITY_NULL", "Activity is null after permission grant", null)
                    }
                } else {
                    Log.e(TAG, "onRequestPermissionsResult: No pending intent stored!")
                }
            } else {
                Log.e(TAG, "onRequestPermissionsResult: Permissions denied")
                pendingIntent = null
                pendingRequestCode = -1
                result?.error("PERMISSION_DENIED", "Required permissions were denied", null)
            }
        }
    }

    override fun onDetachedFromActivity() {
        activityPluginBinding?.removeActivityResultListener(this)
        activityPluginBinding = null
    }

    private fun composeIntentArguments(intent:Intent) {
        // Default values for library - these are required by the library
        val defaultValues = mapOf(
            ScanConstants.SCAN_NEXT_TEXT to "Next",
            ScanConstants.SCAN_SAVE_TEXT to "Save",
            ScanConstants.SCAN_ROTATE_LEFT_TEXT to "Rotate Left",
            ScanConstants.SCAN_ROTATE_RIGHT_TEXT to "Rotate Right",
            ScanConstants.SCAN_ORG_TEXT to "Original",
            ScanConstants.SCAN_BNW_TEXT to "B&W",
            ScanConstants.SCAN_SCANNING_MESSAGE to "Scanning...",
            ScanConstants.SCAN_LOADING_MESSAGE to "Loading...",
            ScanConstants.SCAN_APPLYING_FILTER_MESSAGE to "Applying filter...",
            ScanConstants.SCAN_CANT_CROP_ERROR_TITLE to "Error",
            ScanConstants.SCAN_CANT_CROP_ERROR_MESSAGE to "Cannot crop image",
            ScanConstants.SCAN_OK_LABEL to "OK"
        )
        
        // Apply defaults first
        defaultValues.forEach { (key, value) ->
            intent.putExtra(key, value)
            Log.d(TAG, "composeIntentArguments: Added default extra[$key] = $value")
        }
        
        // Override with custom values from Flutter if provided
        mapOf(
            ScanConstants.SCAN_NEXT_TEXT to "ANDROID_NEXT_BUTTON_LABEL",
            ScanConstants.SCAN_SAVE_TEXT to "ANDROID_SAVE_BUTTON_LABEL",
            ScanConstants.SCAN_ROTATE_LEFT_TEXT to "ANDROID_ROTATE_LEFT_LABEL",
            ScanConstants.SCAN_ROTATE_RIGHT_TEXT to "ANDROID_ROTATE_RIGHT_LABEL",
            ScanConstants.SCAN_ORG_TEXT to "ANDROID_ORIGINAL_LABEL",
            ScanConstants.SCAN_BNW_TEXT to "ANDROID_BMW_LABEL",
            ScanConstants.SCAN_SCANNING_MESSAGE to "ANDROID_SCANNING_MESSAGE",
            ScanConstants.SCAN_LOADING_MESSAGE to "ANDROID_LOADING_MESSAGE",
            ScanConstants.SCAN_APPLYING_FILTER_MESSAGE to "ANDROID_APPLYING_FILTER_MESSAGE",
            ScanConstants.SCAN_CANT_CROP_ERROR_TITLE to "ANDROID_CANT_CROP_ERROR_TITLE",
            ScanConstants.SCAN_CANT_CROP_ERROR_MESSAGE to "ANDROID_CANT_CROP_ERROR_MESSAGE",
            ScanConstants.SCAN_OK_LABEL to "ANDROID_OK_LABEL"
        ).entries.filter { call.hasArgument(it.value) && call.argument<String>(it.value) != null }.forEach {
            intent.putExtra(it.key, call.argument<String>(it.value))
            Log.d(TAG, "composeIntentArguments: Overrode with custom extra[${it.key}] = ${call.argument<String>(it.value)}")
        }
    }

    private fun checkAndRequestPermissions(activity: Activity, intent: Intent, requestCode: Int, callback: () -> Unit) {
        val permissions = mutableListOf<String>()
        
        // Always need camera permission
        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.CAMERA)
            Log.d(TAG, "checkAndRequestPermissions: CAMERA permission not granted")
        } else {
            Log.d(TAG, "checkAndRequestPermissions: CAMERA permission already granted")
        }
        
        // Storage permissions - use API level check with integer (33 = Android 13)
        // For Android 13+, we use system photo picker which doesn't require READ_MEDIA_IMAGES
        if (Build.VERSION.SDK_INT < 33) {
            // Android < 13 needs READ_EXTERNAL_STORAGE and WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                Log.d(TAG, "checkAndRequestPermissions: READ_EXTERNAL_STORAGE permission not granted")
            } else {
                Log.d(TAG, "checkAndRequestPermissions: READ_EXTERNAL_STORAGE permission already granted")
            }
            // WRITE_EXTERNAL_STORAGE is also needed for older Android versions
            if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                Log.d(TAG, "checkAndRequestPermissions: WRITE_EXTERNAL_STORAGE permission not granted")
            } else {
                Log.d(TAG, "checkAndRequestPermissions: WRITE_EXTERNAL_STORAGE permission already granted or not needed")
            }
        }
        // Android 13+ uses system photo picker - no READ_MEDIA_IMAGES permission needed
        
        if (permissions.isEmpty()) {
            Log.d(TAG, "checkAndRequestPermissions: All permissions granted, proceeding with activity launch")
            callback()
        } else {
            Log.d(TAG, "checkAndRequestPermissions: Requesting ${permissions.size} permissions - storing intent for later")
            // Store the intent to launch after permissions are granted
            pendingIntent = intent
            pendingRequestCode = requestCode
            ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
            // Don't call callback yet - wait for permission result
        }
    }

    private fun camera() {
        Log.d(TAG, "camera: Starting camera method")
        if (activityPluginBinding == null) {
            Log.e(TAG, "camera: ERROR - activityPluginBinding is null!")
            result?.error("ACTIVITY_NOT_ATTACHED", "Activity is not attached", null)
            return
        }
        
        val activity = activityPluginBinding?.activity
        if (activity == null) {
            Log.e(TAG, "camera: ERROR - activity is null!")
            result?.error("ACTIVITY_NULL", "Activity is null", null)
            return
        }
        
        Log.d(TAG, "camera: Activity found = ${activity.javaClass.name}")
        
        try {
            val intent = Intent(activity, ScanActivity::class.java)
            Log.d(TAG, "camera: Intent created for ScanActivity = ${ScanActivity::class.java.name}")
            
            // Check if activity can be resolved
            val resolveInfo = activity.packageManager.resolveActivity(intent, 0)
            if (resolveInfo == null) {
                Log.e(TAG, "camera: ERROR - ScanActivity cannot be resolved!")
                result?.error("ACTIVITY_NOT_FOUND", "ScanActivity not found. Check if scan library is properly included.", null)
                return
            }
            Log.d(TAG, "camera: Activity resolved = ${resolveInfo.activityInfo.name}")
            
            intent.putExtra(ScanConstants.OPEN_INTENT_PREFERENCE, ScanConstants.OPEN_CAMERA)
            Log.d(TAG, "camera: Added OPEN_CAMERA preference = ${ScanConstants.OPEN_CAMERA}")
            Log.d(TAG, "camera: OPEN_CAMERA constant value = ${ScanConstants.OPEN_CAMERA}")
            
            composeIntentArguments(intent)
            Log.d(TAG, "camera: Intent arguments composed")
            
            // Log all intent extras for debugging
            val extras = intent.extras
            if (extras != null) {
                Log.d(TAG, "camera: Intent extras keys = ${extras.keySet()}")
                for (key in extras.keySet()) {
                    Log.d(TAG, "camera: Intent extra[$key] = ${extras.get(key)}")
                }
            } else {
                Log.w(TAG, "camera: Intent extras is null!")
            }
            
            Log.d(TAG, "camera: Intent details - Component=${intent.component}, Action=${intent.action}")
            
            // Check and request permissions BEFORE launching activity
            checkAndRequestPermissions(activity, intent, SCAN_REQUEST_CODE) {
                try {
                    Log.d(TAG, "camera: Starting activity with request code = $SCAN_REQUEST_CODE")
                    Log.d(TAG, "camera: Activity package name = ${activity.packageName}")
                    Log.d(TAG, "camera: Intent component = ${intent.component}")
                    Log.d(TAG, "camera: Intent package = ${intent.`package`}")
                    Log.d(TAG, "camera: Activity theme = ${activity.theme}")
                    Log.d(TAG, "camera: Verifying permissions one final time before launch")
                    
                    // Final permission check before launch
                    if (!verifyAllPermissionsGranted(activity)) {
                        Log.e(TAG, "camera: Final permission check failed, cannot launch")
                        result?.error("PERMISSION_CHECK_FAILED", "Permissions not granted", null)
                        return@checkAndRequestPermissions
                    }
                    
                    Log.d(TAG, "camera: All permissions verified, launching ScanActivity")
                    Log.d(TAG, "camera: About to call startActivityForResult with intent=$intent")
                    
                    try {
                        activity.startActivityForResult(intent, SCAN_REQUEST_CODE)
                        Log.d(TAG, "camera: Activity started successfully - startActivityForResult returned")
                    } catch (e: Exception) {
                        Log.e(TAG, "camera: EXCEPTION during startActivityForResult", e)
                        result?.error("START_ACTIVITY_EXCEPTION", "Exception starting activity: ${e.message}", e.stackTraceToString())
                        return@checkAndRequestPermissions
                    }
                    
                    // Log immediately after launch
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "camera: Checking activity state immediately after launch (100ms)")
                        val currentActivity = activityPluginBinding?.activity
                        if (currentActivity != null) {
                            Log.d(TAG, "camera: Current activity = ${currentActivity.javaClass.name}")
                            Log.d(TAG, "camera: Current activity isFinishing = ${currentActivity.isFinishing}")
                            Log.d(TAG, "camera: Current activity isDestroyed = ${currentActivity.isDestroyed}")
                        } else {
                            Log.e(TAG, "camera: Current activity is null after 100ms!")
                        }
                    }, 100)
                    
                    // Log after delays to check activity state and detect crashes
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "camera: Checking activity state after 500ms")
                        val currentActivity = activityPluginBinding?.activity
                        if (currentActivity != null) {
                            Log.d(TAG, "camera: Current activity = ${currentActivity.javaClass.name}")
                            Log.d(TAG, "camera: Current activity isFinishing = ${currentActivity.isFinishing}")
                            Log.d(TAG, "camera: Current activity isDestroyed = ${currentActivity.isDestroyed}")
                        } else {
                            Log.e(TAG, "camera: Current activity is null after 500ms!")
                        }
                    }, 500)
                    
                    // Check again after 2 seconds to see if ScanActivity crashed
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "camera: Checking activity state after 2000ms")
                        val currentActivity = activityPluginBinding?.activity
                        if (currentActivity != null) {
                            val activityName = currentActivity.javaClass.name
                            Log.d(TAG, "camera: Current activity after 2s = $activityName")
                            if (activityName.contains("ScanActivity")) {
                                Log.d(TAG, "camera: ScanActivity is still active")
                            } else {
                                Log.w(TAG, "camera: ScanActivity may have finished/crashed, back to $activityName")
                            }
                        }
                    }, 2000)
                } catch (e: android.content.ActivityNotFoundException) {
                    Log.e(TAG, "camera: ERROR - ActivityNotFoundException", e)
                    result?.error("ACTIVITY_NOT_FOUND", "ScanActivity not found: ${e.message}", e.stackTraceToString())
                } catch (e: SecurityException) {
                    Log.e(TAG, "camera: ERROR - SecurityException (permissions?)", e)
                    result?.error("SECURITY_ERROR", "Security error starting activity (check permissions): ${e.message}", e.stackTraceToString())
                } catch (e: Exception) {
                    Log.e(TAG, "camera: ERROR starting activity", e)
                    result?.error("ACTIVITY_START_FAILED", "Failed to start camera activity: ${e.message}", e.stackTraceToString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "camera: ERROR in camera method", e)
            result?.error("ERROR", "Error in camera method: ${e.message}", e.stackTraceToString())
        }
    }

    private fun gallery() {
        Log.d(TAG, "gallery: Starting gallery method")
        if (activityPluginBinding == null) {
            Log.e(TAG, "gallery: ERROR - activityPluginBinding is null!")
            result?.error("ACTIVITY_NOT_ATTACHED", "Activity is not attached", null)
            return
        }
        
        val activity = activityPluginBinding?.activity
        if (activity == null) {
            Log.e(TAG, "gallery: ERROR - activity is null!")
            result?.error("ACTIVITY_NULL", "Activity is null", null)
            return
        }
        
        Log.d(TAG, "gallery: Activity found = ${activity.javaClass.name}")
        
        // For Android 13+ (API 33+), use system photo picker (no permissions needed)
        if (Build.VERSION.SDK_INT >= 33) {
            Log.d(TAG, "gallery: Android 13+ detected, using system photo picker")
            try {
                val photoPickerIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                
                val chooserIntent = Intent.createChooser(photoPickerIntent, "Select Image")
                activity.startActivityForResult(chooserIntent, PHOTO_PICKER_REQUEST_CODE)
                Log.d(TAG, "gallery: System photo picker launched")
            } catch (e: Exception) {
                Log.e(TAG, "gallery: ERROR launching photo picker", e)
                result?.error("PHOTO_PICKER_ERROR", "Failed to launch photo picker: ${e.message}", e.stackTraceToString())
            }
            return
        }
        
        // For Android < 13, use existing flow with permissions
        Log.d(TAG, "gallery: Android < 13, using existing flow with permissions")
        try {
            val intent = Intent(activity, ScanActivity::class.java)
            Log.d(TAG, "gallery: Intent created for ScanActivity = ${ScanActivity::class.java.name}")
            
            // Check if activity can be resolved
            val resolveInfo = activity.packageManager.resolveActivity(intent, 0)
            if (resolveInfo == null) {
                Log.e(TAG, "gallery: ERROR - ScanActivity cannot be resolved!")
                result?.error("ACTIVITY_NOT_FOUND", "ScanActivity not found. Check if scan library is properly included.", null)
                return
            }
            Log.d(TAG, "gallery: Activity resolved = ${resolveInfo.activityInfo.name}")
            
            intent.putExtra(ScanConstants.OPEN_INTENT_PREFERENCE, ScanConstants.OPEN_MEDIA)
            Log.d(TAG, "gallery: Added OPEN_MEDIA preference = ${ScanConstants.OPEN_MEDIA}")
            
            composeIntentArguments(intent)
            Log.d(TAG, "gallery: Intent arguments composed")
            
            // Check and request permissions BEFORE launching activity
            checkAndRequestPermissions(activity, intent, SCAN_REQUEST_CODE) {
                try {
                    Log.d(TAG, "gallery: Starting activity with request code = $SCAN_REQUEST_CODE")
                    
                    // Final permission check before launch
                    if (!verifyAllPermissionsGranted(activity)) {
                        Log.e(TAG, "gallery: Final permission check failed, cannot launch")
                        result?.error("PERMISSION_CHECK_FAILED", "Permissions not granted", null)
                        return@checkAndRequestPermissions
                    }
                    
                    Log.d(TAG, "gallery: All permissions verified, launching ScanActivity")
                    activity.startActivityForResult(intent, SCAN_REQUEST_CODE)
                    Log.d(TAG, "gallery: Activity started successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "gallery: ERROR starting activity", e)
                    result?.error("ACTIVITY_START_FAILED", "Failed to start gallery activity: ${e.message}", e.stackTraceToString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "gallery: ERROR in gallery method", e)
            result?.error("ERROR", "Error in gallery method: ${e.message}", e.stackTraceToString())
        }
    }
    
    private fun copyImageToInternalStorage(activity: Activity, contentUri: Uri): String? {
        return try {
            val inputStream = activity.contentResolver.openInputStream(contentUri)
            val internalDir = activity.filesDir
            val imageFile = File(internalDir, "scanner_temp_${System.currentTimeMillis()}.jpg")
            
            inputStream?.use { input ->
                imageFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d(TAG, "copyImageToInternalStorage: Image copied to ${imageFile.absolutePath}")
            imageFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "copyImageToInternalStorage: ERROR copying image", e)
            null
        }
    }
    
    private fun launchScanActivityWithImage(activity: Activity, imagePath: String) {
        try {
            val intent = Intent(activity, ScanActivity::class.java)
            intent.putExtra(ScanConstants.OPEN_INTENT_PREFERENCE, ScanConstants.OPEN_MEDIA)
            
            // Create a content URI using FileProvider for the copied image
            // Use the library's FileProvider (authority: "com.scanlibrary.provider")
            // Note: ScanActivity may not support pre-selected images - this is experimental
            // If this doesn't work, we may need to use a different approach or contact library maintainer
            val imageFile = File(imagePath)
            val imageUri = try {
                FileProvider.getUriForFile(
                    activity,
                    "com.scanlibrary.provider",
                    imageFile
                )
            } catch (e: Exception) {
                Log.w(TAG, "launchScanActivityWithImage: FileProvider error, trying alternative approach", e)
                // Fallback - try content URI or direct file path
                // Note: This may not work on all Android versions
                if (Build.VERSION.SDK_INT >= 24) {
                    // Android 7.0+ requires FileProvider
                    result?.error("FILE_PROVIDER_ERROR", "Failed to create file URI: ${e.message}", null)
                    return
                } else {
                    Uri.fromFile(imageFile)
                }
            }
            
            // Try to pass the image URI - this may or may not work depending on ScanActivity implementation
            intent.putExtra(ScanConstants.SCANNED_RESULT, imageUri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            
            composeIntentArguments(intent)
            
            // Only need camera permission for scanning
            if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                pendingIntent = intent
                pendingRequestCode = SCAN_REQUEST_CODE
                ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE)
                return
            }
            
            activity.startActivityForResult(intent, SCAN_REQUEST_CODE)
            Log.d(TAG, "launchScanActivityWithImage: ScanActivity launched with image URI = $imageUri")
        } catch (e: Exception) {
            Log.e(TAG, "launchScanActivityWithImage: ERROR", e)
            result?.error("SCAN_ACTIVITY_ERROR", "Failed to launch ScanActivity: ${e.message}", e.stackTraceToString())
        }
    }

    fun getRealPathFromUri(context: Context, contentUri: Uri?): String? {
        var cursor: Cursor? = null
        return try {
            val proj = arrayOf(MediaStore.Images.Media.DATA)
            cursor = context.getContentResolver().query(contentUri!!, proj, null, null, null)
            val column_index: Int = cursor!!.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            cursor.getString(column_index)
        } finally {
            if (cursor != null) {
                cursor.close()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        Log.d(TAG, "onActivityResult: ========== CALLED ==========")
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        Log.d(TAG, "onActivityResult: data=$data")
        Log.d(TAG, "onActivityResult: SCAN_REQUEST_CODE=$SCAN_REQUEST_CODE, PHOTO_PICKER_REQUEST_CODE=$PHOTO_PICKER_REQUEST_CODE")
        Log.d(TAG, "onActivityResult: RESULT_OK=${Activity.RESULT_OK}, RESULT_CANCELED=${Activity.RESULT_CANCELED}")
        
        // Handle photo picker result (Android 13+)
        if (requestCode == PHOTO_PICKER_REQUEST_CODE) {
            return when (resultCode) {
                Activity.RESULT_OK -> {
                    Log.d(TAG, "onActivityResult: Photo picker returned OK")
                    val activity = activityPluginBinding?.activity
                    if (activity == null) {
                        Log.e(TAG, "onActivityResult: ERROR - activity is null!")
                        result?.error("ACTIVITY_NULL", "Activity is null when processing photo picker result", null)
                        return true
                    }
                    
                    val selectedImageUri = data?.data
                    if (selectedImageUri == null) {
                        Log.e(TAG, "onActivityResult: ERROR - no image URI from photo picker")
                        result?.error("NO_IMAGE_URI", "No image selected from photo picker", null)
                        return true
                    }
                    
                    Log.d(TAG, "onActivityResult: Selected image URI = $selectedImageUri")
                    
                    // Copy image to internal storage
                    val imagePath = copyImageToInternalStorage(activity, selectedImageUri)
                    if (imagePath == null) {
                        Log.e(TAG, "onActivityResult: ERROR - failed to copy image to internal storage")
                        result?.error("IMAGE_COPY_FAILED", "Failed to copy selected image", null)
                        return true
                    }
                    
                    // Launch ScanActivity with the copied image
                    launchScanActivityWithImage(activity, imagePath)
                    true
                }
                Activity.RESULT_CANCELED -> {
                    Log.d(TAG, "onActivityResult: Photo picker canceled by user")
                    result?.error("USER_CANCELED", "User canceled photo selection", null)
                    true
                }
                else -> {
                    Log.w(TAG, "onActivityResult: Photo picker returned unknown result code = $resultCode")
                    result?.error("UNKNOWN_RESULT", "Photo picker returned unknown result code: $resultCode", null)
                    true
                }
            }
        }
        
        // Handle ScanActivity result
        if (requestCode == SCAN_REQUEST_CODE) {
            Log.d(TAG, "onActivityResult: Request code matches SCAN_REQUEST_CODE")
            return when (resultCode) {
                Activity.RESULT_OK -> {
                    Log.d(TAG, "onActivityResult: Result is OK - processing scan result")
                    try {
                        val activity = activityPluginBinding?.activity
                        if (activity == null) {
                            Log.e(TAG, "onActivityResult: ERROR - activity is null!")
                            result?.error("ACTIVITY_NULL", "Activity is null when processing result", null)
                            return true
                        }
                        
                        if (data == null) {
                            Log.e(TAG, "onActivityResult: ERROR - data Intent is null!")
                            result?.error("DATA_NULL", "Result data is null", null)
                            return true
                        }
                        
                        if (data.extras == null) {
                            Log.e(TAG, "onActivityResult: ERROR - data.extras is null!")
                            result?.error("EXTRAS_NULL", "Result extras is null", null)
                            return true
                        }
                        
                        val uri = data.extras!!.getParcelable<Uri>(ScanConstants.SCANNED_RESULT)
                        Log.d(TAG, "onActivityResult: URI extracted = $uri")
                        
                        if (uri == null) {
                            Log.e(TAG, "onActivityResult: ERROR - URI is null!")
                            result?.error("URI_NULL", "Scanned result URI is null", null)
                            return true
                        }
                        
                        val path = getRealPathFromUri(activity, uri)
                        Log.d(TAG, "onActivityResult: Real path = $path")
                        result?.success(path)
                        Log.d(TAG, "onActivityResult: Success result sent to Flutter")
                    } catch (e: Exception) {
                        Log.e(TAG, "onActivityResult: ERROR processing result", e)
                        result?.error("RESULT_PROCESSING_FAILED", "Failed to process result: ${e.message}", e.stackTraceToString())
                    }
                    true
                }
                Activity.RESULT_CANCELED -> {
                    Log.d(TAG, "onActivityResult: Result is CANCELED - user likely pressed back or canceled")
                    Log.d(TAG, "onActivityResult: CANCELED - This means ScanActivity finished with RESULT_CANCELED")
                    result?.error("USER_CANCELED", "User canceled the scan", null)
                    true
                }
                else -> {
                    Log.w(TAG, "onActivityResult: Unknown result code = $resultCode (expected ${Activity.RESULT_OK} or ${Activity.RESULT_CANCELED})")
                    // Still return true to indicate we handled it, but log the unknown code
                    result?.error("UNKNOWN_RESULT", "Activity returned unknown result code: $resultCode", null)
                    true
                }
            }
        } else {
            Log.d(TAG, "onActivityResult: Request code does not match, ignoring (requestCode=$requestCode, expected=$SCAN_REQUEST_CODE)")
            return false
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}


}
