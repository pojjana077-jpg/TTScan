package com.example.ttscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.example.ttscan.databinding.ActivityMainBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * เช็คพาสงาน
 *  - โหมดสแกน: สแกน QR บนใบ TT แล้วเช็คว่ามีโฟลเดอร์ชื่อเดียวกันพร้อมรูปครบ 2 รูปในที่เก็บงานหรือยัง
 *  - ถ้ายังไม่ได้ถ่าย: เข้าโหมดถ่าย 2 รูป แล้วสร้างโฟลเดอร์ (ชื่อ = ข้อความใน QR) พร้อมบันทึกรูปให้อัตโนมัติ
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { SCAN, CAPTURE }

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("ttscan", MODE_PRIVATE) }

    private var rootUri: Uri? = null
    private var mode = Mode.SCAN
    private var currentName: String? = null
    private val shots = arrayListOf<File>()

    private var imageCapture: ImageCapture? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var lastCode: String? = null
    private var lastCodeTime = 0L

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit().putString("root", uri.toString()).apply()
                rootUri = uri
                updateFolderLabel()
                setStatus("สแกน QR บนใบ TT", Color.WHITE)
            }
        }

    private val askCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else setStatus("ต้องอนุญาตกล้องก่อนถึงจะสแกนได้", Color.parseColor("#FF8A80"))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        rootUri = loadSavedRoot()
        updateFolderLabel()

        b.btnPick.setOnClickListener { pickFolder.launch(null) }
        b.btnList.setOnClickListener { showDoneList() }
        b.btnCancel.setOnClickListener { cancelCapture() }
        b.btnShutter.setOnClickListener { takePhoto() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else askCamera.launch(Manifest.permission.CAMERA)

        if (rootUri == null) {
            setStatus("เลือกที่เก็บงานก่อน", Color.parseColor("#FFD180"))
            pickFolder.launch(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        shots.forEach { it.delete() }
    }

    // ---------------------------------------------------------------- ที่เก็บงาน

    private fun loadSavedRoot(): Uri? {
        val s = prefs.getString("root", null) ?: return null
        val uri = Uri.parse(s)
        val stillGranted = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (stillGranted) uri else null
    }

    private fun updateFolderLabel() {
        val name = rootUri?.let { DocumentFile.fromTreeUri(this, it)?.name }
        b.tvFolder.text = if (name != null) "ที่เก็บ: $name" else "ที่เก็บ: ยังไม่ได้เลือก"
    }

    private fun rootDir(): DocumentFile? = rootUri?.let { DocumentFile.fromTreeUri(this, it) }

    // ---------------------------------------------------------------- กล้อง + สแกน

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(b.preview.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { proxy ->
                val media = proxy.image
                if (media == null || mode != Mode.SCAN || busy.get()) {
                    proxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { list ->
                        val value = list.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                        if (value != null) runOnUiThread { onScanned(value.trim()) }
                    }
                    .addOnCompleteListener { proxy.close() }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onScanned(code: String) {
        if (mode != Mode.SCAN) return
        val now = SystemClock.elapsedRealtime()
        if (code == lastCode && now - lastCodeTime < 3000) return
        lastCode = code
        lastCodeTime = now

        if (rootUri == null) {
            setStatus("เลือกที่เก็บงานก่อน", Color.parseColor("#FFD180"))
            return
        }
        if (!busy.compareAndSet(false, true)) return

        val name = safeName(code)
        setStatus("กำลังเช็ค $name ...", Color.WHITE)
        Thread {
            val count = countImages(name)
            runOnUiThread {
                busy.set(false)
                b.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (count >= 2) {
                    setStatus("✅ $name\nถ่ายแล้ว ($count รูป) ไม่ต้องถ่ายซ้ำ", Color.parseColor("#69F0AE"))
                } else {
                    startCapture(name)
                }
            }
        }.start()
    }

    /** ชื่อโฟลเดอร์ห้ามมีอักขระต้องห้ามของระบบไฟล์ */
    private fun safeName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().take(100)

    /** จำนวนรูปที่อยู่ในโฟลเดอร์งานนั้น (0 ถ้ายังไม่มีโฟลเดอร์) */
    private fun countImages(name: String): Int {
        val dir = rootDir()?.findFile(name) ?: return 0
        if (!dir.isDirectory) return 0
        return dir.listFiles().count {
            it.isFile && (it.type?.startsWith("image/") == true ||
                    it.name?.lowercase()?.matches(Regex(".*\\.(jpg|jpeg|png)")) == true)
        }
    }

    // ---------------------------------------------------------------- โหมดถ่ายรูป

    private fun startCapture(name: String) {
        mode = Mode.CAPTURE
        currentName = name
        shots.forEach { it.delete() }
        shots.clear()
        b.thumb1.setImageDrawable(null)
        b.thumb2.setImageDrawable(null)
        b.thumbs.visibility = View.VISIBLE
        b.captureBar.visibility = View.VISIBLE
        b.btnShutter.isEnabled = true
        updateCaptureStatus()
    }

    private fun updateCaptureStatus() {
        setStatus(
            "🆕 ${currentName}\nยังไม่ได้ถ่าย — ถ่ายรูปที่ ${shots.size + 1}/2",
            Color.parseColor("#FFD180")
        )
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (mode != Mode.CAPTURE || shots.size >= 2) return
        b.btnShutter.isEnabled = false

        val file = File(cacheDir, "tmp_${System.currentTimeMillis()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    shots.add(file)
                    showThumb(if (shots.size == 1) b.thumb1 else b.thumb2, file)
                    if (shots.size >= 2) saveShots() else {
                        b.btnShutter.isEnabled = true
                        updateCaptureStatus()
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    b.btnShutter.isEnabled = true
                    Toast.makeText(this@MainActivity, "ถ่ายรูปไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun showThumb(view: ImageView, file: File) {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        view.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath, opts))
    }

    private fun cancelCapture() {
        shots.forEach { it.delete() }
        shots.clear()
        backToScan("ยกเลิกแล้ว สแกน QR ใบถัดไปได้เลย", Color.WHITE)
    }

    /** ครบ 2 รูป: สร้างโฟลเดอร์ชื่อเดียวกับ QR แล้วเซฟรูปลงในที่เก็บงาน */
    private fun saveShots() {
        val name = currentName ?: return
        setStatus("กำลังบันทึก $name ...", Color.WHITE)
        val files = shots.toList()
        Thread {
            val error = try {
                val root = rootDir() ?: throw IllegalStateException("ไม่พบที่เก็บงาน")
                var dir = root.findFile(name)
                if (dir == null || !dir.isDirectory) dir = root.createDirectory(name)
                if (dir == null) throw IllegalStateException("สร้างโฟลเดอร์ไม่ได้")

                files.forEachIndexed { i, f ->
                    val fileName = "${name}_${i + 1}.jpg"
                    dir.findFile(fileName)?.delete()
                    val out = dir.createFile("image/jpeg", fileName)
                        ?: throw IllegalStateException("สร้างไฟล์รูปไม่ได้")
                    contentResolver.openOutputStream(out.uri)?.use { os ->
                        f.inputStream().use { it.copyTo(os) }
                    } ?: throw IllegalStateException("เขียนไฟล์ไม่ได้")
                }
                null
            } catch (e: Exception) {
                e.message ?: "บันทึกไม่สำเร็จ"
            }

            runOnUiThread {
                if (error == null) {
                    files.forEach { it.delete() }
                    shots.clear()
                    backToScan("✅ บันทึก $name แล้ว (2 รูป)\nสแกน QR ใบถัดไปได้เลย", Color.parseColor("#69F0AE"))
                } else {
                    // รูปยังอยู่ใน cache ให้กดถ่ายใหม่หรือยกเลิกได้
                    shots.clear()
                    b.btnShutter.isEnabled = true
                    setStatus("❌ บันทึกไม่สำเร็จ: $error\nถ่ายใหม่อีกครั้ง", Color.parseColor("#FF8A80"))
                }
            }
        }.start()
    }

    private fun backToScan(message: String, color: Int) {
        mode = Mode.SCAN
        currentName = null
        b.thumbs.visibility = View.GONE
        b.captureBar.visibility = View.GONE
        lastCode = null
        setStatus(message, color)
    }

    // ---------------------------------------------------------------- รายการงานที่ถ่ายแล้ว

    private fun showDoneList() {
        val root = rootDir()
        if (root == null) {
            Toast.makeText(this, "ยังไม่ได้เลือกที่เก็บงาน", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val items = root.listFiles()
                .filter { it.isDirectory }
                .mapNotNull { d ->
                    val n = d.name ?: return@mapNotNull null
                    val c = d.listFiles().count { it.isFile }
                    "$n  ($c รูป)"
                }
                .sorted()
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("ถ่ายแล้ว ${items.size} งาน")
                    .setItems(if (items.isEmpty()) arrayOf("(ยังไม่มี)") else items.toTypedArray(), null)
                    .setPositiveButton("ปิด", null)
                    .show()
            }
        }.start()
    }

    private fun setStatus(text: String, color: Int) {
        b.tvStatus.text = text
        b.tvStatus.setTextColor(color)
    }
}
