package org.owntracks.android.gms.location.geofencing

import org.owntracks.android.location.geofencing.Geofence
import org.owntracks.android.location.geofencing.GeofencingRequest


fun GeofencingRequest.toGMSGeofencingRequest(): com.google.android.gms.location.GeofencingRequest {
    val builder = com.google.android.gms.location.GeofencingRequest.Builder()
    this.geofences?.run { builder.addGeofences(this.toMutableList().map { it.toGMSGeofence() }) }
    this.initialTrigger?.run(builder::setInitialTrigger)
    return builder.build()
}

fun Geofence.toGMSGeofence(): com.google.android.gms.location.Geofence {
    val builder = com.google.android.gms.location.Geofence.Builder()
    this.requestId?.run(builder::setRequestId)
    this.circularLatitude?.also {
        this.circularLongitude?.also {
            this.circularRadius?.also {
                builder.setCircularRegion(
                    this.circularLatitude,
                    this.circularLongitude,
                    this.circularRadius
                )
            }
        }
    }
    this.expirationDuration?.run(builder::setExpirationDuration)
    this.transitionTypes?.run(builder::setTransitionTypes)
    this.notificationResponsiveness?.run(builder::setNotificationResponsiveness)
    this.loiteringDelay?.run(builder::setLoiteringDelay)
    return builder.build()
}