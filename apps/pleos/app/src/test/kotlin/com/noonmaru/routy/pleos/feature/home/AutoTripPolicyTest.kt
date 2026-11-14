package com.noonmaru.routy.pleos.feature.home

import com.noonmaru.routy.pleos.feature.trip.AutoTripPolicy
import com.noonmaru.routy.pleos.feature.trip.AutoTripPolicy.Action.*
import org.junit.Test
import kotlin.test.*

class AutoTripPolicyTest {
    private val policy=AutoTripPolicy()
    private fun tick(gear:Int?,time:Long,active:Boolean=true,enabled:Boolean=true,busy:Boolean=false)=policy.advance(enabled,gear,active,busy,time)
    @Test fun startsOnlyInDrive(){for(g in listOf(null,4,2,1))assertEquals(NONE,tick(g,0,false));assertEquals(START,tick(8,1,false))}
    @Test fun driveNotificationsDoNotDuplicateStart(){assertEquals(START,tick(8,0,false));assertEquals(NONE,tick(8,1,false));assertEquals(NONE,tick(8,2))}
    @Test fun reverseAndNeutralKeepTheSameSession(){assertEquals(START,tick(8,0,false));for(g in listOf(2,1,8,2,8))assertEquals(NONE,tick(g,20_000))}
    @Test fun exactlyTenSecondsOfParkIsRequired(){assertEquals(NONE,tick(4,1000));assertEquals(NONE,tick(4,10_999));assertEquals(FINISH,tick(4,11_000))}
    @Test fun returningToDriveResetsParkWait(){tick(4,0);tick(8,9000);tick(4,10_000);assertEquals(NONE,tick(4,19_999));assertEquals(FINISH,tick(4,20_000))}
    @Test fun reverseAndNeutralResetParkWait(){for(g in listOf(2,1)){tick(4,0);tick(g,9000);tick(4,10_000);assertEquals(NONE,tick(4,19_999));assertEquals(FINISH,tick(4,20_000))}}
    @Test fun missingGearNeverCountsAsPark(){tick(4,0);tick(null,9000);tick(4,10_000);assertEquals(NONE,tick(4,19_999))}
    @Test fun disabledDoesNotStart(){assertEquals(NONE,tick(8,0,false,false))}
    @Test fun disablingFinishesExistingRecord(){assertEquals(FINISH,tick(4,0,true,false));assertEquals(NONE,tick(8,1,false,false))}
    @Test fun savingCannotStartOrFinishAgain(){assertEquals(NONE,tick(8,0,false,true,true));assertEquals(NONE,tick(4,20_000,true,false,true))}
    @Test fun nextDriveStartsAfterParkCompletion(){tick(8,0,false);tick(4,100);assertEquals(FINISH,tick(4,10_100));assertEquals(START,tick(8,11_000,false))}
    @Test fun repeatedDriveAfterAnExternalStopNeedsParkBeforeRestart(){tick(8,0,false);assertEquals(NONE,tick(8,600_000,false));tick(4,601_000,false);assertEquals(START,tick(8,602_000,false))}
    @Test fun twoHourDriveHasNoDeadlineAndStillRequiresContinuousPark(){
        assertEquals(START,tick(8,0,false))
        for(time in 250L..7_200_000L step 250)assertEquals(NONE,tick(when((time/600_000)%3){1L->2;2L->1;else->8},time))
        assertEquals(NONE,tick(4,7_200_001))
        assertEquals(NONE,tick(4,7_210_000))
        assertEquals(FINISH,tick(4,7_210_001))
    }
}
