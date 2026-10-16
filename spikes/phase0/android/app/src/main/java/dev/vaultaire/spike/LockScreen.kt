package dev.vaultaire.spike

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ----------------------------------------------------
// LOCK SCREEN (Guilloché Rosette & Biometric Trigger)
// ----------------------------------------------------
@Composable
fun LockScreen(
    statusMessage: String?,
    onUnlockWithBiometrics: () -> Unit,
    onOpenPasswordPrompt: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onLoadDemoVault: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = CircleShape,
                color = ColorAccentSoft,
                border = BorderStroke(1.dp, ColorHairlineGold)
            ) {
                Text(
                    text = "ZERO-KNOWLEDGE ENCRYPTED",
                    color = ColorAccentGold,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = "VAULTAIRE",
                color = ColorTextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 6.sp
            )
            Text(
                text = "Hardware anchored. Air-gapped zero knowledge.",
                color = ColorTextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Box(
            modifier = Modifier.size(260.dp),
            contentAlignment = Alignment.Center
        ) {
            GuillocheRosetteCanvas()

            Surface(
                modifier = Modifier
                    .size(68.dp)
                    .clickable { onUnlockWithBiometrics() },
                shape = CircleShape,
                color = ColorElevated,
                border = BorderStroke(1.5.dp, ColorAccentGold)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    VaultIcon(
                        pathData = VaultIcons.LOCK,
                        tint = ColorAccentGold,
                        size = 26.dp
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (statusMessage != null) {
                Text(
                    text = statusMessage,
                    color = ColorCrit,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
            }

            Button(
                onClick = onUnlockWithBiometrics,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorAccentGold,
                    contentColor = ColorBg
                )
            ) {
                Text(
                    text = "Unlock with Biometrics",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = onOpenPasswordPrompt) {
                Text(
                    text = "Unlock with master password or decoy PIN",
                    color = ColorTextSecondary,
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpenDiagnostics) {
                    Text(
                        text = "Diagnostics & Specs",
                        color = ColorTextTertiary,
                        fontSize = 11.sp
                    )
                }

                if (onLoadDemoVault != null) {
                    Text(
                        text = "·",
                        color = ColorTextTertiary,
                        fontSize = 12.sp
                    )
                    TextButton(onClick = onLoadDemoVault) {
                        Text(
                            text = "Seed Demo Vault",
                            color = ColorAccentGold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
