package com.yugahashimoto.andcode.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.nsd.NsdManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.security.ConnectionQrPayload
import com.yugahashimoto.andcode.runtime.remote.VpsBootstrapResult
import com.yugahashimoto.andcode.ui.theme.AndCodeTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val DISCOVERY_TIMEOUT_MILLIS = 5_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteConnectionScreen(
    onTestConnection: suspend (ConnectionFormState) -> Result<OpenCodeHealth>,
    onSaveConnection: (ConnectionFormState) -> Unit,
    onBack: () -> Unit,
    onConnected: () -> Unit,
    onSetupVps: (suspend (ConnectionFormState, (String) -> Unit) -> Result<VpsBootstrapResult>)? = null,
) {
    var form by remember { mutableStateOf(ConnectionFormState(mode = ConnectionMode.VPS_SSH)) }
    var passwordVisible by remember { mutableStateOf(false) }
    var discoveryDialogOpen by remember { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    var showLogs by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val qrScanLauncher =
        rememberLauncherForActivityResult(ScanContract()) { result ->
            val text = result.contents ?: return@rememberLauncherForActivityResult
            ConnectionQrPayload.parse(text)?.let { payload ->
                form =
                    ConnectionFormState(
                        mode = ConnectionMode.DIRECT_HTTP,
                        name = payload.name.orEmpty(),
                        baseUrl = payload.url.orEmpty(),
                        username = payload.username?.takeIf { it.isNotBlank() } ?: "opencode",
                        password = payload.password.orEmpty(),
                        allowInsecureLan = payload.insecure,
                    )
            }
        }

    fun startLanDiscovery() {
        discoveryDialogOpen = true
        discoveredServers = emptyList()
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            isDiscovering = false
            return
        }
        isDiscovering = true
        scope.launch {
            runCatching {
                withTimeoutOrNull(DISCOVERY_TIMEOUT_MILLIS) {
                    LanDiscovery(nsdManager).discover().collect { server ->
                        discoveredServers = (discoveredServers + server).distinctBy { it.host to it.port }
                    }
                }
            }
            isDiscovering = false
        }
    }

    fun testConnection() {
        scope.launch {
            form = form.copy(isTesting = true, testMessage = null)
            onTestConnection(form).fold(
                onSuccess = { health ->
                    form =
                        form.copy(
                            isTesting = false,
                            testSucceeded = health.healthy,
                            testMessage =
                                if (health.healthy) {
                                    health.version.takeIf { it.isNotBlank() }?.let { "Conectado: $it" } ?: "Conexión exitosa"
                                } else {
                                    context.getString(R.string.remote_connection_unhealthy)
                                },
                        )
                },
                onFailure = { error ->
                    form =
                        form.copy(
                            isTesting = false,
                            testSucceeded = false,
                            testMessage = error.message ?: context.getString(R.string.remote_connection_failed),
                        )
                },
            )
        }
    }

    fun startVpsSetup() {
        if (onSetupVps == null) return
        scope.launch {
            form =
                form.copy(
                    isBootstrapping = true,
                    bootstrapProgress = context.getString(R.string.vps_setting_up),
                    bootstrapLogs = emptyList(),
                    testMessage = null,
                    testSucceeded = false,
                )
            onSetupVps(form) { logLine ->
                form =
                    form.copy(
                        bootstrapProgress = logLine,
                        bootstrapLogs = form.bootstrapLogs + logLine,
                    )
            }.fold(
                onSuccess = { result ->
                    val successMsg = "OpenCode v${result.version} listo en ${result.distro} (${result.arch})"
                    form =
                        form.copy(
                            isBootstrapping = false,
                            testSucceeded = true,
                            testMessage = successMsg,
                        )
                    onSaveConnection(form)
                    onConnected()
                },
                onFailure = { error ->
                    form =
                        form.copy(
                            isBootstrapping = false,
                            testSucceeded = false,
                            testMessage = error.message ?: "Error configurando VPS",
                        )
                },
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.remote_connection_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
        bottomBar = {
            RemoteConnectionBottomBar(
                form = form,
                onTest = ::testConnection,
                onSetup = ::startVpsSetup,
                onSave = {
                    onSaveConnection(form)
                    onConnected()
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TabRow(
                selectedTabIndex = if (form.mode == ConnectionMode.VPS_SSH) 0 else 1,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                Tab(
                    selected = form.mode == ConnectionMode.VPS_SSH,
                    onClick = { form = form.copy(mode = ConnectionMode.VPS_SSH, testMessage = null) },
                    text = { Text(stringResource(R.string.vps_tab_ssh), fontWeight = FontWeight.Medium) },
                    icon = { Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                Tab(
                    selected = form.mode == ConnectionMode.DIRECT_HTTP,
                    onClick = { form = form.copy(mode = ConnectionMode.DIRECT_HTTP, testMessage = null) },
                    text = { Text(stringResource(R.string.vps_tab_direct), fontWeight = FontWeight.Medium) },
                    icon = { Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }

            if (form.mode == ConnectionMode.VPS_SSH) {
                VpsSshForm(
                    form = form,
                    passwordVisible = passwordVisible,
                    onTogglePassword = { passwordVisible = !passwordVisible },
                    onFormChange = { form = it },
                )

                if (form.isBootstrapping || form.bootstrapLogs.isNotEmpty()) {
                    VpsSetupProgressCard(
                        form = form,
                        showLogs = showLogs,
                        onToggleLogs = { showLogs = !showLogs },
                    )
                }
            } else {
                DirectHttpForm(
                    form = form,
                    passwordVisible = passwordVisible,
                    onTogglePassword = { passwordVisible = !passwordVisible },
                    onFormChange = { form = it },
                    onScanQr = {
                        qrScanLauncher.launch(
                            ScanOptions().setBeepEnabled(false).setOrientationLocked(false),
                        )
                    },
                    onDiscoverLan = ::startLanDiscovery,
                )
            }

            form.testMessage?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color =
                        if (form.testSucceeded) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        } else {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                        },
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(14.dp),
                        color =
                            if (form.testSucceeded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (discoveryDialogOpen) {
        DiscoveryDialog(
            isDiscovering = isDiscovering,
            discoveredServers = discoveredServers,
            onDismiss = { discoveryDialogOpen = false },
            onSelectServer = { server ->
                form =
                    form.copy(
                        name = form.name.ifBlank { server.name },
                        baseUrl = server.baseUrl,
                        testSucceeded = false,
                        testMessage = null,
                    )
                discoveryDialogOpen = false
            },
        )
    }
}

@Composable
private fun VpsSshForm(
    form: ConnectionFormState,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    onFormChange: (ConnectionFormState) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.vps_setup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = form.name,
            onValueChange = { onFormChange(form.copy(name = it, testSucceeded = false, testMessage = null)) },
            label = { Text(stringResource(R.string.connection_name)) },
            placeholder = { Text("Mi VPS") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )

        OutlinedTextField(
            value = form.sshHost,
            onValueChange = { onFormChange(form.copy(sshHost = it, testSucceeded = false, testMessage = null)) },
            label = { Text(stringResource(R.string.vps_ssh_host)) },
            placeholder = { Text("85.192.20.22") },
            leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = form.sshPort,
                onValueChange = { onFormChange(form.copy(sshPort = it, testSucceeded = false, testMessage = null)) },
                label = { Text(stringResource(R.string.vps_ssh_port)) },
                placeholder = { Text("22") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = form.sshUser,
                onValueChange = { onFormChange(form.copy(sshUser = it, testSucceeded = false, testMessage = null)) },
                label = { Text(stringResource(R.string.vps_ssh_user)) },
                placeholder = { Text("root") },
                modifier = Modifier.weight(1.5f),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        }

        OutlinedTextField(
            value = form.sshPassword,
            onValueChange = { onFormChange(form.copy(sshPassword = it, testSucceeded = false, testMessage = null)) },
            label = { Text(stringResource(R.string.vps_ssh_password)) },
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onTogglePassword) {
                    Icon(
                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.cd_toggle_password),
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )

        OutlinedTextField(
            value = form.sshKey,
            onValueChange = { onFormChange(form.copy(sshKey = it, testSucceeded = false, testMessage = null)) },
            label = { Text(stringResource(R.string.vps_ssh_key)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            shape = RoundedCornerShape(14.dp),
        )
    }
}

@Composable
private fun VpsSetupProgressCard(
    form: ConnectionFormState,
    showLogs: Boolean,
    onToggleLogs: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (form.isBootstrapping) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (form.testSucceeded) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = form.bootstrapProgress ?: stringResource(R.string.vps_setting_up),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            if (form.isBootstrapping) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            TextButton(
                onClick = onToggleLogs,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (showLogs) stringResource(R.string.vps_hide_logs) else stringResource(R.string.vps_view_logs))
            }

            AnimatedVisibility(visible = showLogs) {
                SelectionContainer {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(180.dp).verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = form.bootstrapLogs.joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectHttpForm(
    form: ConnectionFormState,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    onFormChange: (ConnectionFormState) -> Unit,
    onScanQr: () -> Unit,
    onDiscoverLan: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CompactStepRow(
                number = 1,
                title = stringResource(R.string.remote_step1_title),
                description = stringResource(R.string.remote_step1_desc),
            )
            val serveCommand = stringResource(R.string.remote_serve_command)
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.remote_copy_command), serveCommand))
                            Toast.makeText(context, context.getString(R.string.remote_command_copied), Toast.LENGTH_SHORT).show()
                        },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.remote_serve_command),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.remote_copy_command),
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            CompactStepRow(
                number = 2,
                title = stringResource(R.string.remote_step2_title),
                description = stringResource(R.string.remote_step2_desc),
            )
            CompactStepRow(
                number = 3,
                title = stringResource(R.string.remote_step3_title),
                description = stringResource(R.string.remote_step3_desc),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = form.name,
                onValueChange = {
                    onFormChange(form.copy(name = it, testSucceeded = false, testMessage = null))
                },
                label = { Text(stringResource(R.string.connection_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            val urlInvalid = form.baseUrl.isNotBlank() && form.normalizedUrl == null
            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = {
                    onFormChange(form.copy(baseUrl = it, testSucceeded = false, testMessage = null))
                },
                label = { Text(stringResource(R.string.server_url)) },
                placeholder = { Text("192.168.1.10:4096") },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = stringResource(R.string.cd_server_url)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = urlInvalid,
                supportingText =
                    if (urlInvalid) {
                        { Text(stringResource(R.string.remote_url_invalid)) }
                    } else {
                        null
                    },
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = form.username,
                onValueChange = {
                    onFormChange(form.copy(username = it, testSucceeded = false, testMessage = null))
                },
                label = { Text(stringResource(R.string.username)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = form.password,
                onValueChange = {
                    onFormChange(form.copy(password = it, testSucceeded = false, testMessage = null))
                },
                label = { Text(stringResource(R.string.password)) },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = stringResource(R.string.cd_password)) },
                trailingIcon = {
                    IconButton(onClick = onTogglePassword) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(R.string.cd_toggle_password),
                        )
                    }
                },
                visualTransformation =
                    if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onScanQr,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = stringResource(R.string.cd_scan_qr),
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_via_qr), maxLines = 1)
            }
            OutlinedButton(
                onClick = onDiscoverLan,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.WifiFind,
                    contentDescription = stringResource(R.string.cd_lan_discovery),
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.discover_on_lan), maxLines = 1)
            }
        }
    }
}

@Composable
private fun DiscoveryDialog(
    isDiscovering: Boolean,
    discoveredServers: List<DiscoveredServer>,
    onDismiss: () -> Unit,
    onSelectServer: (DiscoveredServer) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discovered_servers_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isDiscovering) {
                    Text(
                        stringResource(R.string.discovering_servers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (discoveredServers.isEmpty()) {
                    Text(
                        stringResource(R.string.no_servers_found),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                discoveredServers.forEach { server ->
                    Surface(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelectServer(server) },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(server.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                server.baseUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun CompactStepRow(
    number: Int,
    title: String,
    description: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RemoteConnectionBottomBar(
    form: ConnectionFormState,
    onTest: () -> Unit,
    onSetup: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (form.mode == ConnectionMode.VPS_SSH) {
                Button(
                    onClick = if (form.testSucceeded) onSave else onSetup,
                    enabled = if (form.testSucceeded) true else form.canSave && !form.isBootstrapping && !form.isTesting,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (form.isBootstrapping) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (form.testSucceeded) Icons.Default.CheckCircle else Icons.Default.RocketLaunch,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (form.testSucceeded) {
                            stringResource(R.string.remote_save_connection_button)
                        } else {
                            stringResource(R.string.vps_setup_button)
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (!form.testSucceeded) {
                    OutlinedButton(
                        onClick = onTest,
                        enabled = form.canSave && !form.isTesting && !form.isBootstrapping,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        if (form.isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.vps_test_button), fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                Button(
                    onClick = if (form.testSucceeded) onSave else onTest,
                    enabled = if (form.testSucceeded) true else form.canSave && !form.isTesting,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (form.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.NetworkCheck,
                            contentDescription = stringResource(R.string.cd_test_connection),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (form.testSucceeded) {
                            stringResource(R.string.remote_save_connection_button)
                        } else {
                            stringResource(R.string.remote_test_connection_button)
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteConnectionScreenPreview() {
    AndCodeTheme {
        RemoteConnectionScreen(
            onTestConnection = { Result.success(OpenCodeHealth(true, "1.0.0")) },
            onSaveConnection = {},
            onBack = {},
            onConnected = {},
        )
    }
}
