package com.device.wifip2p

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.YuvImage
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: BroadcastReceiver
    private lateinit var intentFilter: IntentFilter
    lateinit var connState: TextView
    private var peers: ArrayList<WifiP2pDevice> = ArrayList()
    private var dvcNameArr = arrayOf<String>()
    private var dvcArr = arrayOf<WifiP2pDevice>()
    var peerListListener: WifiP2pManager.PeerListListener = WifiP2pManager.PeerListListener { peerList ->
        if (peerList != null) {
            if (!peerList.deviceList.equals(peers)) {
                peers.clear()
                peers.addAll(peerList.deviceList)

                dvcNameArr = arrayOf()
                dvcArr = arrayOf()

                for (device in peerList.deviceList) {
                    dvcNameArr += device.deviceName
                    dvcArr += device
                    Log.d("Found",device.deviceName.toString())
                }

                updateDvcList()

                if (peers.size == 0) {
                    Toast.makeText(applicationContext,"No Device Found",Toast.LENGTH_SHORT).show()
                    return@PeerListListener
                }
            }
        }
    }

    var connectionInfoListener: WifiP2pManager.ConnectionInfoListener = WifiP2pManager.ConnectionInfoListener { wifiP2pInfo ->
            if (wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner) {
                connState.text = getString(R.string.host)
                serverClass = ServerClass(this)
                serverClass.start()
                Log.i("conn","serverClass started!")
            } else if (wifiP2pInfo.groupFormed) {
                connState.text = getString(R.string.client)
                clientClass = ClientClass(wifiP2pInfo.groupOwnerAddress, this)
                clientClass.start()
                Log.i("conn","clientClass started!")
            }
        }

    @Suppress("DEPRECATION")
    var handler: Handler = Handler { msg ->
        when (msg.what) {
            1 -> {
                val readBuff = msg.obj as ByteArray
                findViewById<TextView>(R.id.readMsg).text = String(readBuff, 0, msg.arg1)
            }
        }
        true
    }

    private lateinit var serverClass: ServerClass
    private lateinit var clientClass: ClientClass
    private lateinit var sendReceive: SendReceive
    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
