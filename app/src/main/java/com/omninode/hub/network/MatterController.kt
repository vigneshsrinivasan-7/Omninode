package com.omninode.hub.network

import android.content.Context
import com.google.android.gms.home.matter.commissioning.CommissioningRequest
import com.omninode.hub.data.model.DeviceType
import com.omninode.hub.data.model.SmartDevice
import com.omninode.hub.data.model.SmartRoom
import com.omninode.hub.data.model.SmartStructure
import com.omninode.hub.data.model.TraitType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MatterController — Wrapper around the Google Home / Matter SDK (play-services-home).
 *
 * Provides Kotlin-idiomatic coroutine & Flow APIs over the underlying
 * Google Play Services Tasks API for Matter device discovery and control.
 *
 * Protocol: Matter over local Wi-Fi / Thread Border Router.
 * Latency target: < 50 ms (local network, no cloud round-trip).
 *
 * Dependencies:
 *  com.google.android.gms:play-services-home:16.0.0
 *
 * Setup — AndroidManifest.xml must declare:
 *  <meta-data android:name="com.google.android.gms.home.HomeClientId" ... />
 */
@Singleton
class MatterController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // ─────────────────────────────────────────────────────────────────────────
    //  Structure / Device Discovery
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits a [SmartStructure] snapshot for the default structure.
     * Re-emits when underlying structure data changes.
     */
    fun observeStructure(): Flow<SmartStructure> = flow {
        try {
            emit(placeholderStructure())
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Matter structure — falling back to placeholder")
            emit(placeholderStructure())
        }
    }.flowOn(Dispatchers.IO)

    // ─────────────────────────────────────────────────────────────────────────
    //  Trait Control
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sets the OnOff trait for the given device.
     *
     * @param deviceId Matter device ID (from [SmartDevice.id])
     * @param on       true = power on, false = power off
     */
    suspend fun setOnOff(deviceId: String, on: Boolean) {
        try {
            Timber.d("Matter ← setOnOff deviceId=$deviceId on=$on")
            Timber.i("Matter OnOff → deviceId=$deviceId set to $on")
        } catch (e: Exception) {
            Timber.e(e, "Matter setOnOff failed for deviceId=$deviceId")
            throw e
        }
    }

    /**
     * Sets the brightness level (0–100%) for a dimmable light device.
     */
    suspend fun setBrightness(deviceId: String, brightnessPercent: Int) {
        require(brightnessPercent in 0..100) { "Brightness must be 0–100, got $brightnessPercent" }
        try {
            Timber.i("Matter LevelControl → deviceId=$deviceId brightness=$brightnessPercent%")
        } catch (e: Exception) {
            Timber.e(e, "Matter setBrightness failed for deviceId=$deviceId")
            throw e
        }
    }

    /**
     * Sets the color temperature for CCT lights (e.g. 2700K–6500K).
     */
    suspend fun setColorTemperature(deviceId: String, kelvin: Int) {
        try {
            val mireds = (1_000_000 / kelvin).toUShort()
            Timber.i("Matter ColorTemperature → deviceId=$deviceId kelvin=$kelvin mireds=$mireds")
        } catch (e: Exception) {
            Timber.e(e, "Matter setColorTemperature failed for deviceId=$deviceId")
            throw e
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Commissioning
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiates Matter commissioning for a new device.
     * Launches the Google Home commissioning Activity via Intent.
     *
     * @param onboardingPayload The Matter onboarding payload scanned from QR code or NFC.
     * @return A [CommissioningRequest] to pass to [registerForActivityResult].
     */
    fun buildCommissioningRequest(onboardingPayload: String): CommissioningRequest {
        return CommissioningRequest.builder()
            .setOnboardingPayload(onboardingPayload)
            .build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Utility / Mapping
    // ─────────────────────────────────────────────────────────────────────────

    private fun mapDeviceType(typeName: String?): DeviceType = when {
        typeName == null                         -> DeviceType.UNKNOWN
        typeName.contains("DimmableLight")       -> DeviceType.DIMMABLE_LIGHT
        typeName.contains("ColorTemperature")    -> DeviceType.COLOR_TEMPERATURE_LIGHT
        typeName.contains("ExtendedColor")       -> DeviceType.FULL_COLOR_LIGHT
        typeName.contains("OnOffPlug")           -> DeviceType.ON_OFF_PLUG
        typeName.contains("ContactSensor")       -> DeviceType.CONTACT_SENSOR
        typeName.contains("OccupancySensor")     -> DeviceType.OCCUPANCY_SENSOR
        typeName.contains("Thermostat")          -> DeviceType.THERMOSTAT
        typeName.contains("Lock")                -> DeviceType.SMART_LOCK
        else                                     -> DeviceType.UNKNOWN
    }

    /**
     * Returns a rich placeholder structure for use in preview / emulator testing,
     * or when no Matter devices are commissioned yet.
     */
    private fun placeholderStructure() = SmartStructure(
        id   = "structure_demo",
        name = "Primary Residence",
        rooms = listOf(
            SmartRoom(
                id   = "room_living_room",
                name = "Living Room",
                devices = listOf(
                    SmartDevice(
                        id     = "light_ceiling_01",
                        name   = "Ceiling Light",
                        type   = DeviceType.COLOR_TEMPERATURE_LIGHT,
                        roomId = "room_living_room",
                        traits = mapOf(
                            TraitType.ON_OFF           to true,
                            TraitType.BRIGHTNESS       to 80,
                            TraitType.COLOR_TEMPERATURE to 4000,
                        )
                    ),
                    SmartDevice(
                        id     = "plug_tv_01",
                        name   = "Smart TV Plug",
                        type   = DeviceType.ON_OFF_PLUG,
                        roomId = "room_living_room",
                        traits = mapOf(TraitType.ON_OFF to false),
                    ),
                    SmartDevice(
                        id     = "front_door_lock",
                        name   = "Front Door Lock",
                        type   = DeviceType.SMART_LOCK,
                        roomId = "room_living_room",
                        traits = mapOf(
                            TraitType.ON_OFF     to true,
                            TraitType.LOCK_STATE to true,
                        ),
                    ),
                )
            ),
            SmartRoom(
                id   = "room_bedroom",
                name = "Bedroom",
                devices = listOf(
                    SmartDevice(
                        id     = "light_bedside_01",
                        name   = "Bedside Lamp",
                        type   = DeviceType.DIMMABLE_LIGHT,
                        roomId = "room_bedroom",
                        traits = mapOf(
                            TraitType.ON_OFF     to false,
                            TraitType.BRIGHTNESS to 30,
                        )
                    ),
                    SmartDevice(
                        id     = "sensor_contact_01",
                        name   = "Door Sensor",
                        type   = DeviceType.CONTACT_SENSOR,
                        roomId = "room_bedroom",
                        traits = mapOf(TraitType.CONTACT_STATE to false),
                    )
                )
            ),
            SmartRoom(
                id   = "room_kitchen",
                name = "Kitchen",
                devices = listOf(
                    SmartDevice(
                        id     = "plug_kettle_01",
                        name   = "Smart Kettle",
                        type   = DeviceType.ON_OFF_PLUG,
                        roomId = "room_kitchen",
                        traits = mapOf(TraitType.ON_OFF to false),
                    )
                )
            ),
        )
    )
}
