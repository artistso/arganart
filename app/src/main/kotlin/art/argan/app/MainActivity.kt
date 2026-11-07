package art.argan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import art.argan.design.ArganTheme
import art.argan.feature.canvas.CanvasScreen

/**
 * The app's single activity.
 *
 * At P0 it launches straight to the empty infinite canvas. Navigation exists as
 * a seam (P1 adds tools, P2 the ribbon) but there is exactly one destination, so
 * no navigation library is introduced yet.
 */
public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArganTheme {
                CanvasScreen()
            }
        }
    }
}