//        val policy = ThreadPolicy.Builder()
//            .permitAll().build()
//        StrictMode.setThreadPolicy(policy)
        surfaceView = findViewById(R.id.Surface)
        surfaceHolder = surfaceView.holder
        init()
        surfaceHolder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {

            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Surface size or format has changed
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Surface is destroyed
            }
        })
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun init() {

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        receiver = WiFiDirectBroadcastReceiver(manager,channel,this)
        intentFilter = IntentFilter()
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_LISTEN_STARTED.toString())

        val wifiSwitch = findViewById<Button>(R.id.wifiSwitch)
        wifiSwitch.setOnClickListener { switch() }

        discover()

        val send: Button = findViewById(R.id.sendButton)
        manager.requestConnectionInfo(channel, connectionInfoListener)
        send.setOnClickListener {
//            val msg: String = findViewById<EditText>(R.id.writeMsg).text.toString()
//            sendReceive.write(msg.toByteArray(), rotationDegrees)
        }

    }

    private fun switch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiIntent = Intent(Settings.Panel.ACTION_WIFI)
            startActivity(wifiIntent)
        }
    }

    private fun discover() {

        connState = findViewById(R.id.connectionStatus)

        val discover = findViewById<Button>(R.id.discover)
        discover.setOnClickListener {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA
                )!= PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.READ_MEDIA_IMAGES
                )!= PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.CAMERA,Manifest.permission.READ_MEDIA_IMAGES) as Array<out String>, 0)
            } else {
                manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        connState.text = getString(R.string.discovery_started)

                    }

                    override fun onFailure(i: Int) {
                        connState.text = getString(R.string.discovery_failed)
                    }

                })
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateDvcList() {

        val adapter: ArrayAdapter<String> = ArrayAdapter<String>(applicationContext, android.R.layout.simple_list_item_1, dvcNameArr)
        val listView = findViewById<ListView>(R.id.peerListView)

        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, i, _ ->
            val device: WifiP2pDevice = dvcArr[i]
            val config = WifiP2pConfig()
            config.deviceAddress = device.deviceAddress


            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Toast.makeText(applicationContext, "Connected to " + device.deviceName, Toast.LENGTH_SHORT).show()
                }

                override fun onFailure(p0: Int) {
                    Toast.makeText(applicationContext, "Not Connected", Toast.LENGTH_SHORT).show()
                }
            })
        }

    }

    class ServerClass(private val activity: MainActivity): Thread() {
        private lateinit var socket: Socket
        private lateinit var serverSocket: ServerSocket
        private lateinit var inputStream: InputStream
        private lateinit var outputStream: OutputStream
        override fun run() {
            try {
                serverSocket = ServerSocket(8888)
                socket = serverSocket.accept()
                inputStream = socket.getInputStream()
                outputStream = socket.getOutputStream()
                Log.d("ServerClass","sendReceive did init")
                activity.sendReceive = SendReceive(socket, activity.surfaceHolder)
                activity.sendReceive.start()
            } catch (e: IOException) {
                Log.e("ServerClass",e.toString())
            }
        }
    }

    class ClientClass(hostAddress: InetAddress, private val activity: MainActivity) : Thread() {
        private var socket: Socket
        private var hostAdd: String

        init {
            hostAdd = hostAddress.hostAddress as String
            socket = Socket()
        }

        override fun run() {
            try {
                socket.connect(InetSocketAddress(hostAdd, 8888), 500)
                Log.d("ClientClass","sendReceive did init")
                activity.sendReceive = SendReceive(socket, activity.surfaceHolder)
                activity.sendReceive.start()
                activity.startCamera()

            } catch (e: IOException) {
                Log.e("ClientClass",e.toString())
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), ImageAnalysis.Analyzer { imageProxy ->
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                val yuvImage = YuvImage(bytes, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
                val jpegBytes = out.toByteArray()

                // Send JPEG bytes to the server
                sendReceive.write(jpegBytes, rotationDegrees)
                imageProxy.close()
            })

            preview.setSurfaceProvider(findViewById<PreviewView>(R.id.cameraPreview).surfaceProvider)
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }




    //    private class SendReceive(skt: Socket, private val activity: MainActivity): Thread() {
//        private var socket: Socket
//        private lateinit var iStream: InputStream
//        private lateinit var oStream: OutputStream
//
//        init {
//            socket = skt
//            try {
//                iStream = socket.getInputStream()
//                oStream = socket.getOutputStream()
//            } catch (e: IOException) {
//                Log.e("SendReceive", "Stream not init$e")
//            }
//        }
//
//        override fun run() {
//            val buffer = ByteArray(1024)
//            var bytes: Int
//
//            while (true) {
//                try {
//                    bytes = iStream.read(buffer)
//                    if (bytes > 0) {
//                        activity.handler.obtainMessage(1,bytes,-1,buffer).sendToTarget()
//                    }
//                } catch (e: IOException) {
//                    Log.e("SendReceive", "Socket Error $e")
//                    break
//                }
//            }
//        }
//
//        fun write(bytes: ByteArray) {
//            try {
//                oStream.write(bytes)
//            } catch (e: IOException) {
//                Log.e("ServerClass","oStream write $e")
//            }
//        }
//    }

    class SendReceive(private val socket: Socket, private val surfaceHolder: SurfaceHolder) : Thread() {
        private var inputStream: InputStream? = null
        private var outputStream: OutputStream? = null

        init {
            try {
                inputStream = socket.getInputStream()
                outputStream = socket.getOutputStream()
            } catch (e: IOException) {
                Log.e("SendReceive", "Error initializing streams: $e")
            }
        }

        override fun run() {
            try {
                val input = inputStream ?: return

                while (!socket.isClosed && socket.isConnected) {
                    val sizeBuffer = ByteArray(4)
                    val rotationBuffer = ByteArray(4)
                    val bytesReadSize = input.read(sizeBuffer)
                    val bytesReadRotation = input.read(rotationBuffer)

                    if (bytesReadSize != 4 || bytesReadRotation != 4) {
                        Log.e("SendReceive", "Failed to read image size or rotation.")
                        break
                    }

                    val size = byteArrayToInt(sizeBuffer)
                    val rotationDegrees = byteArrayToInt(rotationBuffer)

                    if (size > 0) {
                        val frameBuffer = ByteArray(size)
                        var totalBytesRead = 0
                        while (totalBytesRead < size) {
                            val remainingBytes = size - totalBytesRead
                            val bytesRead = input.read(frameBuffer, totalBytesRead, remainingBytes)
                            if (bytesRead == -1) {
                                Log.e("SendReceive", "End of stream reached unexpectedly.")
                                break
                            }
                            totalBytesRead += bytesRead
                        }

                        if (totalBytesRead == size) {
                            val bitmap = BitmapFactory.decodeByteArray(frameBuffer, 0, size)
                            if (bitmap != null) {
                                val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees.toFloat())
                                val canvas = surfaceHolder.lockCanvas()
                                if (canvas != null) {
                                    canvas.drawBitmap(rotatedBitmap, 0f, 0f, null)
                                    surfaceHolder.unlockCanvasAndPost(canvas)
                                }
                            }
                        } else {
                            Log.e("SendReceive", "Failed to read complete image data.")
                        }
                    } else {
                        Log.e("SendReceive", "Invalid size received.")
                    }
                }
            } catch (e: IOException) {
                Log.e("SendReceive", "Socket Error: $e")
            } finally {
                closeResources()
            }
        }

        fun write(bytes: ByteArray, rotationDegrees: Int) {
            try {
                val output = outputStream ?: return
                val size = bytes.size
                val sizeBuffer = intToByteArray(size)
                val rotationBuffer = intToByteArray(rotationDegrees)
                output.write(sizeBuffer)
                output.write(rotationBuffer)
                output.write(bytes)
                output.flush()
            } catch (e: IOException) {
                Log.e("SendReceive", "Error writing to output stream: $e")
            }
        }

        private fun byteArrayToInt(byteArray: ByteArray): Int {
            return byteArray[3].toInt() and 0xFF or
                    (byteArray[2].toInt() and 0xFF shl 8) or
                    (byteArray[1].toInt() and 0xFF shl 16) or
                    (byteArray[0].toInt() and 0xFF shl 24)
        }

        private fun intToByteArray(value: Int): ByteArray {
            return byteArrayOf(
                (value shr 24).toByte(),
                (value shr 16).toByte(),
                (value shr 8).toByte(),
                value.toByte()
            )
        }

        private fun closeResources() {
            try {
                inputStream?.close()
                outputStream?.close()
                socket.close()
            } catch (e: IOException) {
                Log.e("SendReceive", "Error closing resources: $e")
            }
        }

        private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees)
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }

}
