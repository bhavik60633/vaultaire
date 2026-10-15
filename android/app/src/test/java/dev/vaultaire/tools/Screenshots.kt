package dev.vaultaire.tools

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import dev.vaultaire.core.CardFinish
import dev.vaultaire.core.CardInput
import dev.vaultaire.core.CardKind
import androidx.test.core.app.ApplicationProvider
import dev.vaultaire.core.BankAccountInput
import dev.vaultaire.core.PinInput
import dev.vaultaire.core.PinPurpose
import dev.vaultaire.core.SecretInput
import dev.vaultaire.ui.FakeGateway
import dev.vaultaire.ui.RealVault
import dev.vaultaire.ui.VaultaireApp
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Not a test: renders each screen to a PNG so the owner can compare it with the design mockup
 * before installing anything. Runs only with `./gradlew :app:testDebugUnitTest -Pscreenshots`;
 * the images land in `app/build/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Screenshots {

    @get:Rule
    val compose = createComposeRule()

    private val out: String? = System.getProperty("vaultaire.screenshots")

    @Before
    fun onlyWhenAsked() {
        assumeTrue(out != null)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out!!, name).parentFile?.mkdirs()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()

    @Test
    fun onboarding() {
        val gateway = FakeGateway()
        compose.setContent { VaultaireApp(gateway, testBuild = false) }
        save("1-welcome")
        click("create")
        save("2-password-empty")
        compose.onNodeWithTag("password").performTextInput("password1234")
        compose.onNodeWithTag("confirm").performTextInput("password1234")
        gateway.weakPassword = true
        click("continue")
        save("3-password-weak")
        gateway.weakPassword = false
        click("continue")
        save("4-recovery-key")
        click("writtenDown")
        click("continue")
        save("5-fingerprint")
    }

    @Test
    fun lockAndHome() {
        val gateway = FakeGateway(exists = true)
        gateway.failures = 3
        compose.setContent { VaultaireApp(gateway, testBuild = false) }
        save("6-lock")
        click("usePassword")
        save("7-password")
        compose.onNodeWithTag("password").performTextInput("a completely different passphrase 7")
        click("unlock")
        save("8-password-wrong")
        compose.onNodeWithTag("password").performTextInput("x")
        click("back")
        click("unlockFingerprint")
        save("9-home")
    }

    @Test
    fun testBuildAndRefusal() {
        val gateway = FakeGateway(refusalValue = dev.vaultaire.security.Refusal.NO_SCREEN_LOCK)
        compose.setContent { VaultaireApp(gateway, testBuild = true) }
        save("10-refusal-test-build")
    }

    @Test
    fun vault() = vaultShots("")

    /** The same screens with the phone's text made larger, as many people set it. */
    @Test
    fun vaultLargeText() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        vaultShots("large-")
    }

    private fun vaultShots(prefix: String) {
        val dir = Files.createTempDirectory("vaultaire-shots").toFile()
        val vault = RealVault(ApplicationProvider.getApplicationContext(), dir)
        try {
            val ids = vault.fixture()
            val session = vault.session()
            session.setFavourite(ids[0], true)
            session.setFavourite(ids[5], true)
            session.addPin(
                PinInput(PinPurpose.ATM_PIN, "SBI Cashback ATM PIN", SecretInput.Set("4321".toCharArray()), linkedTo = ids[0]),
                RealVault.NOW,
            )
            session.addBankAccount(
                BankAccountInput(
                    bankId = "in.sbi", holder = "ASHA RAO",
                    accountNumber = SecretInput.Set("00000012345678".toCharArray()), ifsc = "SBIN0001234",
                ),
                RealVault.NOW,
            )
            compose.setContent { VaultaireApp(vault, testBuild = false) }
            save(prefix + "11-home")
            click("bank:in.sbi")
            save(prefix + "12-bank-sbi")
            click("filter:credit")
            save(prefix + "13-bank-sbi-credit")
            click("entry:${ids[0].hex}")
            save(prefix + "14-card")
            click("reveal:card.number")
            click("copy:card.number")
            save(prefix + "15-card-revealed-copied")
            click("back")
            click("back")
            click("addCard")
            save(prefix + "16-add-card")
            click("back")
            click("settings")
            save(prefix + "17-settings")
        } finally {
            vault.lock()
            dir.deleteRecursively()
        }
    }

    /**
     * Frames of the card for the owner's preview: drifting, tilted by a finger, turned over and
     * back, the finishes, and the number shown after a fingerprint. Rendered at twice the density.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun cardAnimation() {
        val dir = Files.createTempDirectory("vaultaire-card").toFile()
        val vault = RealVault(ApplicationProvider.getApplicationContext(), dir)
        try {
            val id = vault.session().addCard(
                CardInput(
                    bankId = "in.hdfc", kind = CardKind.CREDIT, product = "HDFC Millennia", holder = "ASHA RAO",
                    number = SecretInput.Set("5555555555554444".toCharArray()), expiry = "08/29",
                    network = "Mastercard", cvv = SecretInput.Set("321".toCharArray()),
                ),
                RealVault.NOW,
            )
            vault.session().addPin(
                PinInput(PinPurpose.ATM_PIN, "HDFC card PIN", SecretInput.Set("8642".toCharArray()), linkedTo = id),
                RealVault.NOW,
            )
            compose.setContent { VaultaireApp(vault, testBuild = false) }
            click("bank:in.hdfc")
            click("entry:${id.hex}")
            compose.mainClock.autoAdvance = false
            var frame = 0
            fun shoot(ms: Long, count: Int) = repeat(count) {
                compose.mainClock.advanceTimeBy(ms)
                save("card/%03d".format(frame++))
            }
            shoot(80, 30)
            compose.onNodeWithTag("cardFace").performTouchInput { down(center) }
            repeat(10) {
                compose.onNodeWithTag("cardFace").performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(-9f, 4f)) }
                shoot(40, 1)
            }
            compose.onNodeWithTag("cardFace").performTouchInput { up() }
            shoot(60, 18)
            click("turnCard")
            shoot(40, 22)
            shoot(80, 14)
            click("turnCard")
            shoot(40, 22)
            for (f in listOf(CardFinish.CHAMPAGNE, CardFinish.PLATINUM, CardFinish.IVORY, CardFinish.TITANIUM, CardFinish.OBSIDIAN)) {
                click("finish:${f.name.lowercase()}")
                shoot(80, 9)
            }
            click("cardNumber")
            shoot(80, 20)
        } finally {
            vault.lock()
            dir.deleteRecursively()
        }
    }
}
