package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.repository.GolocalRepository
import com.example.domain.FareCalculator
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AdminDashboardMetrics(
    val totalCustomers: Int = 0,
    val totalDrivers: Int = 0,
    val verifiedDrivers: Int = 0,
    val pendingDrivers: Int = 0,
    val onlineDrivers: Int = 0,
    val activeRides: Int = 0,
    val completedRides: Int = 0,
    val cancelledRides: Int = 0,
    val grossBookingValueInr: Double = 0.0,
    val totalPlatformCommissionInr: Double = 0.0,
    val pendingSettlementsCount: Int = 0,
    val openComplaintsCount: Int = 0,
    val activeEmergenciesCount: Int = 0
)

class AdminViewModel(
    private val repository: GolocalRepository
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _sosEmergencyContacts = MutableStateFlow<Map<String, PrivateEmergencyContact?>>(emptyMap())
    val sosEmergencyContacts: StateFlow<Map<String, PrivateEmergencyContact?>> = _sosEmergencyContacts.asStateFlow()

    val users: StateFlow<List<UserAccount>> = repository.observeAdminUsers()
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin users", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val drivers: StateFlow<List<DriverProfile>> = repository.observeAdminDrivers()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin drivers", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val bookings: StateFlow<List<Booking>> = repository.observeAdminBookings()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin bookings", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val payments: StateFlow<List<PaymentRecord>> = repository.observeAdminPayments()
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin payments", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val complaints: StateFlow<List<ComplaintRecord>> = repository.observeAdminComplaints()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin complaints", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val emergencies: StateFlow<List<EmergencyEvent>> = repository.observeAdminEmergencies()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin emergencies", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val settlements: StateFlow<List<DriverSettlement>> = repository.observeAdminSettlements()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin settlements", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val refunds: StateFlow<List<RefundRecord>> = repository.observeAdminRefunds()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin refunds", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val auditLogs: StateFlow<List<AuditLog>> = repository.observeAdminAuditLogs()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin audit logs", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val fareConfigs: StateFlow<List<FareConfig>> = repository.observeFareConfigs()
        .map { remote ->
            if (remote.isEmpty()) FareCalculator.defaultFareConfigs()
            else remote.groupBy { it.vehicleCategory }.mapValues { it.value.maxByOrNull { c -> c.version }!! }.values.toList()
        }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; emit(FareCalculator.defaultFareConfigs()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FareCalculator.defaultFareConfigs())

    val serviceAreas: StateFlow<List<ServiceArea>> = repository.observeServiceAreas()
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val ratings: StateFlow<List<RatingRecord>> = repository.observeAdminRatings()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val supportRequests: StateFlow<List<SupportRequest>> = repository.observeAdminSupportRequests()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val adminAllowlist: StateFlow<List<AdminAccountEntry>> = repository.observeAdminAllowlist()
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val platformSettings: StateFlow<PlatformSettings> = repository.observePlatformSettings()
        .map { it ?: PlatformSettings() }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlatformSettings())

    val metrics: StateFlow<AdminDashboardMetrics> = combine(
        users,
        drivers,
        bookings,
        payments,
        combine(settlements, complaints, emergencies) { s, c, e -> Triple(s, c, e) }
    ) { u, d, b, p, sce ->
        val (s, c, e) = sce
        AdminDashboardMetrics(
            totalCustomers = u.size,
            totalDrivers = d.size,
            verifiedDrivers = d.count { it.verificationStatus == "verified" },
            pendingDrivers = d.count { it.verificationStatus == "pending" },
            onlineDrivers = d.count { it.isOnline },
            activeRides = b.count { it.status in setOf("searching", "assigned", "arriving", "arrived", "in_progress") },
            completedRides = b.count { it.status == "completed" },
            cancelledRides = b.count { it.status == "cancelled" },
            grossBookingValueInr = p.sumOf { it.grossFareInr },
            totalPlatformCommissionInr = p.sumOf { it.platformCommissionInr },
            pendingSettlementsCount = s.count { it.status == "requested" },
            openComplaintsCount = c.count { it.status != "resolved" },
            activeEmergenciesCount = e.count { it.status != "resolved" }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AdminDashboardMetrics())

    fun verifyDriver(driverId: String, status: String, reason: String = "") {
        viewModelScope.launch {
            repository.adminVerifyOrUpdateDriver(driverId, status, reason)
                .onSuccess { _message.value = "Rider status updated to $status." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to update rider" }
        }
    }

    fun saveFareConfig(config: FareConfig) {
        viewModelScope.launch {
            repository.adminSaveFareConfig(config)
                .onSuccess { _message.value = "Saved ${config.vehicleCategory} pricing v${config.version + 1}." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to save fare config" }
        }
    }

    fun addServiceArea(name: String, nameBn: String, district: String, block: String, zoneType: String, parcelApproved: Boolean) {
        if (name.isBlank() || district.isBlank()) {
            _message.value = "Area name and district are required."
            return
        }
        viewModelScope.launch {
            repository.adminAddServiceArea(
                ServiceArea(
                    name = name,
                    nameBn = nameBn,
                    district = district,
                    block = block,
                    zoneType = zoneType,
                    parcelApproved = parcelApproved
                )
            ).onSuccess { _message.value = "Service area $name added." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to add service area" }
        }
    }

    fun toggleServiceArea(areaId: String, active: Boolean) {
        viewModelScope.launch {
            repository.adminToggleServiceArea(areaId, active)
                .onSuccess { _message.value = if (active) "Service area activated." else "Service area deactivated." }
                .onFailure { _message.value = it.localizedMessage ?: "Update failed" }
        }
    }

    fun setUserAccountStatus(targetUid: String, newStatus: String) {
        viewModelScope.launch {
            repository.adminSetUserAccountStatus(targetUid, newStatus)
                .onSuccess { _message.value = "Customer status updated to $newStatus." }
                .onFailure { _message.value = it.localizedMessage ?: "Status update failed" }
        }
    }

    fun sendNotification(targetUid: String, title: String, body: String) {
        if (targetUid.isBlank() || title.isBlank() || body.isBlank()) {
            _message.value = "Recipient UID, title, and message body are required."
            return
        }
        viewModelScope.launch {
            repository.adminSendNotification(targetUid, title, body)
                .onSuccess { _message.value = "Notification dispatched." }
                .onFailure { _message.value = it.localizedMessage ?: "Dispatch failed" }
        }
    }

    fun savePlatformSettings(settings: PlatformSettings) {
        viewModelScope.launch {
            repository.adminSavePlatformSettings(settings)
                .onSuccess { _message.value = "Platform settings saved." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to save settings" }
        }
    }

    fun grantAdminAccess(uid: String, email: String, name: String) {
        if (uid.isBlank() || email.isBlank()) {
            _message.value = "Admin UID and verified email are required."
            return
        }
        viewModelScope.launch {
            repository.adminGrantAdminAccess(uid, email, name)
                .onSuccess { _message.value = "Admin access granted to $email." }
                .onFailure { _message.value = it.localizedMessage ?: "Only primary bootstrapped admin can manage admin accounts." }
        }
    }

    fun revokeAdminAccess(uid: String) {
        viewModelScope.launch {
            repository.adminRevokeAdminAccess(uid)
                .onSuccess { _message.value = "Admin access revoked." }
                .onFailure { _message.value = it.localizedMessage ?: "Revoke failed" }
        }
    }

    fun resolveComplaint(id: String, status: String, note: String) {
        viewModelScope.launch {
            repository.adminUpdateComplaintStatus(id, status, note)
                .onSuccess { _message.value = "Complaint marked $status." }
                .onFailure { _message.value = it.localizedMessage ?: "Update failed" }
        }
    }

    fun resolveEmergency(id: String, status: String) {
        viewModelScope.launch {
            repository.adminResolveEmergency(id, status)
                .onSuccess { _message.value = "SOS event marked $status." }
                .onFailure { _message.value = it.localizedMessage ?: "Update failed" }
        }
    }

    fun loadEmergencyContactForSos(userId: String) {
        if (userId.isBlank()) return
        viewModelScope.launch {
            repository.fetchEmergencyContactForAuthorizedResponder(userId)
                .onSuccess { contact ->
                    _sosEmergencyContacts.value = _sosEmergencyContacts.value + (userId to contact)
                    if (contact == null) {
                        _message.value = "No private emergency contact recorded for user $userId."
                    }
                }
                .onFailure {
                    _message.value = it.localizedMessage ?: "Unable to fetch private emergency contact."
                }
        }
    }

    fun processSettlement(id: String, status: String) {
        viewModelScope.launch {
            repository.adminProcessSettlement(id, status)
                .onSuccess { _message.value = "Settlement updated to $status." }
                .onFailure { _message.value = it.localizedMessage ?: "Update failed" }
        }
    }

    fun processRefund(id: String, status: String) {
        viewModelScope.launch {
            repository.adminProcessRefund(id, status)
                .onSuccess { _message.value = "Refund updated to $status." }
                .onFailure { _message.value = it.localizedMessage ?: "Update failed" }
        }
    }

    fun initializeDefaultPricing() {
        viewModelScope.launch {
            repository.adminSeedWestBengalDefaults()
                .onSuccess { _message.value = "Default West Bengal pricing catalog saved to Firestore." }
                .onFailure { _message.value = it.localizedMessage ?: "Initialization failed" }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val TAG = "AdminVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
