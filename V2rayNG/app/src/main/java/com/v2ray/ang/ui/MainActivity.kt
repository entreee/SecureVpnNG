package com.v2ray.ang.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MigrateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity() {

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    val mainViewModel: MainViewModel by viewModels()

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                startV2Ray()
            }
        }

    // register activity result for requesting permission
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                when (pendingAction) {
                    Action.IMPORT_QR_CODE_CONFIG ->
                        scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))

                    Action.READ_CONTENT_FROM_URI ->
                        chooseFileForCustomConfig.launch(
                            Intent.createChooser(
                                Intent(Intent.ACTION_GET_CONTENT).apply {
                                    type = "*/*"
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                }, getString(R.string.title_file_chooser)
                            )
                        )

                    Action.POST_NOTIFICATIONS -> {}
                    else -> {}
                }
            } else {
                toast(R.string.toast_permission_denied)
            }
            pendingAction = Action.NONE
        }

    private var pendingAction: Action = Action.NONE

    enum class Action {
        NONE,
        IMPORT_QR_CODE_CONFIG,
        READ_CONTENT_FROM_URI,
        POST_NOTIFICATIONS
    }

    private val chooseFileForCustomConfig =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val uri = it.data?.data
            if (it.resultCode == RESULT_OK && uri != null) {
                readContentFromUri(uri)
            }
        }

    private val scanQRCodeForConfig =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        // Power button: start/stop VPN
        binding.btnPower.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                V2RayServiceManager.stopVService(this)
            } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                startV2Ray()
            }
        }

        // Status: tap to test connection when running
        binding.tvStatus.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setStatusText(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            }
        }

        // Current server selector
        binding.tvCurrentServer.setOnClickListener { showServerPicker() }

        // Bottom actions: Clipboard / QR
        binding.btnClipboard.setOnClickListener { importClipboard() }
        binding.btnQrcode.setOnClickListener { importQRcode() }

        setupViewModel()
        migrateLegacy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingAction = Action.POST_NOTIFICATIONS
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setStatusText(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            updatePowerButton(isRunning)
            if (isRunning) {
                setStatusText(getString(R.string.connection_connected))
            } else {
                setStatusText(getString(R.string.connection_not_connected))
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun migrateLegacy() {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = MigrateManager.migrateServerConfig2Profile()
            launch(Dispatchers.Main) {
                if (result) {
                    toast(getString(R.string.migration_success))
                    mainViewModel.reloadServerList()
                    updateCurrentServerLabel()
                }
            }
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    private fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
        updateCurrentServerLabel()
    }

    /**
     * Server picker dialog (minimal list)
     */
    private fun showServerPicker() {
        val servers = mainViewModel.serversCache.toList()
        if (servers.isEmpty()) {
            toast(R.string.toast_none_data)
            return
        }
        val labels = servers.map { it.profile.remarks }.toTypedArray()
        val selectedGuid = MmkvManager.getSelectServer()
        var preselectIndex = servers.indexOfFirst { it.guid == selectedGuid }
        if (preselectIndex < 0) preselectIndex = 0

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_server))
            .setSingleChoiceItems(labels, preselectIndex) { dialog, which ->
                val guid = servers[which].guid
                val old = MmkvManager.getSelectServer()
                if (guid != old) {
                    MmkvManager.setSelectServer(guid)
                    updateCurrentServerLabel()
                    if (mainViewModel.isRunning.value == true) {
                        restartV2Ray()
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun updateCurrentServerLabel() {
        val guid = MmkvManager.getSelectServer()
        val remarks = guid?.let { MmkvManager.decodeServerConfig(it)?.remarks }.orEmpty()
        binding.tvCurrentServer.text = "Текущий сервер: ${remarks.ifBlank { "-" }} ▼"
    }

    private fun updatePowerButton(isRunning: Boolean) {
        val neon = ContextCompat.getColor(this, R.color.my_neon_green)
        val grey = ContextCompat.getColor(this, R.color.color_fab_inactive)

        if (isRunning) {
            binding.btnPower.icon = ContextCompat.getDrawable(this, R.drawable.ic_stop_24dp)
            binding.btnPower.backgroundTintList = ContextCompat.getColorStateList(this, R.color.my_neon_green)
            binding.btnPower.strokeColor = ContextCompat.getColorStateList(this, R.color.my_neon_green)
            startPulse(binding.btnPower)
            binding.tvStatus.setTextColor(neon)
        } else {
            binding.btnPower.icon = ContextCompat.getDrawable(this, R.drawable.ic_play_24dp)
            binding.btnPower.backgroundTintList = ContextCompat.getColorStateList(this, R.color.color_fab_inactive)
            binding.btnPower.strokeColor = ContextCompat.getColorStateList(this, R.color.my_neon_green)
            stopPulse(binding.btnPower)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun setStatusText(content: String?) {
        binding.tvStatus.text = content
    }

    private fun startPulse(target: android.view.View) {
        val scaleUpX = PropertyValuesHolder.ofFloat(android.view.View.SCALE_X, 1f, 1.06f)
        val scaleUpY = PropertyValuesHolder.ofFloat(android.view.View.SCALE_Y, 1f, 1.06f)
        ObjectAnimator.ofPropertyValuesHolder(target, scaleUpX, scaleUpY).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = TimeInterpolator { input ->
                // Ease in-out
                if (input < 0.5f) 2f * input * input else -1f + (4f - 2f * input) * input
            }
            start()
        }
    }

    private fun stopPulse(target: android.view.View) {
        target.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
        } else {
            pendingAction = Action.IMPORT_QR_CODE_CONFIG
            requestPermissionLauncher.launch(permission)
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard(): Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) =
                    AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                            updateCurrentServerLabel()
                        }

                        countSub > 0 -> {
                            toastSuccess(R.string.import_subscription_success)
                            mainViewModel.reloadServerList()
                            updateCurrentServerLabel()
                        }

                        else -> toastError(R.string.toast_failure)
                    }
                    binding.pbWaiting.hide()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    binding.pbWaiting.hide()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pendingAction = Action.READ_CONTENT_FROM_URI
            chooseFileForCustomConfig.launch(
                Intent.createChooser(intent, getString(R.string.title_file_chooser))
            )
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    importBatchConfig(input?.bufferedReader()?.readText())
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun importManually(createConfigType: Int) {
        startActivity(
            Intent()
                .putExtra("createConfigType", createConfigType)
                .putExtra("subscriptionId", mainViewModel.subscriptionId)
                .setClass(this, ServerActivity::class.java)
        )
    }

    private fun setStatusText(content: String?) {
        binding.tvStatus.text = content
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}