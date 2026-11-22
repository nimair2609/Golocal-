package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.BuildConfig
import com.example.R
import com.example.data.repository.GolocalRepository
import com.example.data.repository.SampleRiderRepository
import com.example.rider.RiderActivity
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RideRequestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        if (action != ACTION_ACCEPT_RIDE && action != ACTION_DECLINE_RIDE) return

        val bookingId = intent.getStringExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID).orEmpty().trim()
        val isTestRide = intent.getBooleanExtra(NotificationDeepLinkExtras.EXTRA_IS_TEST_RIDE, false)
        val isSampleRide = intent.getBooleanExtra(NotificationDeepLinkExtras.EXTRA_IS_SAMPLE_RIDE, false)
        if (bookingId.isBlank()) return

        RideRequestAlertController.stopAlert(context, bookingId)

        if ((isTestRide || isSampleRide) && !BuildConfig.ENABLE_TEST_SANDBOX) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        receiverScope.launch {
            try {
                if (FirebaseApp.getApps(appContext).isEmpty()) return@launch
                val uid = Firebase.auth.currentUser?.uid.orEmpty()
                if (uid.isBlank()) return@launch
                val dbId = appContext.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(dbId)
                val repo = GolocalRepository(db)

                if (action == ACTION_DECLINE_RIDE) {
                    if (isSampleRide) {
                        SampleRiderRepository(db).rejectSampleRide(bookingId)
                    } else {
                        repo.rejectOpenBooking(bookingId, reason = "declined")
                    }
                    return@launch
                }

                // ACTION_ACCEPT_RIDE
                if (isSampleRide) {
                    val sampleRepo = SampleRiderRepository(db)
                    val sampleProfile = sampleRepo.getSampleRiderOnce(uid)
                    val sampleRide = sampleRepo.getSampleRideOnce(bookingId)
                    if (sampleProfile == null || sampleRide == null) {
                        showToast(appContext, "Sample ride is no longer available.")
                        return@launch
                    }
                    sampleRepo.acceptSampleRide(sampleRide, sampleProfile)
                        .onSuccess { launchRiderHome(appContext, bookingId, isTestRide = false, isSampleRide = true) }
                        .onFailure { showToast(appContext, it.localizedMessage ?: "Unable to accept sample ride.") }
                    return@launch
                }

                val booking = repo.getBookingById(bookingId)
                if (booking == null || booking.status != "searching") {
                    showToast(appContext, "This ride is no longer available.")
                    return@launch
                }

                if (isTestRide) {
                    val testProfile = repo.getTestRiderProfileOnce(uid)
                    if (testProfile == null || testProfile.status != "approved") {
                        showToast(appContext, "Approved Test Rider profile required.")
                        return@launch
                    }
                    repo.claimTestBookingByTestRider(booking = booking, riderProfile = testProfile)
                        .onSuccess { launchRiderHome(appContext, bookingId, isTestRide = true, isSampleRide = false) }
                        .onFailure { showToast(appContext, it.localizedMessage ?: "Unable to claim Test Ride.") }
                } else {
                    val driverProfile = repo.getDriverProfileOnce(uid)
                    if (driverProfile == null || driverProfile.verificationStatus != "verified") {
                        showToast(appContext, "Verified driver profile required to accept rides.")
                        return@launch
                    }
                    repo.acceptBookingAtomically(
                        booking = booking,
                        driver = driverProfile,
                        fareInr = booking.estimatedFareInr
                    ).onSuccess {
                        launchRiderHome(appContext, bookingId, isTestRide = false, isSampleRide = false)
                    }.onFailure { err ->
                        showToast(appContext, err.localizedMessage ?: "This ride was already taken.")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error handling notification ride action=$action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun launchRiderHome(
        context: Context,
        bookingId: String,
        isTestRide: Boolean,
        isSampleRide: Boolean
    ) {
        val openIntent = Intent(context, RiderActivity::class.java).apply {
            action = NotificationDeepLinkExtras.ACTION_OPEN_RIDER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(NotificationDeepLinkExtras.EXTRA_TARGET_SCREEN, "home")
            putExtra(NotificationDeepLinkExtras.EXTRA_BOOKING_ID, bookingId)
            putExtra(NotificationDeepLinkExtras.EXTRA_TARGET_APP, "rider")
            putExtra(NotificationDeepLinkExtras.EXTRA_IS_TEST_RIDE, isTestRide)
            putExtra(NotificationDeepLinkExtras.EXTRA_IS_SAMPLE_RIDE, isSampleRide)
        }
        context.startActivity(openIntent)
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "RideReqActionReceiver"
        const val ACTION_ACCEPT_RIDE = "com.aistudio.golocal.ACTION_ACCEPT_RIDE"
        const val ACTION_DECLINE_RIDE = "com.aistudio.golocal.ACTION_DECLINE_RIDE"
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
