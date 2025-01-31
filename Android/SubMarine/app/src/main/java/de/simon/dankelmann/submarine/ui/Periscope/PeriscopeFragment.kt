package de.simon.dankelmann.submarine.ui.Periscope

import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.airbnb.lottie.LottieAnimationView
import de.simon.dankelmann.esp32_subghz.ui.connectedDevice.PeriscopeViewModel
import de.simon.dankelmann.submarine.AppContext.AppContext
import de.simon.dankelmann.submarine.Constants.Constants
import de.simon.dankelmann.submarine.Database.AppDatabase
import de.simon.dankelmann.submarine.Entities.LocationEntity
import de.simon.dankelmann.submarine.Entities.SignalEntity
import de.simon.dankelmann.submarine.Interfaces.LocationResultListener
import de.simon.dankelmann.submarine.Interfaces.SubmarineResultListenerInterface
import de.simon.dankelmann.submarine.Models.SubmarineCommand
import de.simon.dankelmann.submarine.R
import de.simon.dankelmann.submarine.databinding.FragmentPeriscopeBinding
import de.simon.dankelmann.submarine.Services.ForegroundService
import de.simon.dankelmann.submarine.Services.LocationService
import de.simon.dankelmann.submarine.Services.SubMarineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.time.LocalDateTime
import kotlin.math.round


class PeriscopeFragment: Fragment(), LocationResultListener, SubmarineResultListenerInterface {
    private val _logTag = "PeriscopeFragment"
    private var _binding: FragmentPeriscopeBinding? = null
    private var _viewModel: PeriscopeViewModel? = null
    private var _bluetoothDevice: BluetoothDevice? = null
    //private var _bluetoothSerial: BluetoothSerial? = null

    private var _capturedSignals = 0
    private var _lastIncomingSignalData = ""
    private var _lastIncomingSignalEntity: SignalEntity? = null
    private var _lastIncomingCc1101Config = ""

    private var _animationView:LottieAnimationView? = null
    private var _locationService:LocationService? = null

    private var _lastLocation:Location? = null
    private var _lastLocationDateTime:LocalDateTime? = null

    private var _submarineService:SubMarineService = AppContext.submarineService

    // Notification Service Intent
    var serviceIntent: Intent? = null


    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewModel = ViewModelProvider(this).get(PeriscopeViewModel::class.java)
        _viewModel = viewModel

        _binding = FragmentPeriscopeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // REQUEST LOCATION UPDATES
        _locationService = LocationService(requireContext(), this)

        setupUi()

        _submarineService.addResultListener(this)
        _submarineService.connect()

        // GET DATA FROM BUNDLE
        /*
        var deviceFromBundle = arguments?.getParcelable("Device") as BluetoothDevice?
        if(deviceFromBundle != null){
            if(PermissionCheck.checkPermission(Manifest.permission.BLUETOOTH_CONNECT)){
                //_viewModel?.updateText(deviceFromBundle.name + " - " + deviceFromBundle.address)
                _bluetoothDevice = deviceFromBundle

                // LETS GO !
                _bluetoothSerial = BluetoothSerial(requireContext(), ::connectionStateChangedCallback)
                Thread(Runnable {
                    _bluetoothSerial?.connect(deviceFromBundle.address, ::receivedDataCallback)
                }).start()
            }
        }*/

        // Start Foreground Service to scan Locations in Background
        serviceIntent = Intent(requireContext(), ForegroundService::class.java)
        serviceIntent!!.putExtra("inputExtra", "Foreground Service Example in Android FROM FRAGMENT")
        serviceIntent!!.action = "ACTION_START_FOREGROUND_SERVICE"
        ContextCompat.startForegroundService(requireContext(), serviceIntent!!)

