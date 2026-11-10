package com.expedition.app.features.auth

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onRegisterSuccess: (User) -> Unit,
    onNavigateToLogin: () -> Unit
) {
    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    val pendingUser by authManager.pendingVerificationUser.collectAsState()
    val deviceFingerprint = remember { authManager.getDeviceFingerprintPreview() }

    var email by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var accountType by remember { mutableStateOf(AccountType.REGULAR) }
    var isLoading by remember { mutableStateOf(false) }
    var verificationBannerMessage by remember { mutableStateOf<String?>(null) }

    // Animation states
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Decorative background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                )
        )

        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = tween(800)) + slideInVertically(animationSpec = tween(800)) { -20 }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "JOIN THE PACK",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Create & verify your rider identity",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Input Fields
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = tween(800, 200)) + slideInVertically(animationSpec = tween(800, 200)) { 20 }
                ) {
                    Column {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("Display Name") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email Address") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text("Confirm Password") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Account Type Selection
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = tween(800, 400)) + slideInVertically(animationSpec = tween(800, 400)) { 20 }
                ) {
                    Column {
                        Text(
                            "Account Type",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AccountTypeCard(
                                title = "Regular",
                                description = "For everyday riders (Requires Email Verification)",
                                icon = Icons.Default.Person,
                                isSelected = accountType == AccountType.REGULAR,
                                onClick = { accountType = AccountType.REGULAR },
                                modifier = Modifier.fillMaxWidth()
                            )
                            AccountTypeCard(
                                title = "Supervisor",
                                description = "For group ride leaders (Requires Email Verification)",
                                icon = Icons.Default.SupervisedUserCircle,
                                isSelected = accountType == AccountType.SUPERVISOR,
                                onClick = { accountType = AccountType.SUPERVISOR },
                                modifier = Modifier.fillMaxWidth()
                            )
                            AccountTypeCard(
                                title = "Developer (Device-Bound)",
                                description = "Hardware-verified via AndroidKeyStore ($deviceFingerprint)",
                                icon = Icons.Default.VerifiedUser,
                                isSelected = accountType == AccountType.DEVELOPER,
                                onClick = { accountType = AccountType.DEVELOPER },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Action Buttons
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = tween(800, 600)) + slideInVertically(animationSpec = tween(800, 600)) { 20 }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = {
                                if (password != confirmPassword) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Passwords do not match")
                                    }
                                    return@Button
                                }
                                scope.launch {
                                    isLoading = true
                                    val result = authManager.register(
                                        email = email,
                                        password = password,
                                        displayName = displayName,
                                        accountType = accountType
                                    )
                                    isLoading = false
                                    when (result) {
                                        is AuthResult.Success -> onRegisterSuccess(result.user)
                                        is AuthResult.VerificationRequired -> {
                                            verificationBannerMessage = result.message
                                        }
                                        is AuthResult.Error -> snackbarHostState.showSnackbar(result.message)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.medium,
                            enabled = !isLoading
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    "Register & Verify",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        TextButton(
                            onClick = onNavigateToLogin,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Already have an account? Login or use Device Key",
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }

    if (pendingUser != null) {
        AccountVerificationDialog(
            user = pendingUser!!,
            statusMessage = verificationBannerMessage ?: "Verification link sent to your email.",
            deviceFingerprint = deviceFingerprint,
            onCheckEmailVerified = {
                scope.launch {
                    when (val res = authManager.checkVerificationStatus(allowDevBypass = false)) {
                        is AuthResult.Success -> onRegisterSuccess(res.user)
                        is AuthResult.VerificationRequired -> verificationBannerMessage = res.message
                        is AuthResult.Error -> snackbarHostState.showSnackbar(res.message)
                    }
                }
            },
            onResendEmail = {
                scope.launch {
                    when (val res = authManager.sendEmailVerification()) {
                        is AuthResult.VerificationRequired -> verificationBannerMessage = res.message
                        is AuthResult.Error -> snackbarHostState.showSnackbar(res.message)
                        else -> {}
                    }
                }
            },
            onVerifyWithHardwareKey = {
                scope.launch {
                    when (val res = authManager.checkVerificationStatus(allowDevBypass = true)) {
                        is AuthResult.Success -> onRegisterSuccess(res.user)
                        is AuthResult.Error -> snackbarHostState.showSnackbar(res.message)
                        else -> {}
                    }
                }
            },
            onDismiss = {
                authManager.logout()
                verificationBannerMessage = null
            }
        )
    }
}

@Composable
fun AccountTypeCard(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent

    Card(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .border(2.dp, borderColor, MaterialTheme.shapes.medium)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
