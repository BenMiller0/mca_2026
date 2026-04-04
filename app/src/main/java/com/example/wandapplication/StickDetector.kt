package com.example.wandapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerOptions
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.sqrt

class StickDetector(private val context: Context) {
    
    private var handLandmarker: HandLandmarker? = null
    private var objectDetector: ObjectDetector? = null
    
    data class StickDetectionResult(
        val isStickDetected: Boolean,
        val confidence: Float,
        val handBoundingBox: RectF? = null,
        val stickBoundingBox: RectF? = null,
        val message: String = ""
    )
    
    init {
        setupDetectors()
    }
    
    private fun setupDetectors() {
        try {
            // Setup Hand Landmarker
            val handLandmarkerOptions = HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder()
                    .setModelAssetPath("hand_landmarker.task")
                    .build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .build()
            
            handLandmarker = HandLandmarker.createFromOptions(context, handLandmarkerOptions)
            
            // Setup Object Detector for stick-like objects
            val objectDetectorOptions = ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder()
                    .setModelAssetPath("efficientdet_lite0.tflite")
                    .build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(10)
                .setScoreThreshold(0.3f)
                .build()
            
            objectDetector = ObjectDetector.createFromOptions(context, objectDetectorOptions)
            
            Log.d(TAG, "Detectors initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize detectors", e)
        }
    }
    
    fun detectStick(bitmap: Bitmap): StickDetectionResult {
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            
            // Detect hands
            val handResult = handLandmarker?.detect(mpImage)
            val objectResult = objectDetector?.detect(mpImage)
            
            return analyzeResults(handResult, objectResult, bitmap.width, bitmap.height)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during stick detection", e)
            return StickDetectionResult(false, 0f, message = "Detection error: ${e.message}")
        }
    }
    
    private fun analyzeResults(
        handResult: HandLandmarkerResult?,
        objectResult: ObjectDetectorResult?,
        imageWidth: Int,
        imageHeight: Int
    ): StickDetectionResult {
        
        // Check if hands are detected
        val hands = handResult?.landmarks() ?: emptyList()
        if (hands.isEmpty()) {
            return StickDetectionResult(false, 0f, message = "No hands detected")
        }
        
        // Get hand bounding boxes
        val handBoxes = hands.map { landmarks ->
            createBoundingBox(landmarks, imageWidth, imageHeight)
        }
        
        // Look for stick-like objects near hands
        val detections = objectResult?.detections() ?: emptyList()
        
        for (detection in detections) {
            val categories = detection.categories()
            if (categories.isNotEmpty()) {
                val category = categories[0]
                val confidence = category.score()
                
                // Check for stick-like objects or objects with high aspect ratio
                val boundingBox = detection.boundingBox()
                val aspectRatio = boundingBox.width().toFloat() / boundingBox.height().toFloat()
                
                val isStickLike = isStickLikeObject(category.categoryName(), aspectRatio, confidence)
                
                if (isStickLike) {
                    // Check if this object is near any hand
                    val stickRect = RectF(
                        boundingBox.left(),
                        boundingBox.top(),
                        boundingBox.right(),
                        boundingBox.bottom()
                    )
                    
                    val nearHand = handBoxes.any { handBox ->
                        isNearHand(handBox, stickRect)
                    }
                    
                    if (nearHand) {
                        return StickDetectionResult(
                            true,
                            confidence,
                            handBoxes.firstOrNull(),
                            stickRect,
                            "Stick detected near hand (${(confidence * 100).toInt()}%)"
                        )
                    }
                }
            }
        }
        
        // Alternative: Check for extended hand position (like holding something)
        val extendedHand = detectExtendedHand(hands)
        if (extendedHand) {
            return StickDetectionResult(
                true,
                0.6f,
                handBoxes.firstOrNull(),
                null,
                "Extended hand detected - possible wand"
            )
        }
        
        return StickDetectionResult(false, 0f, message = "No stick detected")
    }
    
    private fun isStickLikeObject(categoryName: String, aspectRatio: Float, confidence: Float): Boolean {
        // Check for common stick-like objects
        val stickCategories = listOf(
            "person", "bottle", "cup", "cell phone", "remote", "knife", "spoon", "fork"
        )
        
        val isStickCategory = stickCategories.any { category ->
            categoryName.lowercase().contains(category)
        }
        
        // Check aspect ratio (sticks are typically tall and thin)
        val isStickShape = aspectRatio < 0.5f || aspectRatio > 2.0f
        
        return (isStickCategory && confidence > 0.4f) || (isStickShape && confidence > 0.3f)
    }
    
    private fun createBoundingBox(
        landmarks: List<com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarksProto.NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        
        landmarks.forEach { landmark ->
            val x = landmark.x() * imageWidth
            val y = landmark.y() * imageHeight
            
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
        
        return RectF(minX, minY, maxX, maxY)
    }
    
    private fun isNearHand(handBox: RectF, stickBox: RectF): Boolean {
        // Expand hand box slightly and check for overlap
        val expandedHandBox = RectF(
            handBox.left - 50,
            handBox.top - 50,
            handBox.right + 50,
            handBox.bottom + 50
        )
        
        return RectF.intersects(expandedHandBox, stickBox)
    }
    
    private fun detectExtendedHand(landmarksList: List<List<com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarksProto.NormalizedLandmark>>): Boolean {
        for (landmarks in landmarksList) {
            if (landmarks.size >= 21) { // Hand landmarks have 21 points
                // Check if fingers are extended (simplified check)
                val wrist = landmarks[0]
                val middleTip = landmarks[12]
                
                // Calculate distance between wrist and middle finger tip
                val distance = sqrt(
                    (middleTip.x() - wrist.x()).pow(2) +
                    (middleTip.y() - wrist.y()).pow(2)
                )
                
                // If distance is large, hand might be extended holding something
                if (distance > 0.3f) {
                    return true
                }
            }
        }
        return false
    }
    
    fun close() {
        handLandmarker?.close()
        objectDetector?.close()
    }
    
    companion object {
        private const val TAG = "StickDetector"
    }
}
