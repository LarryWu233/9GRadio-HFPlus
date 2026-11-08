package com.radiosport.ninegradio.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class CwChannelizerOwnershipTest {
    private class Plan:ComplexIqResampler {
        var closes=0
        override fun process(iq:FloatArray)=iq
        override fun reset()=Unit
        override fun close() { closes++ }
    }
    @Test fun configurationResetAndCloseReleaseAllFactoryPlans() {
        val plans=mutableListOf<Plan>()
        val branch=CwChannelizer(resamplers=ComplexIqResamplerFactory { _,_,_ -> Plan().also { plans+=it } })
        branch.process(FloatArray(32),768000,8000,500)
        val first=plans.toList();assertTrue(first.size>1)
        branch.process(FloatArray(32),768000,8000,500)
        assertEquals(first.size,plans.size);assertTrue(first.all { it.closes==0 })
        branch.process(FloatArray(32),768000,8000,700)
        assertTrue(first.all { it.closes==1 })
        branch.reset();assertTrue(plans.all { it.closes==1 })
        branch.process(FloatArray(32),768000,8000,700)
        branch.close();branch.close();assertTrue(plans.all { it.closes==1 })
        assertThrows(IllegalStateException::class.java) { branch.process(FloatArray(32),768000,8000,700) }
    }
    @Test fun partialFactoryFailureClosesNewPlansWithoutLosingTheOldOwner() {
        val plans=mutableListOf<Plan>();var failAt=Int.MAX_VALUE
        val branch=CwChannelizer(resamplers=ComplexIqResamplerFactory { _,_,_ ->
            if(plans.size==failAt) error("injected factory failure")
            Plan().also { plans+=it }
        })
        branch.process(FloatArray(32),768000,8000,500)
        val old=plans.toList();failAt=old.size+2
        assertThrows(IllegalStateException::class.java) { branch.process(FloatArray(32),1920000,8000,500) }
        assertTrue(old.all { it.closes==0 })
        assertTrue(plans.drop(old.size).all { it.closes==1 })
        branch.close();assertTrue(plans.all { it.closes==1 })
    }
    @Test fun injectedReferencePreservesChunkBoundariesAndInputOwnership() {
        val input=FloatArray(120000) { sin(it*.023).toFloat() }
        val unchanged=input.copyOf()
        val whole=CwChannelizer().use { it.process(input,240000,8000,500) }
        val chunks=mutableListOf<FloatArray>()
        CwChannelizer(resamplers=ReferenceIqResamplers).use { branch ->
            var index=0
            while(index<input.size) {
                val count=minOf(listOf(2,74,8192,1006)[chunks.size%4],input.size-index)
                chunks+=branch.process(input.copyOfRange(index,index+count),240000,8000,500).copyOf()
                index+=count
            }
        }
        val combined=FloatArray(chunks.sumOf { it.size });var cursor=0
        chunks.forEach { it.copyInto(combined,cursor);cursor+=it.size }
        assertArrayEquals(whole,combined,0f);assertArrayEquals(unchanged,input,0f)
    }
}
