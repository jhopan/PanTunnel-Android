package id.jhopanstore.pantunnel

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import id.jhopanstore.pantunnel.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Branded splash: logo and text fade+slide in, "By JhopanStore" lands last, then hand over
 * to [MainActivity]. The hand-over waits for the whole intro so the brand beat is always
 * visible instead of racing the activity launch.
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playIntro()
        handOverAfterIntro()
    }

    private fun playIntro() {
        // Start hidden, offset down and slightly small, then settle in.
        listOf(binding.splashLogo, binding.splashTitle, binding.splashTagline, binding.splashBy)
            .forEach { view ->
                view.alpha = 0f
                view.translationY = 24f
            }
        binding.splashLogo.scaleX = 0.8f
        binding.splashLogo.scaleY = 0.8f

        binding.splashLogo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(80L)
            .setDuration(420L)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        fadeUp(binding.splashTitle, startDelay = 220L, duration = 320L)
        fadeUp(binding.splashTagline, startDelay = 330L, duration = 320L)
        fadeUp(binding.splashBy, startDelay = 520L, duration = 380L)
    }

    private fun fadeUp(view: View, startDelay: Long, duration: Long) {
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(startDelay)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun handOverAfterIntro() {
        lifecycleScope.launch {
            delay(INTRO_TOTAL_MS)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }

    companion object {
        /** Last animation ends at 520 + 380 = 900 ms; hold a beat so it stays readable. */
        private const val INTRO_TOTAL_MS = 1_150L
    }
}
