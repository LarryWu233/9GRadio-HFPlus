package com.radiosport.ninegradio.dsp

/** Per-pipeline computation port. Android JNI is supplied by the platform adapter. */
interface DemodPrimitives {
    fun amDetect(iq:FloatArray):FloatArray
    fun fmDemodulateStateful(iq:FloatArray,state:FloatArray,gainHz:Float):FloatArray
    fun fmDemodulateStatefulInto(iq:FloatArray,state:FloatArray,gainHz:Float,out:FloatArray)
    fun applyFirDual(iq:FloatArray,taps:FloatArray,bufI:FloatArray,bufQ:FloatArray,bufPos:IntArray):FloatArray
}

/** Existing alpha28 Kotlin fallback implementations, moved here without changing the algorithms. */
object ReferenceDemodPrimitives:DemodPrimitives {
    override fun amDetect(iq:FloatArray)=FloatArray(iq.size/2) { i ->
        val re=iq[2*i];val im=iq[2*i+1];kotlin.math.sqrt(re*re+im*im)
    }
    override fun fmDemodulateStateful(iq:FloatArray,state:FloatArray,gainHz:Float):FloatArray {
        val out=FloatArray(iq.size/2);fmDemodulateStatefulInto(iq,state,gainHz,out);return out
    }
    override fun fmDemodulateStatefulInto(iq:FloatArray,state:FloatArray,gainHz:Float,out:FloatArray) {
        val n=iq.size/2
        var previous=Math.atan2(state[1].toDouble(),state[0].toDouble()).toFloat()
        for(i in 0 until n) {
            val phase=Math.atan2(iq[2*i+1].toDouble(),iq[2*i].toDouble()).toFloat()
            var diff=phase-previous
            if(diff>Math.PI.toFloat()) diff-=2f*Math.PI.toFloat()
            if(diff< -Math.PI.toFloat()) diff+=2f*Math.PI.toFloat()
            out[i]=diff*gainHz;previous=phase
        }
        if(n>0) { state[0]=iq[2*(n-1)];state[1]=iq[2*(n-1)+1] }
    }
    override fun applyFirDual(iq:FloatArray,taps:FloatArray,bufI:FloatArray,bufQ:FloatArray,bufPos:IntArray):FloatArray {
        val n=iq.size/2;val out=FloatArray(iq.size);val size=taps.size;var pos=bufPos[0]
        for(i in 0 until n) {
            bufI[pos]=iq[2*i];bufQ[pos]=iq[2*i+1]
            var re=0f;var im=0f
            for(k in 0 until size) { val p=(pos-k+size)%size;re+=taps[k]*bufI[p];im+=taps[k]*bufQ[p] }
            out[2*i]=re;out[2*i+1]=im;pos=(pos+1)%size
        }
        bufPos[0]=pos;return out
    }
}
