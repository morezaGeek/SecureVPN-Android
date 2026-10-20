package com.vpnapp.ads

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/** Refreshes Google's consent state at startup and gates all ad requests. */
object AdsConsent {
    private const val TAG = "AdsConsent"
    private var consentInformation: ConsentInformation? = null

    var canRequestAds by mutableStateOf(false)
        private set

    fun refresh(activity: Activity) {
        val information = UserMessagingPlatform.getConsentInformation(activity)
        consentInformation = information
        information.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    canRequestAds = information.canRequestAds()
                    if (error != null) Log.w(TAG, "Consent form was unavailable: ${error.message}")
                }
            },
            { error ->
                // UMP retains the prior consent state when refresh fails; never assume consent.
                canRequestAds = information.canRequestAds()
                Log.w(TAG, "Consent information refresh failed: ${error.message}")
            }
        )
    }

    fun showPrivacyOptions(activity: Activity) {
        val information = consentInformation
            ?: UserMessagingPlatform.getConsentInformation(activity).also { consentInformation = it }
        if (information.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        ) {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
                canRequestAds = information.canRequestAds()
                if (error != null) Log.w(TAG, "Privacy options form was unavailable: ${error.message}")
            }
        } else {
            Toast.makeText(activity, "No additional ad privacy choices are currently required.", Toast.LENGTH_SHORT).show()
        }
    }
}
