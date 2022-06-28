@file:Suppress("DEPRECATION")

package com.smart.myapplication

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.vr.sdk.widgets.video.VrVideoView
import org.json.JSONArray
import org.json.JSONObject


class AnnotationsParser(json: String) : JSONObject(json) {

    val data = this.optJSONArray("data")
        ?.let { 0.until(it.length()).map { i -> it.optJSONObject(i) } } // returns an array of JSONObject
        ?.map { Annotation(it.toString()) } // transforms each JSONObject of the array into an annotation
}
class LocationsParser(json: String) : JSONObject(json) {

    val data = this.optJSONArray("data")
        .let { 0.until(it.length()).map { i -> it.optJSONObject(i) } } // returns an array of JSONObject
        .map { Location(it.toString()) } // transforms each JSONObject of the array into an annotation
}
class Location (json: String) : JSONObject(json){
    val locationName: String = this.optString("name")

    val timestamp: Long = this.optInt("timestamp").toLong()
    val annotations = this.optJSONArray("annotations")
        .let { 0.until(it.length()).map { i -> it.optJSONObject(i) } } // returns an array of JSONObject
        .map { Annotation(it.toString()) } // transforms each JSONObject of the array into an annotation
}
class Annotation (json: String) : JSONObject(json) {
    val hitboxRect : IntArray = JSONArrayToIntArray(this.optJSONArray("hitboxRect"))// [Int , Int , Int , Int] -> the coordinates (Spherical) of the two corners of the box
    val type: String = this.optString("type") // enum ("text","voice","video","XMLbox","teleport" )
    val resourceName: String = this.optString("URI")
    val hitboxName: String = this.optString("name")
}

fun JSONArrayToIntArray(jsonArray: JSONArray): IntArray {
    Log.i("oncreate - > ", "JSONArrayToIntArray: " + jsonArray)
    val intArray = IntArray(jsonArray.length())
    for (i in intArray.indices) {
        intArray[i] = jsonArray.optInt(i)
    }
    return intArray
}

class DomeActivity : AppCompatActivity(), SensorEventListener {

    private var view: ConstraintLayout? = null
    var debugOn = false
    var isStanding : Boolean = false
    private  var VRView: VrVideoView? = null
    private lateinit var  crossHair : ImageView
    private lateinit var crosshairProgress : ProgressBar
    private lateinit var mSensorManager : SensorManager
    private var mAccelerometer : Sensor ?= null
    private var resume = true
    private var soundPlaying : Boolean = false
    private lateinit var continueButton : Button
    private  var mediaPlayer : MediaPlayer? = null
    private var gazeThreshold = 500
    private lateinit var domeRoot : ConstraintLayout
    private val experienceName = "prague"
    private var gazeTime : Int = 0
    private lateinit var locations : LocationsParser
    private var AnnotationGL = false
    private var lookingAt : Annotation? = null
    private var currentRect : IntArray = IntArray(4,{_ -> 0})
    private var locationIndex: Int = 0
    private var yawAndPitch : FloatArray = FloatArray(2) { 0f }
    private var debugText : TextView ?= null
    private var s : Int = 0
    private var onLookAway = {
        false
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        // for warm and hot load
        super.onCreate(savedInstanceState)

        //hide title and action bar
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        this.window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar!!.hide()
        hideSystemBars()

        val file_name = "$experienceName.json"
        val jsonString = application.assets.open(file_name).bufferedReader().use{
            it.readText()
        }
        locations = LocationsParser(jsonString)
        // this here is what REALLY gets the app running
        setContentView(R.layout.activity_dome)
        domeRoot = findViewById<ConstraintLayout>(R.id.domeRoot)

        //load vr

        crossHair = findViewById(R.id.crosshair)
        VRView = findViewById(R.id.dome360)
        VRView!!.loadVideoFromAsset("$experienceName.mp4",VrVideoView.Options())
        VRView!!.setVolume(0f)
        VRView!!.setFullscreenButtonEnabled(false)
        VRView!!.setInfoButtonEnabled(false)
        VRView!!.setStereoModeButtonEnabled(false)
        VRView!!.setTransitionViewEnabled(false)
        VRView!!.setTouchTrackingEnabled(false)
        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        debugText = findViewById(R.id.sensorDebug)
        crosshairProgress = findViewById(R.id.progressBar)
        crosshairProgress.progress = 0
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        crossHair.setImageResource(com.google.android.material.R.drawable.ic_m3_chip_close)
        /* VRView!!.setOnTouchListener { i, _ ->
            isStanding = !isStanding
            if (isStanding){
                VRView!!.pauseVideo()
                continueButton.visibility = View.VISIBLE
            }else{
                    VRView!!.playVideo()
                continueButton.visibility = View.GONE
        }

            false} */
        debugText!!.text = ">>"
        debugText!!.setOnClickListener {
            debugOn = !debugOn
            if (!debugOn){
                debugText!!.text = ">>"
            }
        }

        continueButton = findViewById(R.id.continueButton)
        continueButton.setOnClickListener {
            if (locationIndex == locations.length()){
                val intent = Intent(this, TheEndActivity::class.java)
                startActivity(intent)
            }else {
                locationIndex+=1
                if(soundPlaying){
                    mediaPlayer!!.stop()
                    mediaPlayer!!.release()
                }
                onLookAway()
                lookingAt = null
                crosshairProgress.progress = 0
                crossHair.setImageResource(com.google.android.material.R.drawable.ic_m3_chip_close)
                continueButton.visibility = View.GONE
                VRView!!.playVideo()
                gazeTime = 0
                isStanding = false
                AnnotationGL = false
            }

        }
    }


