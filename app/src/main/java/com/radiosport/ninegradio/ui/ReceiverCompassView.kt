package com.radiosport.ninegradio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.View

/** Heading comes from Android's fused rotation vector, referenced to magnetic north. */
class ReceiverCompassView(context: Context): View(context), SensorEventListener {
    private val manager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor=manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val matrix=FloatArray(9)
    private val displayMatrix=FloatArray(9)
    private val angles=FloatArray(3)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val needle=Path()
    private var heading: Float?=null
    private var active=false
    private var registered=false
    private var accuracy=SensorManager.SENSOR_STATUS_UNRELIABLE
    fun setActive(value: Boolean) { active=value; updateSensor() }
    private fun updateSensor() {
        val wanted=active && isAttachedToWindow && windowVisibility==VISIBLE
        if(wanted && !registered && sensor!=null) registered=manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_UI)
        else if(!wanted && registered) { manager.unregisterListener(this); registered=false }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateSensor() }
    override fun onDetachedFromWindow() { manager.unregisterListener(this); registered=false; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateSensor() }
    override fun onAccuracyChanged(sensor: Sensor?,value: Int) { accuracy=value; invalidate() }
    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix,event.values)
        @Suppress("DEPRECATION") val rotation=(context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
        val axes=when(rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(matrix,axes.first,axes.second,displayMatrix)
        SensorManager.getOrientation(displayMatrix,angles)
        heading=((Math.toDegrees(angles[0].toDouble())+360)%360).toFloat()
        postInvalidateOnAnimation()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale=resources.displayMetrics.density
        val cx=width/2f; val cy=height/2f-18*scale; val radius=minOf(width.toFloat(),height-66*scale)*0.43f
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1.5f*scale; paint.color=0xff29424f.toInt()
        canvas.drawCircle(cx,cy,radius,paint)
        canvas.save(); canvas.rotate(-(heading ?: 0f),cx,cy)
        for(degree in 0 until 360 step 5) {
            canvas.save(); canvas.rotate(degree.toFloat(),cx,cy)
            paint.color=if(degree==0) 0xffefa66b.toInt() else 0xff7798a5.toInt()
            canvas.drawLine(cx,cy-radius,cx,cy-radius+(if(degree%30==0) 13 else 6)*scale,paint)
            if(degree%90==0) {
                paint.style=Paint.Style.FILL; paint.textAlign=Paint.Align.CENTER; paint.textSize=17*scale
                canvas.drawText(listOf("北","东","南","西")[degree/90],cx,cy-radius+34*scale,paint)
                paint.style=Paint.Style.STROKE
            }
            canvas.restore()
        }
        canvas.restore()
        paint.style=Paint.Style.FILL; paint.color=0xffefa66b.toInt()
        needle.reset(); needle.moveTo(cx,cy-radius+3*scale); needle.lineTo(cx-6*scale,cy-radius-9*scale); needle.lineTo(cx+6*scale,cy-radius-9*scale); needle.close()
        canvas.drawPath(needle,paint)
        paint.textAlign=Paint.Align.CENTER; paint.textSize=34*scale; paint.color=0xffdbe5eb.toInt()
        canvas.drawText(heading?.let { "%03.0f°".format(it) } ?: "—",cx,cy+10*scale,paint)
        paint.textSize=12*scale; paint.color=0xff8fb9c2.toInt()
        canvas.drawText("指南针 · 磁北",cx,cy+35*scale,paint)
        paint.textSize=11*scale
        canvas.drawText(if(sensor==null) "设备无方向传感器" else if(accuracy==SensorManager.SENSOR_STATUS_UNRELIABLE) "罗盘精度低" else "手机朝向",cx,height-12*scale,paint)
    }
}
