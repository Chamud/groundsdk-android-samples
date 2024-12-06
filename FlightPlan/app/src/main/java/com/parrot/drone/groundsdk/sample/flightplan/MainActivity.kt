package com.parrot.drone.groundsdk.sample.flightplan

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.RemoteControl
import com.parrot.drone.groundsdk.device.pilotingitf.Activable
import com.parrot.drone.groundsdk.device.pilotingitf.FlightPlanPilotingItf
import com.parrot.drone.groundsdk.facility.AutoConnection
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class Logger(private val context: Context) {

    private val logFile: File
    private val logFileName = "app_logs.txt"

    init {
        // Define the log file location
        logFile = File(context.getExternalFilesDir(null), logFileName)
        // Ensure the file exists
        if (!logFile.exists()) {
            logFile.createNewFile()
        }
    }

    // Write log message to file
    fun log(message: String) {
        val timestamp = getCurrentTimestamp()
        val logMessage = "$timestamp: $message\n"
        try {
            FileOutputStream(logFile, true).use { fos ->
                fos.write(logMessage.toByteArray())
            }
            Log.d("Logger", "Log saved: $logMessage") // Optional: Log to Android log as well
        } catch (e: IOException) {
            Log.e("Logger", "Error writing log to file", e)
        }
    }

    // Retrieve current timestamp
    private fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    // Optional: Read all logs from the file
    fun readLogs(): String {
        return try {
            logFile.readText()
        } catch (e: IOException) {
            "Error reading log file"
        }
    }
}

class MainActivity : AppCompatActivity() {

    /** GroundSdk instance. */
    private lateinit var groundSdk: GroundSdk

    // Drone:
    /** Current drone instance. */
    private var drone: Drone? = null
    /** Reference to the current drone state. */
    private var droneStateRef: Ref<DeviceState>? = null
    /** Reference to drone Flight Plan piloting interface. */
    private var pilotingItfRef: Ref<FlightPlanPilotingItf>? = null

    // Remote control:
    /** Current remote control instance. */
    private var rc: RemoteControl? = null
    /** Reference to the current remote control state. */
    private var rcStateRef: Ref<DeviceState>? = null

    // User Interface:
    /** Drone state text view. */
    private lateinit var droneStateTxt: TextView
    /** RC state text view. */
    private lateinit var rcStateTxt: TextView
    /** Flight Plan latest upload state text view. */
    private lateinit var uploadStateTxt: TextView
    /** Flight Plan unavailability reasons list. */
    private lateinit var unavailabilityReasonsTxt: TextView
    /** Upload Flight Plan button. */
    private lateinit var uploadBtn: Button
    /** Activate Flight Plan button. */
    private lateinit var activateBtn: Button

    /** Logger instance for logging actions. */
    private lateinit var logger: Logger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get user interface instances.
        droneStateTxt = findViewById(R.id.droneStateTxt)
        rcStateTxt = findViewById(R.id.rcStateTxt)
        uploadStateTxt = findViewById(R.id.uploadStateTxt)
        unavailabilityReasonsTxt = findViewById(R.id.unavailabilityReasonsTxt)

        activateBtn = findViewById<Button>(R.id.activateBtn).apply {
            setOnClickListener { onActivateClick() }
        }
        uploadBtn = findViewById<Button>(R.id.uploadPlanBtn).apply {
            setOnClickListener { onUploadClick() }
        }

        // Initialize user interface default values.
        droneStateTxt.text = DeviceState.ConnectionState.DISCONNECTED.toString()
        rcStateTxt.text = DeviceState.ConnectionState.DISCONNECTED.toString()
        uploadStateTxt.text = FlightPlanPilotingItf.UploadState.NONE.toString()

        // Initialize Logger
        logger = Logger(this)

        // Log creation of MainActivity
        logger.log("MainActivity onCreate() called.")

        // Get a GroundSdk session.
        groundSdk = ManagedGroundSdk.obtainSession(this)
        logger.log("GroundSdk session obtained.")

        logFlightPlanData()

