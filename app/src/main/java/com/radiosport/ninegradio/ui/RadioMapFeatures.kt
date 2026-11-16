package com.radiosport.ninegradio.ui

import com.radiosport.ninegradio.data.RadioTarget
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/** Map presentation fields; received heading stays independent of the map/camera bearing. */
fun RadioTarget.mapFeature(nowMs:Long=System.currentTimeMillis()):Feature = Feature.fromGeometry(Point.fromLngLat(longitude,latitude)).apply {
    addStringProperty("id",id);addStringProperty("label",label)
    val aircraft=type==RadioTarget.Type.AIRCRAFT
    val ship=type==RadioTarget.Type.SHIP
    val heading=headingDegrees?.takeIf { it.isFinite() && it in 0.0..360.0 }?.rem(360.0)
    addStringProperty("kind",if(aircraft) "aircraft" else if(ship) "ship" else "station")
    addNumberProperty("heading",heading ?: 0.0)
    addBooleanProperty("headingKnown",heading!=null)
    val stale=(aircraft || ship) && (positionTimeMs==null || nowMs-positionTimeMs>(if(ship) 300_000 else 30_000))
    addBooleanProperty("positionStale",stale)
    val prefix=if(ship) "radio-ship" else "radio-aircraft"
    addStringProperty("icon",if(stale) "$prefix-stale" else if(heading!=null) "$prefix-known" else "$prefix-unknown")
}