        return root
    }

    fun setupUi(){
        // SET UP UI

        // ANIMATION VIEW
        _animationView = binding.animationPeriscope

        val description: TextView = binding.textViewPersicopeDescription
        _viewModel!!.description.observe(viewLifecycleOwner) {
            description.text = it
        }

        val capturedSignalInfo: TextView = binding.textViewCapturedSignalInfo
        _viewModel!!.capturedSignalInfo.observe(viewLifecycleOwner) {
            capturedSignalInfo.text = it
        }

        val capturedSignalData: TextView = binding.textViewCapturedSignalData
        _viewModel!!.capturedSignalData.observe(viewLifecycleOwner) {
            capturedSignalData.text = it
        }

        val infoTextFooter: TextView = binding.textViewSignalCounter
        _viewModel!!.infoTextFooter.observe(viewLifecycleOwner) {
            infoTextFooter.text = it
        }

        val connectionState: TextView = binding.textViewConnectionState
        _viewModel!!.connectionState.observe(viewLifecycleOwner) {
            connectionState.text = it
        }

        val locationInfo: TextView = binding.textViewLocation
        _viewModel!!.locationInfo.observe(viewLifecycleOwner) {
            locationInfo.text = it
        }

        // REPLAY BUTTON
        val replayButton: Button = binding.replaySignalButton
        replayButton.setOnClickListener { view ->
            // REPLAY
            _viewModel!!.updateDescription("Transmitting Signal to Sub Marine...")
            if(_lastIncomingSignalData != ""){
                requireActivity().runOnUiThread {
                    _binding!!.animationPeriscope!!.setAnimation(R.raw.wave2)
                    _binding!!.animationPeriscope.playAnimation()
                }
                _viewModel!!.updateDescription("Transmitting Signal to Sub Marine Device")

                Log.d(_logTag, "ReTransmitting: " + _lastIncomingSignalData)
/*
                val command = Constants.COMMAND_REPLAY_SIGNAL_FROM_BLUETOOTH_COMMAND
                val commandId = Constants.COMMAND_ID_DUMMY
                val commandString = command + commandId + _lastIncomingCc1101Config + _lastIncomingSignalData
*/

                if(_lastIncomingSignalEntity != null){
                    _submarineService.setOperationMode(Constants.OPERATIONMODE_IDLE)
                    _submarineService.transmitSignal(_lastIncomingSignalEntity!!, 1, 0)
                }

                //_submarineService.sendCommandToDevice(SubmarineCommand(Constants.COMMAND_REPLAY_SIGNAL_FROM_BLUETOOTH_COMMAND, Constants.COMMAND_ID_DUMMY, _lastIncomingSignalData))

               // _bluetoothSerial!!.sendByteString(commandString + "\n", ::replayStatusCallback)
            }
        }
    }

    override fun onDestroyView() {
        _submarineService.removeResultListener(this)
        super.onDestroyView()
        _binding = null
    }

    override fun onPause() {
        _submarineService.removeResultListener(this)
        super.onPause()
    }

    override fun onResume() {
        _submarineService.addResultListener(this)
        super.onResume()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun replayStatusCallback(message: String){

        requireActivity().runOnUiThread {
            //_binding?.animationPeriscope!!.cancelAnimation()
            _viewModel!!.updateDescription("Transmitting captured Signal")
            _binding!!.animationPeriscope!!.setAnimation(R.raw.sinus)
            _binding!!.animationPeriscope.playAnimation()
        }

        //_animationView!!.setAnimation(R.raw.sinus)
        //_animationView!!.playAnimation()
        // GIVE IT SOME TIME TO TRANSMIT THE SIGNAL
        Handler(Looper.getMainLooper()).postDelayed(Runnable {
            setOperationMode(Constants.OPERATIONMODE_PERISCOPE)
            requireActivity().runOnUiThread {
                //_binding?.animationPeriscope!!.cancelAnimation()
                _viewModel!!.updateDescription("Looking for Signals")
                _binding!!.animationPeriscope!!.setAnimation(R.raw.radar)
                _binding!!.animationPeriscope.playAnimation()
            }
        }, 1500)
        //Thread.sleep(1_500)
        // GO BACK TO PERISCOPE MODE
        /*activity?.runOnUiThread {
            _binding!!.animationPeriscope.setAnimation(R.raw.radar)
            _binding!!.animationPeriscope.playAnimation()
        }*/
    }


    private fun setOperationMode(operationMode:String){
        _submarineService.setOperationMode(operationMode)
    }

    private fun receivedDataCallback(message: String){
        if(message != ""){
            Log.d(_logTag, "Received: " + message)

            // PARSE COMMAND AND DATA
            var incomingCommand = message.substring(0,4)
            var incomingCommandId = message.substring(4,8)

            Log.d(_logTag, "Icoming Command: " + incomingCommand)
            Log.d(_logTag, "Icoming Command Id: " + incomingCommandId)

            when (incomingCommand) {
                "0003" -> handleIncomingSignalTransfer(message)
                else -> { // Note the block
                    Log.d(_logTag, "Icoming Command not parseable")
                }
            }
        }
    }



    private fun handleIncomingSignalTransfer(data:String){
        var configEndIndex = Constants.BLUETOOTH_COMMAND_HEADER_LENGTH + Constants.CC1101_ADAPTER_CONFIGURATION_LENGTH
        var cc1101ConfigString = data.substring(Constants.BLUETOOTH_COMMAND_HEADER_LENGTH, configEndIndex)
        var signalData = data.substring(configEndIndex)

        Log.d(_logTag, "Configstring: " + cc1101ConfigString)
        Log.d(_logTag, "Signaldata: " + signalData)

        // CLEAR EMPTY FIRST SAMPLES:
        var samples = signalData.split(",").toMutableList()
        while(samples.last().toInt() <= 0){
            samples.removeLast()
        }

        // CLEAR EMPTY LAST SAMPLES:
        while(samples.first().toInt() <= 0){
            samples.removeFirst()
        }

        if(samples.size >= Constants.MIN_TIMINGS_TO_SAVE){

            val locationDao = AppDatabase.getDatabase(requireContext()).locationDao()
            val signalDao = AppDatabase.getDatabase(requireContext()).signalDao()
            CoroutineScope(Dispatchers.IO).launch {
                var locationId = 0
                // SAVE LOCATION ?!
                if(_lastLocation != null && _lastLocationDateTime != null){

                    var locationEntity: LocationEntity = LocationEntity(0, _lastLocation!!.accuracy,_lastLocation!!.altitude,_lastLocation!!.latitude,_lastLocation!!.longitude,_lastLocation!!.speed)
                    locationId = locationDao.insertItem(locationEntity).toInt()
                    Log.d(_logTag, "Saved Location with ID: " + locationId)
                }

                var signalEntity = _submarineService.parseSignalEntityFromDataString(data, locationId)
                var signalId = signalDao.insertItem(signalEntity).toInt()

                _lastIncomingSignalEntity = signalEntity
                Log.d(_logTag, "Saved Signal with ID: " + signalId)
            }

            signalData = samples.joinToString(",")

            var samplesCount = signalData.split(',').size
            _viewModel!!.capturedSignalInfo.postValue("Received " + samplesCount + " Samples")

            _lastIncomingSignalData = signalData
            _lastIncomingCc1101Config = cc1101ConfigString
            _viewModel!!.capturedSignalData.postValue(signalData)
            _capturedSignals++;
            _viewModel!!.infoTextFooter.postValue(_capturedSignals.toString() + " Signals captured")
        } else {
            Log.d(_logTag, "Skipping Signal because its too small")
        }

    }

    private fun connectionStateChangedCallback(connectionState: Int){
        Log.d(_logTag, "Connection Callback: " + connectionState)
        when(connectionState){
            0 -> {
                Log.d(_logTag, "Disconnected")
                _viewModel!!.connectionState.postValue("Disconnected")
            }
            1 -> {
                Log.d(_logTag, "Connecting...")
                requireActivity().runOnUiThread {
                    //_binding?.animationPeriscope!!.cancelAnimation()
                    _binding!!.animationPeriscope!!.setAnimation(R.raw.dots)
                    _binding!!.animationPeriscope.playAnimation()
                }

                _viewModel!!.connectionState.postValue("Connecting...")
            }
            2 -> {
                Log.d(_logTag, "Connected")
                _viewModel!!.connectionState.postValue("Connected")
                // ACTIVATE PERISCOPE MODE
                setOperationMode(Constants.OPERATIONMODE_PERISCOPE)
                requireActivity().runOnUiThread {
                    //_binding?.animationPeriscope!!.cancelAnimation()
                    _binding!!.animationPeriscope!!.setAnimation(R.raw.radar)
                    _binding!!.animationPeriscope.playAnimation()
                }
            }
        }
    }

    override fun receiveLocationChanges(location: Location) {
        //Log.d(_logTag, "Location updated")
        _lastLocation = location
        _lastLocationDateTime = LocalDateTime.now()

        var decimals = 4
        _viewModel!!.locationInfo.postValue(location.longitude.round(decimals).toString() + " | " + location.latitude.round(decimals).toString())
    }

    fun Double.round(decimals: Int): Double {
        var multiplier = 1.0
        repeat(decimals) { multiplier *= 10 }
        return round(this * multiplier) / multiplier
    }

    override fun onConnectionStateChanged(connectionState: Int) {
        connectionStateChangedCallback(connectionState)
    }

    override fun onIncomingData(data: String, command: SubmarineCommand?) {
        receivedDataCallback(data)
    }

    override fun onOutgoingData(timeElapsed: Int, command: SubmarineCommand?) {
        // NOT IN USE
    }

    override fun onCommandSent(timeElapsed: Int, command: SubmarineCommand) {
        // NOT IN USE
    }

    override fun onOperationModeSet(timeElapsed: Int, command: SubmarineCommand) {
        // NOT IN USE
    }

    override fun onSignalReplayed(timeElapsed: Int, command: SubmarineCommand) {
        replayStatusCallback("SIGNAL REPLAYED")
    }


}