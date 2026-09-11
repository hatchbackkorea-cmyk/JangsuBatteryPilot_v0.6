package com.seungjae.jangsu280battery
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*
class GateTimingMathTest {
    private val base=10_000_000_000L
    private fun motion(v:Double=2.78, brake:Boolean=false, hz:Int=5, acc:Double=1.0):List<GateTimingMath.Fix> =
        (-hz*2..hz*2).map { i ->
            val t=i.toDouble()/hz+0.05
            val d=if (brake&&t>0.25) v*0.25+(t-0.25)*0.3 else v*t
            GateTimingMath.Fix(base+(t*1e9).roundToLong(),d,0.0,acc,if(brake&&t>0.25)0.3 else v)
        }
    @Test fun constantMotion(){val e=GateTimingMath.evaluate(motion(),base,10.0);assertTrue(e.usable);assertTrue(abs(e.ns-base)<=1_000_000L)}
    @Test fun brakingAfterFinishCannotRetime(){val a=GateTimingMath.evaluate(motion(),base,10.0);val b=GateTimingMath.evaluate(motion(brake=true),base,10.0);assertEquals(a.ns,b.ns)}
    @Test fun accelerationAfterFinishCannotRetime(){val a=motion();val b=a.map{if(it.ns>base+250_000_000L)it.copy(along=it.along+8.0,speed=15.0) else it};assertEquals(GateTimingMath.evaluate(a,base,10.0).ns,GateTimingMath.evaluate(b,base,10.0).ns)}
    @Test fun largeMarginIsNotCapped(){assertTrue(GateTimingMath.evaluate(motion(acc=20.0),base,10.0).marginMs!!>2000L)}
    @Test fun absentFixesAreUnknown(){assertNull(GateTimingMath.evaluate(motion().take(2),base,10.0).marginMs)}
    @Test fun duplicateFixesDoNotIncreaseConfidence(){val a=motion();assertEquals(GateTimingMath.evaluate(a,base,10.0).marginMs,GateTimingMath.evaluate(a+a,base,10.0).marginMs)}
    @Test fun noSignedCrossingCannotBeInvented(){assertNull(GateTimingMath.evaluate(motion().map{it.copy(along=0.0)},base,10.0).marginMs)}
    @Test fun reverseCrossingIsUnknown(){assertNull(GateTimingMath.evaluate(motion().map{it.copy(along=-it.along)},base,10.0).marginMs)}
    @Test fun strictWidthIsTheSameForAllPhones(){assertNull(GateTimingMath.evaluate(motion().map{it.copy(across=8.0,accuracy=20.0)},base,10.0).marginMs)}
    @Test fun oneHzSupportedWithoutInventingSamples(){val e=GateTimingMath.evaluate(motion(hz=1),base,10.0);assertTrue(e.usable);assertTrue(abs(e.ns-base)<=1_000_000L)}
    @Test fun fixedPositionBiasDoesNotGetAveragedAway(){assertTrue(GateTimingMath.evaluate(motion().map{it.copy(along=it.along+0.5)},base,10.0).marginMs!!>500L)}
    @Test fun lowNormalSpeedUnknownRatherThanDisqualification(){assertNull(GateTimingMath.evaluate(motion(v=0.2),base,10.0).marginMs)}
    @Test fun correctionLimitRejectsUnusableCrossing(){assertFalse(GateTimingMath.evaluate(motion(),base-1_100_000_000L,10.0).usable)}
    @Test fun mockSourceNotRankable(){assertFalse(GateTimingMath.evaluate(motion().map{it.copy(mock=true)},base,10.0).usable)}
}