        // Monitor the auto connection facility.
        groundSdk.getFacility(AutoConnection::class.java) { autoConnection ->
            // Called when the auto connection facility is available and when it changes.
            autoConnection ?: return@getFacility // if it is not available, we have nothing to do

            // Start auto connection if necessary.
            if (autoConnection.status != AutoConnection.Status.STARTED) {
                logger.log("Auto connection not started, starting it now.")
                autoConnection.start()
            } else {
                logger.log("Auto connection already started.")
            }

            // If the drone has changed.
            if (drone?.uid != autoConnection.drone?.uid) {
                // Stop monitoring the previous drone.
                logger.log("Drone has changed.")
                if (drone != null) stopDroneMonitors()
                // Monitor the new drone.
                drone = autoConnection.drone
                if (drone != null) startDroneMonitors()
            }

            // If the remote control has changed.
            if (rc?.uid  != autoConnection.remoteControl?.uid) {
                // Stop monitoring the old remote.
                logger.log("Remote control has changed.")
                if (rc != null) stopRcMonitors()
                // Monitor the new remote.
                rc = autoConnection.remoteControl
                if(rc != null) startRcMonitors()
            }
        }
    }

    /**
     * Starts drone monitors.
     */
    private fun startDroneMonitors() {
        logger.log("Starting drone monitors.")
        // Monitor current drone state.
        droneStateRef = drone?.getState { droneState ->
            // Called at each drone state update.
            droneState ?: return@getState

            // Update drone connection state view.
            droneStateTxt.text = droneState.connectionState.toString()

            // Log the connection state change.
            logger.log("Drone connection state changed: ${droneState.connectionState}")
        }

        // Monitor piloting interface.
        pilotingItfRef = drone?.getPilotingItf(
            FlightPlanPilotingItf::class.java, ::managePilotingItfState)
    }

    private fun logFlightPlanData() {
        // Open the flightplan.mavlink file and read its content
        val flightPlanData = runCatching {
            assets.open("flightplan.mavlink").use { input ->
                input.bufferedReader().use { it.readText() }
            }
        }.getOrNull()

        // If the file was successfully read, log its content
        flightPlanData?.let {
            logger.log("Flight Plan Data:\n$it")
        } ?: run {
            logger.log("Failed to read the flight plan data.")
        }
    }

    /**
     * Stops drone monitors.
     */
    private fun stopDroneMonitors() {
        logger.log("Stopping drone monitors.")
        // Close all references linked to the current drone to stop their monitoring.
        droneStateRef?.close()
        droneStateRef = null

        pilotingItfRef?.close()
        pilotingItfRef = null

        // Reset drone user interface views.
        droneStateTxt.text = DeviceState.ConnectionState.DISCONNECTED.toString()
    }

    /**
     * Manage piloting interface state.
     *
     * @param itf the piloting interface
     */
    private fun managePilotingItfState(itf: FlightPlanPilotingItf?) {
        // Log upload state
        uploadStateTxt.text = itf?.latestUploadState?.toString() ?: "N/A"
        logger.log("Upload state set to: ${uploadStateTxt.text}")

        // Log unavailability reasons
        unavailabilityReasonsTxt.text = itf?.unavailabilityReasons?.joinToString(separator = "\n")
        logger.log("Unavailability reasons: ${unavailabilityReasonsTxt.text}")

        // Log upload button state
        uploadBtn.isEnabled =
            itf?.latestUploadState !in setOf(null, FlightPlanPilotingItf.UploadState.UPLOADING)
        logger.log("Upload button isEnabled set to: ${uploadBtn.isEnabled}")

        // Determine and log piloting interface state
        val state = itf?.state ?: Activable.State.UNAVAILABLE
        logger.log("Piloting interface state is: $state")

        // Configure and log activate button state
        activateBtn.apply {
            isEnabled = state != Activable.State.UNAVAILABLE
            text = when (state) {
                Activable.State.ACTIVE -> "stop"
                else                   -> "start"
            }
            logger.log("Activate button isEnabled set to: $isEnabled")
            logger.log("Activate button text set to: $text")
        }

        // Log piloting interface state changes
        logger.log("Piloting interface state changed: ${itf?.state}")
    }

    private fun onUploadClick() {
        logger.log("Upload button clicked.")
        val pilotingItf = pilotingItfRef?.get() ?: return

        // Launch the file operation on an I/O thread
        CoroutineScope(Dispatchers.IO).launch {
            val flightPlanFile = runCatching {
                assets.open("flightplan.mavlink").use { input ->
                    File.createTempFile("flightplan", ".mavlink", cacheDir).also {
                        it.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }.getOrNull()

            if (flightPlanFile != null) {
                logger.log("Flight plan file created: $flightPlanFile")

                // Ensure UI updates or piloting interface interactions happen on the main thread
                CoroutineScope(Dispatchers.Main).launch {
                    pilotingItf.uploadFlightPlan(flightPlanFile)
                    val reasons = pilotingItf.unavailabilityReasons
                    if (reasons != null) {
                        logger.log("Unavailability reasons: ${reasons.joinToString()}")
                    } else {
                        logger.log("No unavailability reasons found.")
                    }
                }
            } else {
                logger.log("Failed to create flight plan file.")
            }
        }
    }


    /**
     * Called on activate button click.
     */
    private fun onActivateClick() {
        logger.log("Activate button clicked.")
        val pilotingItf = pilotingItfRef?.get() ?: return

        when (pilotingItf.state) {
            Activable.State.ACTIVE -> pilotingItf.stop()
            Activable.State.IDLE   -> pilotingItf.activate(true)
            else -> {}
        }

        logger.log("Piloting interface activated/stopped.")
    }

    /**
     * Starts remote control monitors.
     */
    private fun startRcMonitors() {
        logger.log("Starting remote control monitors.")
        // Monitor current RC state.
        rcStateRef = rc?.getState { rcState ->
            // Called at each remote state update.
            rcState ?: return@getState

            // Update remote connection state view.
            rcStateTxt.text = rcState.connectionState.toString()

            // Log the connection state change.
            logger.log("Remote control connection state changed: ${rcState.connectionState}")
        }
    }

    /**
     * Stops remote control monitors.
     */
    private fun stopRcMonitors() {
        logger.log("Stopping remote control monitors.")
        // Close all references linked to the current remote to stop their monitoring.
        rcStateRef?.close()
        rcStateRef = null

        // Reset remote control user interface views.
        rcStateTxt.text = DeviceState.ConnectionState.DISCONNECTED.toString()
    }
}
