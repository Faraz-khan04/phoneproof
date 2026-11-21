package app.phoneproof.ads

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import app.phoneproof.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ads policy for PhoneProof (see docs/play-compliance.md):
 *  - Consent (UMP) is gathered before any ad request.
 *  - Banner only on Home. Never in the test wizard, on a report, or anywhere in meet-up mode.
 *  - One interstitial after a full check finishes, before the report opens (a natural break),
 *    at most once every 5 minutes. Never after a meet-up check.
 *  - Ad unit IDs in BuildConfig are Google's TEST IDs until you replace them for release.
 */
object Ads {
    private val started = AtomicBoolean(false)
    /** Compose state so the Home banner appears as soon as the SDK is ready. */
    var ready by mutableStateOf(false); private set
    private var interstitial: InterstitialAd? = null
    private var lastShownMs = 0L
    private const val MIN_GAP_MS = 5 * 60_000L

    /** Call from the activity's onCreate. Shows the consent form only where required (EEA/UK etc). */
    fun start(activity: Activity) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                if (info.canRequestAds()) init(activity)
            }
        }, { _ -> if (info.canRequestAds()) init(activity) })
        if (info.canRequestAds()) init(activity) // consent from a previous session
    }

    /** True when the privacy options entry must be shown in Settings/Help (UMP requirement). */
    fun privacyOptionsRequired(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus ==
            com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity) = UserMessagingPlatform.showPrivacyOptionsForm(activity) { }

    private fun init(context: Context) {
        if (!started.compareAndSet(false, true)) return
        MobileAds.initialize(context.applicationContext) {
            ready = true
            preload(context.applicationContext)
        }
    }

    /** Preload while the seller is running tests so the ad is ready at the end. */
    fun preload(context: Context) {
        if (!ready || interstitial != null) return
        InterstitialAd.load(context, BuildConfig.AD_INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { interstitial = null }
            })
    }

    /** Shows the interstitial if one is loaded and allowed; [then] always runs exactly once. */
    fun showAfterCheck(activity: Activity, then: () -> Unit) {
        val ad = interstitial
        val now = System.currentTimeMillis()
        if (ad == null || now - lastShownMs < MIN_GAP_MS) { then(); return }
        interstitial = null
        var done = false
        fun finish() { if (!done) { done = true; preload(activity.applicationContext); then() } }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = finish()
            override fun onAdFailedToShowFullScreenContent(error: AdError) = finish()
        }
        lastShownMs = now
        ad.show(activity)
    }
}

/** Anchored adaptive banner for the Home screen only. Renders nothing until ads are ready. */
@Composable
fun HomeBanner() {
    if (!Ads.ready) return
    val widthDp = LocalConfiguration.current.screenWidthDp
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
                adUnitId = BuildConfig.AD_BANNER_ID
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { it.destroy() },
    )
}
