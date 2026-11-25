package ru.podmoskovye.transport

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.podmoskovye.transport.data.DataSource
import ru.podmoskovye.transport.data.Prefs
import ru.podmoskovye.transport.ui.MainContent
import ru.podmoskovye.transport.ui.Mode
import ru.podmoskovye.transport.ui.Pal
import ru.podmoskovye.transport.ui.TransportTheme

/**
 * Рендер настоящего интерфейса приложения на JVM (Robolectric + Roborazzi).
 * Это тот же Compose-код, что и в APK; снимки делаются без эмулятора.
 * Подложка карты в тесте не скачивается из сети, поэтому тайлы подменены
 * тёмным цветом — сам интерфейс, линии, станции и подписи настоящие.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun stubImages() {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept({ true }, ColorDrawable(AndroidColor.rgb(26, 31, 42)))
            .build()
        val loader = ImageLoader.Builder(context).components { add(engine) }.build()
        Coil.setImageLoader(loader)
    }

    private fun render(name: String, mode: Mode = Mode.MAP, place: String? = null) {
        val network = DataSource.loadNetwork(context)
        val scheme = DataSource.loadScheme(context)
        val prefs = Prefs(context)
        composeRule.setContent {
            TransportTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Pal.Bg) {
                    MainContent(
                        net = network,
                        scheme = scheme,
                        prefs = prefs,
                        context = context,
                        initialMode = mode,
                        initialPlace = place,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // карта и схема готовят данные в фоне — даём им завершиться
        Thread.sleep(1200)
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    fun mapScreen() {
        render("01-map")
    }

    @Test
    fun schemeScreen() {
        render("02-scheme", mode = Mode.SCHEME)
    }

    @Test
    fun stationCard() {
        render("03-station-card", place = "Окружная")
    }
}
