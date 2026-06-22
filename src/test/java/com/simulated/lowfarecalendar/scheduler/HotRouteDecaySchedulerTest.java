package com.simulated.lowfarecalendar.scheduler;

import com.simulated.lowfarecalendar.cache.HotRouteTracker;
import com.simulated.lowfarecalendar.config.LfcProperties;
import com.simulated.lowfarecalendar.config.LfcProperties.HotRoutesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HotRouteDecaySchedulerTest {

    @Mock private HotRouteTracker hotRouteTracker;
    @Mock private LfcProperties lfcProperties;

    @InjectMocks
    private HotRouteDecayScheduler scheduler;

    @BeforeEach
    void setUp() {
        HotRoutesProperties hotRoutesProps = new HotRoutesProperties();
        hotRoutesProps.setDecayMinScore(10L);
        when(lfcProperties.getHotRoutes()).thenReturn(hotRoutesProps);
    }

    @Test
    void decay_callsPruneBelowWithConfiguredMinScore() {
        scheduler.decay();

        verify(hotRouteTracker).pruneBelow(10.0);
    }
}
