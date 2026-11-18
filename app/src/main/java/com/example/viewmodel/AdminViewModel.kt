package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.repository.BusRepository
import com.example.data.repository.CommunicationRepository
import com.example.data.repository.GolocalRepository
import com.example.domain.AbuseDetectionEngine
import com.example.domain.DriverAbuseAdvisory
import com.example.domain.FareCalculator
import com.example.domain.SimulatedTestDriver
import com.example.domain.TestDriverCatalog
import com.example.domain.TestRideRouteSimulator
import com.example.domain.TestRideSeparation
import com.example.map.GeoPoint
import com.example.map.OsrmRouteService
import com.example.map.RouteUiState
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModel(
    private val repository: GolocalRepository,
    private val communicationRepository: CommunicationRepository? = null,
    private val busRepository: BusRepository? = null
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _sosEmergencyContacts = MutableStateFlow<Map<String, PrivateEmergencyContact?>>(emptyMap())
    val sosEmergencyContacts: StateFlow<Map<String, PrivateEmergencyContact?>> = _sosEmergencyContacts.asStateFlow()

    private val _inspectedChatBookingId = MutableStateFlow<String?>(null)
    val inspectedChatBookingId: StateFlow<String?> = _inspectedChatBookingId.asStateFlow()

    private val _simulatingBookingId = MutableStateFlow<String?>(null)
    val simulatingBookingId: StateFlow<String?> = _simulatingBookingId.asStateFlow()

    private val _simulationPhase = MutableStateFlow<String?>(null)
    val simulationPhase: StateFlow<String?> = _simulationPhase.asStateFlow()

    private var simulationJob: Job? = null
    private val autoAssignedIds = mutableSetOf<String>()

    val users: StateFlow<List<UserAccount>> = repository.observeAdminUsers()
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin users", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val drivers: StateFlow<List<DriverProfile>> = repository.observeAdminDrivers()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin drivers", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val verificationHistory: StateFlow<List<DriverVerificationHistoryEntry>> = repository.observeAllVerificationHistory()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val allBookings: StateFlow<List<Booking>> = repository.observeAdminBookings()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin bookings", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val bookings: StateFlow<List<Booking>> = allBookings
        .map { list -> TestRideSeparation.filterProductionBookings(list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val testBookings: StateFlow<List<Booking>> = allBookings
        .map { list -> TestRideSeparation.filterTestBookings(list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val testTripLocations: StateFlow<Map<String, TripLocation>> = repository.observeTestRideTripLocations()
        .map { list -> list.associateBy { it.bookingId } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; emit(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    val testOtpSecrets: StateFlow<Map<String, TestRideOtpSecret>> = repository.observeTestRideOtpSecrets()
        .map { list -> list.associateBy { it.bookingId } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; emit(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    val cancellationAdvisories: StateFlow<List<DriverAbuseAdvisory>> = bookings
        .map { list -> AbuseDetectionEngine.evaluateCancellationAdvisories(list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val cancellationAppeals: StateFlow<List<CancellationAppeal>> = repository.observeAdminCancellationAppeals()
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val inspectedChatMessages: StateFlow<List<BookingChatMessage>> = _inspectedChatBookingId
        .flatMapLatest { bid ->
            val comm = communicationRepository
            if (comm == null || bid.isNullOrBlank()) flowOf(emptyList())
            else comm.observeMessages(bid).catch { emit(emptyList()) }
        }
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

    val operationsStaff: StateFlow<List<OperationsStaffEntry>> = repository.observeOperationsStaff()
        .map { list -> list.sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; Log.w(TAG, "Admin ops staff", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val platformSettings: StateFlow<PlatformSettings> = repository.observePlatformSettings()
        .map { it ?: PlatformSettings() }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlatformSettings())

    // Private Bus Admin Streams
    val busOperators: StateFlow<List<BusOperator>> = (busRepository?.observeBusOperatorsAdmin() ?: flowOf(emptyList()))
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val busRoutes: StateFlow<List<BusRoute>> = (busRepository?.observeAllBusRoutesAdmin() ?: flowOf(emptyList()))
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val busSchedules: StateFlow<List<BusSchedule>> = (busRepository?.observeBusSchedules() ?: flowOf(emptyList()))
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val busBookings: StateFlow<List<BusBooking>> = (busRepository?.observeAdminBusBookings() ?: flowOf(emptyList()))
        .map { list -> list.sortedByDescending { it.createdAt ?: Timestamp(0, 0) } }
        .catch { if (it is CancellationException && it.cause !is FirebaseFirestoreException) throw it; emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

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

    fun inspectBookingChat(bookingId: String?) {
        _inspectedChatBookingId.value = bookingId
    }

    fun verifyDriver(driverId: String, status: String, reason: String = "") {
        viewModelScope.launch {
            repository.adminVerifyOrUpdateDriver(driverId, status, reason)
                .onSuccess { _message.value = "Rider KYC updated to $status." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to update rider" }
        }
    }

    fun resolveCancellationAppeal(appealId: String, decision: String, note: String) {
        viewModelScope.launch {
            repository.adminResolveCancellationAppeal(appealId, decision, note)
                .onSuccess { _message.value = "Appeal marked $decision." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to resolve appeal" }
        }
    }

    fun saveBusOperator(operator: BusOperator) {
        val busRepo = busRepository ?: return
        viewModelScope.launch {
            busRepo.adminSaveBusOperator(operator)
                .onSuccess { _message.value = "Saved bus operator ${operator.operatorName}." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed saving operator" }
        }
    }

    fun saveBusRoute(route: BusRoute, activateRequested: Boolean) {
        val busRepo = busRepository ?: return
        viewModelScope.launch {
            busRepo.adminSaveBusRoute(route, activateRequested)
                .onSuccess { _message.value = "Saved bus route ${route.routeName}." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed saving route" }
        }
    }

    fun createBusSchedule(schedule: BusSchedule, approvedRoute: BusRoute) {
        val busRepo = busRepository ?: return
        viewModelScope.launch {
            busRepo.adminCreateBusSchedule(schedule, approvedRoute)
                .onSuccess { _message.value = "Published schedule for ${approvedRoute.routeName}." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed publishing schedule" }
        }
    }

    fun releaseCancelledBusSeats(booking: BusBooking) {
        val busRepo = busRepository ?: return
        viewModelScope.launch {
            busRepo.adminReleaseCancelledBusSeats(booking)
                .onSuccess { _message.value = "Released seats & refunded ${booking.bookingRef}." }
                .onFailure { _message.value = it.localizedMessage ?: "Seat release failed" }
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

    fun grantOperationsAccess(
        uid: String,
        displayName: String,
        contact: String,
        assignedDistrict: String
    ) {
        val cleanUid = uid.trim()
        val cleanName = displayName.trim()
        val cleanDistrict = assignedDistrict.trim()
        if (cleanUid.isBlank() || cleanName.isBlank() || cleanDistrict.isBlank()) {
            _message.value = "Staff UID, name, and assigned district are required."
            return
        }
        viewModelScope.launch {
            repository.adminGrantOperationsAccess(cleanUid, cleanName, contact.trim(), cleanDistrict)
                .onSuccess { _message.value = "Scoped operations access granted to $cleanName ($cleanDistrict)." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to grant operations access." }
        }
    }

    fun revokeOperationsAccess(uid: String) {
        val cleanUid = uid.trim()
        if (cleanUid.isBlank()) return
        viewModelScope.launch {
            repository.adminRevokeOperationsAccess(cleanUid)
                .onSuccess { _message.value = "Operations access revoked." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to revoke operations access." }
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

    // --- Admin-Controlled Live Test Ride Mode Controls ---

    fun toggleTestRideMode(enabled: Boolean) {
        val current = platformSettings.value
        val updated = current.copy(testRideModeEnabled = enabled)
        viewModelScope.launch {
            repository.adminSavePlatformSettings(updated)
                .onSuccess {
                    _message.value = if (enabled) {
                        "TEST RIDE MODE enabled. Customer bookings are now marked TEST RIDE."
                    } else {
                        "TEST RIDE MODE disabled. Platform returned to standard production bookings."
                    }
                }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to toggle Test Ride Mode." }
        }
    }

    fun updateTestRideSettings(
        autoAssignEnabled: Boolean,
        manualAssignEnabled: Boolean,
        speedKmph: Double,
        otpValiditySeconds: Long,
        allowAdminOtpOverride: Boolean,
        autoCompleteEnabled: Boolean
    ) {
        val updated = platformSettings.value.copy(
            testAutoAssignEnabled = autoAssignEnabled,
            testManualAssignEnabled = manualAssignEnabled,
            testDriverSpeedKmph = speedKmph.coerceIn(5.0, 120.0),
            testOtpValiditySeconds = otpValiditySeconds.coerceIn(60L, 1800L),
            testAllowAdminOtpOverride = allowAdminOtpOverride,
            testAutoCompleteEnabled = autoCompleteEnabled
        )
        viewModelScope.launch {
            repository.adminSavePlatformSettings(updated)
                .onSuccess { _message.value = "Test Ride simulation settings saved." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to save Test Ride settings." }
        }
    }

    fun assignTestDriver(
        booking: Booking,
        testDriver: SimulatedTestDriver = TestDriverCatalog.defaultForCategory(booking.vehicleCategory),
        notifyCustomer: Boolean = true,
        actorRole: String = "admin"
    ) {
        val validity = platformSettings.value.testOtpValiditySeconds.coerceIn(60L, 1800L)
        viewModelScope.launch {
            repository.assignTestDriverToBooking(booking, testDriver, validity, notifyCustomer, actorRole)
                .onSuccess { otp ->
                    _message.value = "Assigned ${testDriver.displayName} to TEST RIDE #${booking.id.take(6)}. TEST OTP: $otp"
                }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to assign Test Driver." }
        }
    }

    fun tryAutoAssignPendingTestRides(actorRole: String = "admin") {
        val settings = platformSettings.value
        if (!settings.testRideModeEnabled || !settings.testAutoAssignEnabled) return
        val pending = testBookings.value.filter {
            it.status == "searching" && it.assignedDriverId.isBlank() && it.id !in autoAssignedIds
        }
        pending.forEach { booking ->
            autoAssignedIds.add(booking.id)
            assignTestDriver(
                booking = booking,
                testDriver = TestDriverCatalog.defaultForCategory(booking.vehicleCategory),
                notifyCustomer = true,
                actorRole = actorRole
            )
        }
    }

    fun advanceTestRideStatus(
        booking: Booking,
        nextStatus: String,
        reason: String = "",
        actorRole: String = "admin"
    ) {
        if (nextStatus in setOf("completed", "cancelled") && _simulatingBookingId.value == booking.id) {
            stopMovementSimulation()
        }
        viewModelScope.launch {
            repository.updateTestRideStatus(booking, nextStatus, reason, actorRole)
                .onSuccess {
                    _message.value = "TEST RIDE #${booking.id.take(6)} updated to $nextStatus."
                }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to update Test Ride status." }
        }
    }

    fun sendTestOtpToCustomer(booking: Booking, actorRole: String = "admin") {
        val secret = testOtpSecrets.value[booking.id]
        if (secret == null || secret.otpPlaintext.length != 6) {
            _message.value = "Assign a Test Driver first to generate a Test OTP."
            return
        }
        viewModelScope.launch {
            repository.sendTestOtpNotificationToCustomer(booking, secret.otpPlaintext, actorRole)
                .onSuccess { _message.value = "Sent TEST OTP alert to customer (${secret.otpPlaintext})." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to send TEST OTP alert." }
        }
    }

    fun overrideTestOtpAndStartRide(
        booking: Booking,
        reason: String,
        actorRole: String = "admin"
    ) {
        if (!platformSettings.value.testAllowAdminOtpOverride) {
            _message.value = "Admin OTP override is disabled in Testing Settings."
            return
        }
        viewModelScope.launch {
            repository.adminOverrideTestOtpAndStartRide(booking, reason, actorRole)
                .onSuccess { _message.value = "Authorized override verified TEST RIDE #${booking.id.take(6)} -> in_progress." }
                .onFailure { _message.value = it.localizedMessage ?: "OTP override failed." }
        }
    }

    fun resetTestRide(booking: Booking, actorRole: String = "admin") {
        if (_simulatingBookingId.value == booking.id) stopMovementSimulation()
        autoAssignedIds.remove(booking.id)
        viewModelScope.launch {
            repository.resetTestRideBooking(booking, actorRole)
                .onSuccess { _message.value = "Reset TEST RIDE #${booking.id.take(6)} back to Pending." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to reset Test Ride." }
        }
    }

    fun clearCompletedTestRecords(actorRole: String = "admin") {
        viewModelScope.launch {
            repository.clearCompletedTestRideRecords(actorRole)
                .onSuccess { count ->
                    _message.value = if (count == 0) {
                        "No completed or cancelled TEST RIDE records to clear."
                    } else {
                        "Cleared $count completed/cancelled TEST RIDE record(s)."
                    }
                }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to clear Test Ride records." }
        }
    }

    fun startMovementSimulation(
        booking: Booking,
        phase: String,
        actorRole: String = "admin"
    ) {
        stopMovementSimulation()
        _simulatingBookingId.value = booking.id
        _simulationPhase.value = phase
        val speedKmph = platformSettings.value.testDriverSpeedKmph

        simulationJob = viewModelScope.launch {
            try {
                if (phase == "to_pickup") {
                    if (booking.status == "assigned") {
                        repository.updateTestRideStatus(booking, "arriving", actorRole = actorRole)
                    }
                    val currentLoc = testTripLocations.value[booking.id]
                    val startPt = if (currentLoc != null && currentLoc.lat != 0.0) {
                        GeoPoint(currentLoc.lat, currentLoc.lng)
                    } else {
                        val initPt = TestRideRouteSimulator.initialApproachPoint(booking.pickupLat, booking.pickupLng)
                        GeoPoint(initPt.lat, initPt.lng)
                    }
                    val endPt = GeoPoint(booking.pickupLat, booking.pickupLng)
                    val waypoints = resolveWaypoints(startPt, endPt, steps = 14)
                    val distKm = TestRideRouteSimulator.haversineKm(startPt.lat, startPt.lng, endPt.lat, endPt.lng)
                    val delayMs = TestRideRouteSimulator.stepDelayMs(distKm, speedKmph, waypoints.size)

                    waypoints.forEach { wp ->
                        repository.updateTestRideTripLocation(booking, wp.lat, wp.lng)
                        delay(delayMs)
                    }
                    val latestStatus = testBookings.value.firstOrNull { it.id == booking.id }?.status ?: booking.status
                    if (latestStatus in setOf("assigned", "arriving")) {
                        repository.updateTestRideStatus(
                            booking.copy(status = latestStatus),
                            "arrived",
                            actorRole = actorRole
                        )
                    }
                    _message.value = "TEST DRIVER arrived at pickup for #${booking.id.take(6)}. Waiting for Pickup OTP verification."
                } else if (phase == "to_drop") {
                    if (booking.status != "in_progress") {
                        _message.value = "Verify Pickup OTP first (ride must be In Progress) before simulating trip to drop."
                        return@launch
                    }
                    val currentLoc = testTripLocations.value[booking.id]
                    val startPt = if (currentLoc != null && currentLoc.lat != 0.0) {
                        GeoPoint(currentLoc.lat, currentLoc.lng)
                    } else {
                        GeoPoint(booking.pickupLat, booking.pickupLng)
                    }
                    val endPt = GeoPoint(booking.destinationLat, booking.destinationLng)
                    val waypoints = resolveWaypoints(startPt, endPt, steps = 18)
                    val distKm = booking.distanceKm.coerceAtLeast(1.0)
                    val delayMs = TestRideRouteSimulator.stepDelayMs(distKm, speedKmph, waypoints.size)

                    waypoints.forEach { wp ->
                        repository.updateTestRideTripLocation(booking, wp.lat, wp.lng)
                        delay(delayMs)
                    }
                    if (platformSettings.value.testAutoCompleteEnabled) {
                        val latestStatus = testBookings.value.firstOrNull { it.id == booking.id }?.status ?: booking.status
                        if (latestStatus == "in_progress") {
                            repository.updateTestRideStatus(
                                booking.copy(status = "in_progress"),
                                "completed",
                                actorRole = actorRole
                            )
                        }
                        _message.value = "Simulated trip reached destination and auto-completed TEST RIDE #${booking.id.take(6)}."
                    } else {
                        _message.value = "TEST DRIVER reached destination. Tap Complete Ride to finalize."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.localizedMessage ?: "Simulation interrupted."
            } finally {
                if (_simulatingBookingId.value == booking.id) {
                    _simulatingBookingId.value = null
                    _simulationPhase.value = null
                }
            }
        }
    }

    fun stopMovementSimulation() {
        simulationJob?.cancel()
        simulationJob = null
        _simulatingBookingId.value = null
        _simulationPhase.value = null
    }

    private suspend fun resolveWaypoints(
        start: GeoPoint,
        end: GeoPoint,
        steps: Int
    ): List<com.example.domain.SimulationWaypoint> {
        return when (val route = OsrmRouteService.fetchDrivingRoute(start, end)) {
            is RouteUiState.Ready -> TestRideRouteSimulator.resamplePolyline(
                route.points.map { it.lat to it.lng },
                targetSteps = steps
            )
            else -> TestRideRouteSimulator.interpolateWaypoints(
                start.lat,
                start.lng,
                end.lat,
                end.lng,
                steps = steps
            )
        }
    }

    override fun onCleared() {
        simulationJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "AdminVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
