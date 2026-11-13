package com.radiosport.ninegradio.ui

import com.radiosport.ninegradio.data.RadioTarget
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/** Map presentation fields; received heading stays independent of the map/camera bearing. */
fun RadioTarget.mapFeature():Feature = Feature.fromGeometry(Point.fromLngLat(longitude,latitude)).apply {
    addStringProperty("id",id);addStringProperty("label",label)
    val aircraft=type==RadioTarget.Type.AIRCRAFT
    val heading=headingDegrees?.takeIf { it.isFinite() && it in 0.0..360.0 }?.rem(360.0)
    addStringProperty("kind",if(aircraft) "aircraft" else "station")
    addNumberProperty("heading",heading ?: 0.0)
    addBooleanProperty("headingKnown",heading!=null)
    addStringProperty("icon",if(heading!=null) "radio-aircraft-known" else "radio-aircraft-unknown")
}