    override  fun onSensorChanged(event: SensorEvent?) {

        if (debugOn){
            renderDebug()
        }
        VRView!!.getHeadRotation(yawAndPitch)
        val yaw = yawAndPitch[0]
        val pitch = yawAndPitch[1]
        if (event != null && resume && !AnnotationGL) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {

                VRView!!.getHeadRotation(yawAndPitch)



                 if (isStanding){
                    val iter = locations.data[locationIndex].annotations.iterator()
                    while (iter.hasNext()){
                        val n = iter.next()

                        if (yaw > n.hitboxRect[0] &&
                            pitch < n.hitboxRect[1] &&
                            yaw < n.hitboxRect[2] &&
                            pitch > n.hitboxRect[3]){

                            if (lookingAt == n){
                                gazeTime+= 1
                                updateGazeProgress()
                                if (gazeTime >= gazeThreshold){

                                    updateGazeProgress()
                                    activateAnnotation(lookingAt!!)
                                    gazeTime = 0
                                }

                            }else {
                                gazeTime= 0
                                updateGazeProgress()
                                lookingAt = n
                                crossHair.setImageResource(com.google.vr.widgets.common.R.drawable.quantum_ic_info_white_24)
                            }

                        }else{
                            lookingAt = null
                            onLookAway()
                            crossHair.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                            gazeTime = 0
                            updateGazeProgress()
                        }
                    }


                }else{
                    if(VRView!!.currentPosition >= locations.data[locationIndex].timestamp){


                        isStanding = true
                        continueButton.visibility = View.VISIBLE
                        VRView!!.pauseVideo()
                        VRView!!.seekTo(locations.data[locationIndex].timestamp.toLong())
                    }
                }

               }

            }else{
            //fallback listener
            if (!(yaw > currentRect[0] &&
                        pitch < currentRect[1] &&
                        yaw < currentRect[2] &&
                        pitch > currentRect[3])){
                onLookAway()
            }
        }
    }
    private fun updateGazeProgress(){
        crosshairProgress.progress = gazeTime*100 / gazeThreshold
    }
    fun getRawResId(filename: String): Int {
        return getResources().getIdentifier(filename,
            "raw", getPackageName()) }

    fun getLayoutResId(filename: String): Int {
        return getResources().getIdentifier(filename,
            "layout", getPackageName()) }
    private fun activateAnnotation(lookingAt: Annotation) {

        when (lookingAt.type) {
            "audio" -> {
                soundPlaying = true
                AnnotationGL = true
                currentRect = lookingAt.hitboxRect
                mediaPlayer = MediaPlayer.create(this,getRawResId(lookingAt.resourceName))
                if (mediaPlayer == null){
                    Toast.makeText(applicationContext, "good evening null", Toast.LENGTH_SHORT).show()
                }
                mediaPlayer!!.start()
                crossHair.setImageResource(android.R.drawable.presence_audio_online)
                mediaPlayer!!.setOnCompletionListener {
                    soundPlaying = false
                    AnnotationGL = false
                    mediaPlayer!!.stop()
                    mediaPlayer!!.release()
                    }
                }
        "XMLbox" -> {
            AnnotationGL = true


            val layout = getLayoutResId(lookingAt.resourceName)
            domeRoot.addView(LayoutInflater.from(applicationContext).inflate(layout, domeRoot, false))
            view = findViewById(R.id.XMLbox)
            currentRect = lookingAt.hitboxRect
            onLookAway = {
                domeRoot.removeView(view)
                onLookAway = {false}
                AnnotationGL = false
                crossHair.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                gazeTime = 0
                updateGazeProgress()
                true
            }
        }
                }
            }





    private fun renderDebug(){
        debugText!!.text =
"""yaw : pitch -> ${yawAndPitch[0]}:${yawAndPitch[1]}
looking at -> $lookingAt
gaze timer -> $gazeTime
current rect -> [${currentRect[0]},${currentRect[1]},${currentRect[2]},${currentRect[3]}]
video time -> ${VRView!!.currentPosition}
standing? ->${isStanding}
location index -> $locationIndex
next stop @ ->${locations.data[locationIndex].timestamp}
annotations -> ${locations.data[locationIndex].annotations}
annotation GL-> ${AnnotationGL}""".trimMargin()

    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        if (p0 != null ) {
            s = 0
        }
    }
    override fun onResume() {
        super.onResume()
        mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        }

    override fun onPause() {
        super.onPause()
        mSensorManager.unregisterListener(this)
        mediaPlayer?.stop()
    }

    override fun onBackPressed() {
        onDestroy()
        super.onBackPressed()
    }
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window,
            window.decorView.findViewById(android.R.id.content)).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())

            // When the screen is swiped up at the bottom
            // of the application, the navigationBar shall
            // appear for some time

            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

}