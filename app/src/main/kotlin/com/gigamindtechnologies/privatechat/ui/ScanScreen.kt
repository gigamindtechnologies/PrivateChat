package com.gigamindtechnologies.privatechat.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigamindtechnologies.privatechat.TorStatus
import com.gigamindtechnologies.privatechat.core.node.Node
import java.util.concurrent.Executors

@Composable
fun ScanScreen(node: Node?, tor: TorStatus, onPaired: (String) -> Unit, onBack: () -> Unit) {
    if (node == null) {
        WaitingForTor("Scan a code", tor, onBack)
        return
    }
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }

    val state by node.pairing.collectAsStateWithLifecycle()
    DisposableEffect(node) {
        node.cancelPairing()
        onDispose { if (node.pairing.value !is Node.Pairing.Paired) node.cancelPairing() }
    }
    LaunchedEffect(state) {
        (state as? Node.Pairing.Paired)?.let {
            node.cancelPairing()
            onPaired(it.contactId)
        }
    }

    Scaffold(topBar = { BackTopBar("Scan a code", onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                Node.Pairing.Idle -> if (hasCamera) {
                    Text("Point your camera at their PrivateChat code", textAlign = TextAlign.Center)
                    CameraScanner(onCode = node::scanInvite)
                } else {
                    Text("PrivateChat needs the camera to scan codes. Images never leave your phone.", textAlign = TextAlign.Center)
                    PrimaryAction("Allow camera") { permission.launch(Manifest.permission.CAMERA) }
                }
                Node.Pairing.Connecting -> {
                    CircularProgressIndicator()
                    Text("Connecting privately to their phone…", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "This usually takes 10–60 seconds. Ask them to keep their code on screen.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = node::cancelPairing) { Text("Cancel") }
                }
                is Node.Pairing.AwaitingApproval -> {
                    Text("Show them your screen", style = MaterialTheme.typography.titleLarge)
                    SafetyCodeView(s.safetyCode)
                    Text(
                        "Their phone shows symbols too. When they match, they tap \"They match\".",
                        textAlign = TextAlign.Center,
                    )
                    CircularProgressIndicator()
                }
                is Node.Pairing.Failed -> {
                    Text(failureText(s.reason), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    PrimaryAction("Scan again") { node.cancelPairing() }
                }
                else -> CircularProgressIndicator()
            }
        }
    }
}

private fun failureText(reason: Node.FailReason) = when (reason) {
    Node.FailReason.INVALID_CODE -> "That isn't a PrivateChat code."
    Node.FailReason.UNREACHABLE -> "Couldn't reach their phone. Make sure their code is still on screen and both of you are online."
    Node.FailReason.REJECTED -> "They declined the request."
    Node.FailReason.EXPIRED -> "That code has expired. Ask them for a new one."
    Node.FailReason.ERROR -> "Something went wrong. Please try again."
}

@Composable
private fun CameraScanner(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    DisposableEffect(Unit) {
        onDispose {
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }
    Box(Modifier.widthIn(max = 400.dp).fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val mainExecutor = ContextCompat.getMainExecutor(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, QrAnalyzer { text -> mainExecutor.execute { onCode(text) } }) }
                    provider.unbindAll()
                    runCatching { provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                }, mainExecutor)
                previewView
            },
        )
    }
}
