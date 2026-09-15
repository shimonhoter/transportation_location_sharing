package com.shimonhoter.ridelocationshare.service

import androidx.lifecycle.MutableLiveData
import com.shimonhoter.ridelocationshare.remote.RideLocation

/**
 * In-memory, process-local bridge between BroadcastService and the UI.
 * Deliberately not persisted: this mirrors server state (last known ride
 * location), which is itself never persisted (docs/SPEC_EN.md section 5).
 */
object RideSessionState {
    val currentLocation = MutableLiveData<RideLocation?>(null)
    val isThisDeviceBroadcasting = MutableLiveData(false)
}
