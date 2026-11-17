package com.example.shared

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.customer.BusQrTicketDialog
import com.example.data.model.Booking
import com.example.data.model.BookingCallContact
import com.example.data.model.BookingChatMessage
import com.example.data.model.BusBooking
import com.example.domain.BusFareEngine
import com.example.map.GolocalMapContainer
import com.example.ui.theme.GolocalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CommunicationAndBusRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `BookingCommunicationBar renders call chat and unread badge for active trip`() {
        val booking = Booking(
            id = "bk_100",
            customerId = "cust_1",
            customerName = "Aritra Sen",
            assignedDriverId = "drv_1",
            assignedDriverName = "Subrata Das",
            assignedVehicleLabel = "WB-04-T-2211",
            vehicleCategory = "cab",
            pickupLabel = "Howrah Station",
            status = "assigned"
        )
        val contact = BookingCallContact(
            bookingId = "bk_100",
            customerPhone = "+919800001111",
            driverPhone = "+919800002222",
            driverName = "Subrata Das"
        )

        composeTestRule.setContent {
            GolocalTheme {
                BookingCommunicationBar(
                    booking = booking,
                    viewerRole = "customer",
                    callContact = contact,
                    unreadCount = 3,
                    onCallClick = {},
                    onChatClick = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("active_trip_call_button").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithTag("active_trip_chat_button").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithTag("chat_unread_badge", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Subrata Das").assertIsDisplayed()
    }

    @Test
    fun `BookingCommunicationBar disables call when peer phone is not synced yet`() {
        val booking = Booking(
            id = "bk_101",
            customerId = "cust_1",
            assignedDriverId = "drv_1",
            assignedDriverName = "Subrata Das",
            status = "assigned"
        )
        composeTestRule.setContent {
            GolocalTheme {
                BookingCommunicationBar(
                    booking = booking,
                    viewerRole = "customer",
                    callContact = null,
                    unreadCount = 0,
                    onCallClick = {},
                    onChatClick = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("active_trip_call_button").assertIsNotEnabled()
    }

    @Test
    fun `BookingChatModalSheet allows sending during active trip and locks after completion`() {
        var sentText = ""
        val activeBooking = Booking(
            id = "bk_chat_1",
            customerId = "cust_1",
            assignedDriverId = "drv_1",
            assignedDriverName = "Ramesh Mondal",
            status = "arriving"
        )
        val bookingState = androidx.compose.runtime.mutableStateOf(activeBooking)

        composeTestRule.setContent {
            GolocalTheme {
                BookingChatModalSheet(
                    booking = bookingState.value,
                    viewerUid = "cust_1",
                    viewerRole = "customer",
                    messages = listOf(
                        BookingChatMessage(
                            id = "m1",
                            bookingId = "bk_chat_1",
                            senderId = "drv_1",
                            senderRole = "driver",
                            text = "Reaching Gate 2 in 3 mins",
                            deliveryState = "read"
                        )
                    ),
                    onSendMessage = { sentText = it },
                    onMarkRead = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Reaching Gate 2 in 3 mins").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_message_input").performTextInput("Near prepaid taxi booth")
        composeTestRule.onNodeWithTag("chat_send_button").performClick()
        assertEquals("Near prepaid taxi booth", sentText)

        composeTestRule.runOnUiThread {
            bookingState.value = activeBooking.copy(status = "completed")
        }
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.onAllNodesWithTag("chat_message_input").fetchSemanticsNodes().isEmpty())
        composeTestRule.onNodeWithText(
            "Chat is closed because this booking is completed. Read-only archive preserved."
        ).assertIsDisplayed()
    }

    @Test
    fun `BusQrTicketDialog generates ZXing QR code and displays digital boarding pass`() {
        val bookingId = "bus_bk_42"
        val scheduleId = "sched_9"
        val payload = BusFareEngine.buildQrTicketPayload(
            bookingId = bookingId,
            bookingRef = "GLB-42009",
            scheduleId = scheduleId,
            seatNumbers = listOf("B1", "B2")
        )
        val ticket = BusBooking(
            id = bookingId,
            bookingRef = "GLB-42009",
            scheduleId = scheduleId,
            routeLabel = "Kolkata -> Siliguri",
            seatNumbers = listOf("B1", "B2"),
            travelDateLabel = "2025-12-20",
            departureTimeLabel = "06:30 AM",
            qrPayload = payload,
            status = "confirmed"
        )

        composeTestRule.setContent {
            GolocalTheme {
                BusQrTicketDialog(ticket = ticket, onDismiss = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("bus_ticket_qr_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("GLB-42009").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("VALID FOR BOARDING").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `GolocalMapContainer uses JVM-safe fallback in Robolectric without MapLibre native JNI crash`() {
        composeTestRule.setContent {
            GolocalTheme {
                GolocalMapContainer(
                    pickupLat = 22.5726,
                    pickupLng = 88.3639,
                    pickupLabel = "Esplanade",
                    destLat = 22.5850,
                    destLng = 88.4100,
                    destLabel = "Salt Lake",
                    onlineDrivers = emptyList(),
                    liveTripLocation = null,
                    lowNetworkMode = false
                )
            }
        }
        composeTestRule.waitForIdle()
    }
}
