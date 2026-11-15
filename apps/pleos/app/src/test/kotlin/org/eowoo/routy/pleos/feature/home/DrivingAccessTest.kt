package org.eowoo.routy.pleos.feature.home

import org.eowoo.routy.pleos.feature.trip.DrivingAccessState
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrivingAccessTest {
    @Test fun unknownVehicleStateDoesNotAllowSetup(){assertFalse(DrivingAccessState().parked)}
    @Test fun parkAndUnrestrictedUxAllowSetup(){assertTrue(DrivingAccessState(true,false,0,4).parked)}
    @Test fun driveAtZeroSpeedDoesNotAllowSetup(){assertFalse(DrivingAccessState(true,false,0,8).parked)}
    @Test fun parkDoesNotOverrideUxRestriction(){assertFalse(DrivingAccessState(true,true,80,4).parked)}
    @Test fun featureRestrictionWithoutDoFlagStillHidesSetup(){assertFalse(DrivingAccessState(true,false,64,4).parked)}
    @Test fun missingGearIsNotAssumedToBePark(){assertFalse(DrivingAccessState(true,false,0,null).parked)}
    @Test fun disconnectedUxIsNotAssumedToBeUnrestricted(){assertFalse(DrivingAccessState(false,false,0,4).parked)}
}
