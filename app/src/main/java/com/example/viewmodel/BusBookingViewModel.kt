package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BusBooking
import com.example.data.model.BusOperator
import com.example.data.model.BusRoute
import com.example.data.model.BusSchedule
import com.example.data.model.BusSeatLock
import com.example.data.model.BusVehicle
import com.example.data.model.DriverDocumentItem
import com.example.data.repository.BusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BusBookingViewModel(
    private val repository: BusRepository,
    private val currentUserId: String,
    private val mode: String = "customer" // customer, operator, admin
) : ViewModel() {

    private val _selectedSchedule = MutableStateFlow<BusSchedule?>(null)
    val selectedSchedule: StateFlow<BusSchedule?> = _selectedSchedule.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _lastValidatedTicket = MutableStateFlow<BusBooking?>(null)
    val lastValidatedTicket: StateFlow<BusBooking?> = _lastValidatedTicket.asStateFlow()

    val myOperatorProfile: StateFlow<BusOperator?> = if (mode == "operator") {
        repository.observeMyBusOperator(currentUserId)
            .catch { }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)
    } else {
        MutableStateFlow<BusOperator?>(null).asStateFlow()
    }

    val allOperators: StateFlow<List<BusOperator>> = if (mode == "admin") {
        repository.observeAllBusOperators()
            .catch { }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())
    } else {
        MutableStateFlow<List<BusOperator>>(emptyList()).asStateFlow()
    }

    val busVehicles: StateFlow<List<BusVehicle>> = when (mode) {
        "admin" -> repository.observeBusVehicles(null)
        "operator" -> repository.observeBusVehicles(currentUserId)
        else -> flowOf(emptyList())
    }.catch { }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val busRoutes: StateFlow<List<BusRoute>> = when (mode) {
        "admin" -> repository.observeBusRoutes(null)
        "operator" -> repository.observeBusRoutes(currentUserId)
        else -> flowOf(emptyList())
    }.catch { }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val busSchedules: StateFlow<List<BusSchedule>> = when (mode) {
        "admin" -> repository.observeAllBusSchedules()
        "operator" -> repository.observeOperatorBusSchedules(currentUserId)
        else -> repository.observeApprovedBusSchedules()
    }.catch { }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val selectedScheduleLocks: StateFlow<List<BusSeatLock>> = _selectedSchedule
        .flatMapLatest { sched ->
            if (sched == null) flowOf(emptyList())
            else repository.observeSeatLocksForSchedule(sched.id)
        }
        .catch { }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val myBusBookings: StateFlow<List<BusBooking>> = when (mode) {
        "customer" -> repository.observeMyBusBookings(currentUserId)
        "operator" -> repository.observeOperatorBusBookings(currentUserId)
        else -> flowOf(emptyList())
    }.catch { }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    fun selectScheduleForSeatPicker(schedule: BusSchedule?) {
        _selectedSchedule.value = schedule
    }

    fun clearMessage() {
        _message.value = null
    }

    fun reserveAndBookSeats(
        schedule: BusSchedule,
        customerName: String,
        boardingStop: String,
        dropStop: String,
        seatIds: List<String>,
        paymentMethod: String,
        onBooked: (BusBooking) -> Unit = {}
    ) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch {
            repository.reserveBusSeatsAndBook(
                scheduleId = schedule.id,
                customerName = customerName,
                boardingStop = boardingStop,
                dropStop = dropStop,
                selectedSeatIds = seatIds,
                paymentMethod = paymentMethod
            ).onSuccess { ticket ->
                _isBusy.value = false
                _selectedSchedule.value = null
                _message.value = "Ticket ${ticket.qrToken} confirmed (${ticket.seatIds.joinToString(", ")})"
                onBooked(ticket)
            }.onFailure { err ->
                _isBusy.value = false
                _message.value = err.localizedMessage ?: "Seat booking failed."
            }
        }
    }

    fun cancelBusTicket(ticketId: String, reason: String) {
        viewModelScope.launch {
            repository.cancelBusBooking(ticketId, reason)
                .onSuccess { refund ->
                    _message.value = "Ticket cancelled. Refund ₹${refund.toInt()} requested."
                }
                .onFailure { err ->
                    _message.value = err.localizedMessage ?: "Failed to cancel bus ticket."
                }
        }
    }

    fun submitOperatorProfile(
        companyName: String,
        contactPhone: String,
        licenseNo: String,
        permitNo: String,
        district: String,
        upiOrBank: String,
        docs: List<DriverDocumentItem>
    ) {
        viewModelScope.launch {
            repository.submitBusOperatorOnboarding(companyName, contactPhone, licenseNo, permitNo, district, upiOrBank, docs)
                .onSuccess { _message.value = "Bus Operator profile submitted for Admin verification." }
                .onFailure { _message.value = it.localizedMessage ?: "Operator submission failed." }
        }
    }

    fun registerVehicle(
        busName: String,
        regNo: String,
        busType: String,
        totalSeats: Int,
        docs: List<DriverDocumentItem>
    ) {
        viewModelScope.launch {
            repository.registerBusVehicle(busName, regNo, busType, totalSeats, docs)
                .onSuccess { _message.value = "Bus vehicle submitted for Admin KYC approval." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to register bus." }
        }
    }

    fun submitBusRoute(
        originTown: String,
        destinationTown: String,
        stopsCsv: String,
        distanceKm: Double,
        farePerSeatInr: Double,
        fareSource: String,
        authorityRef: String,
        effectiveDateIso: String
    ) {
        viewModelScope.launch {
            val stops = stopsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
            repository.createBusRoute(originTown, destinationTown, stops, distanceKm, farePerSeatInr, fareSource, authorityRef, effectiveDateIso)
                .onSuccess { _message.value = "Bus route submitted with tariff ($fareSource). Status: Pending Admin Fare Approval." }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to add bus route." }
        }
    }

    fun publishBusSchedule(
        route: BusRoute,
        vehicle: BusVehicle,
        dateIso: String,
        depTime: String,
        arrTime: String
    ) {
        viewModelScope.launch {
            repository.createBusSchedule(route, vehicle, dateIso, depTime, arrTime)
                .onSuccess {
                    _message.value = if (route.fareApprovalStatus == "approved") {
                        "Bus schedule published live for passengers!"
                    } else {
                        "Schedule saved — remains hidden from customers until Admin approves route fare."
                    }
                }
                .onFailure { _message.value = it.localizedMessage ?: "Failed to create schedule." }
        }
    }

    fun validateQrTicket(qrTokenOrId: String) {
        viewModelScope.launch {
            repository.validateBusTicketQr(qrTokenOrId)
                .onSuccess { ticket ->
                    _lastValidatedTicket.value = ticket
                    _message.value = "VALID TICKET: ${ticket.customerName} • Seats ${ticket.seatIds.joinToString(", ")}"
                }
                .onFailure { err ->
                    _lastValidatedTicket.value = null
                    _message.value = err.localizedMessage ?: "Ticket validation failed."
                }
        }
    }

    fun adminVerifyOperator(operatorId: String, status: String, reason: String) {
        viewModelScope.launch {
            repository.adminVerifyBusOperator(operatorId, status, reason)
                .onSuccess { _message.value = "Bus operator $operatorId marked $status." }
                .onFailure { _message.value = it.localizedMessage ?: "Operator review failed." }
        }
    }

    fun adminVerifyVehicle(busId: String, status: String, reason: String) {
        viewModelScope.launch {
            repository.adminVerifyBusVehicle(busId, status, reason)
                .onSuccess { _message.value = "Bus vehicle updated to $status." }
                .onFailure { _message.value = it.localizedMessage ?: "Vehicle review failed." }
        }
    }

    fun adminReviewRouteFare(routeId: String, approve: Boolean, reason: String) {
        viewModelScope.launch {
            repository.adminApproveOrRejectBusRouteFare(routeId, approve, reason)
                .onSuccess { _message.value = if (approve) "Bus route tariff approved!" else "Bus route tariff rejected." }
                .onFailure { _message.value = it.localizedMessage ?: "Fare review failed." }
        }
    }
}
