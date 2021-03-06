/*
 * Copyright (c) 2014 Hewlett-Packard Development Company, L.P.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monasca.thresh.domain.model;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import monasca.common.model.alarm.AlarmState;
import monasca.common.model.alarm.AlarmSubExpression;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.UUID;

@Test
public class SubAlarmStatsTest {
  private SubExpression expression;
  private SubAlarm subAlarm;
  private SubAlarmStats subAlarmStats;

  @BeforeMethod
  protected void beforeMethod() {
    expression =
        new SubExpression(UUID.randomUUID().toString(),
            AlarmSubExpression.of("avg(hpcs.compute.cpu{id=5}, 60) > 3 times 3"));
    subAlarm = new SubAlarm("123", "1", expression);
    subAlarm.setNoState(true);
    subAlarmStats = new SubAlarmStats(subAlarm, expression.getAlarmSubExpression().getPeriod());
  }

  public void shouldBeOkIfAnySlotsInViewAreBelowThreshold() {
    subAlarmStats.getStats().addValue(5, 1);
    assertFalse(subAlarmStats.evaluateAndSlideWindow(62, 1));
    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.UNDETERMINED);

    subAlarmStats.getStats().addValue(1, 62);
    assertTrue(subAlarmStats.evaluateAndSlideWindow(122, 1));
    // This went to OK because at least one period is under the threshold
    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.OK);

    subAlarmStats.getStats().addValue(5, 123);
    assertFalse(subAlarmStats.evaluateAndSlideWindow(182, 1));
    // Still one under the threshold
    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.OK);
  }

  public void shouldBeAlarmedIfAllSlotsInViewExceedThreshold() {
    subAlarmStats.getStats().addValue(5, 1);
    assertFalse(subAlarmStats.evaluateAndSlideWindow(62, 1));
    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.UNDETERMINED);

    subAlarmStats.getStats().addValue(5, 62);
    assertFalse(subAlarmStats.evaluateAndSlideWindow(122, 1));
    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.UNDETERMINED);

    subAlarmStats.getStats().addValue(5, 123);
    assertTrue(subAlarmStats.evaluateAndSlideWindow(182, 1));
    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.ALARM);
  }

  /**
   * Simulates the way a window will fill up in practice.
   */
  public void shouldEvaluateAndSlideWindow() {
    long initialTime = 11;

    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.UNDETERMINED);
    assertFalse(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));
    assertFalse(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));
    assertFalse(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));

    // Add value and trigger OK
    subAlarmStats.getStats().addValue(1, initialTime - 1);
    assertTrue(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));
    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.OK);

    // Slide in some values that exceed the threshold
    subAlarmStats.getStats().addValue(5, initialTime - 1);
    assertFalse(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));
    subAlarmStats.getStats().addValue(5, initialTime - 1);
    assertFalse(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));
    subAlarmStats.getStats().addValue(5, initialTime - 1);

    // Trigger ALARM
    assertTrue(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));
    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.ALARM);

    // Add value and trigger OK
    subAlarmStats.getStats().addValue(1, initialTime - 1);
    assertTrue(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));
    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.OK);

    // Must slide 9 times total from the last added value to trigger UNDETERMINED. This is
    // equivalent to the behavior in CloudWatch for an alarm with 3 evaluation periods. 2 more
    // slides to move the value outside of the window and 6 more to exceed the observation
    // threshold.
    for (int i = 0; i < 7; i++) {
      assertFalse(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));
    }
    assertTrue(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));
    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.UNDETERMINED);
    subAlarmStats.getStats().addValue(5, initialTime - 1);
  }

  public void shouldAlarmIfAllSlotsAlarmed() {
    long initialTime = 11;

    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.UNDETERMINED);

    assertFalse(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));

    subAlarmStats.getStats().addValue(5, initialTime - 1);
    assertFalse(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));

    subAlarmStats.getStats().addValue(5, initialTime - 1);
    assertFalse(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));

    subAlarmStats.getStats().addValue(5, initialTime - 1);
    assertTrue(subAlarmStats.evaluateAndSlideWindow(initialTime += 60, 1));
    assertEquals(subAlarmStats.getSubAlarm().getState(), AlarmState.ALARM);
  }

  public void testEmptyWindowObservationThreshold() {
    expression =
        new SubExpression(UUID.randomUUID().toString(),
            AlarmSubExpression.of("avg(hpcs.compute.cpu{id=5}) > 3 times 3"));
    subAlarm = new SubAlarm("123", "1", expression);
    SubAlarmStats saStats = new SubAlarmStats(subAlarm, (System.currentTimeMillis() / 1000) + 60);
    assertEquals(saStats.emptyWindowObservationThreshold, 6);
  }

  public void checkLongPeriod() {
    final SubExpression subExpr = new SubExpression(UUID.randomUUID().toString(),
        AlarmSubExpression.of("sum(hpcs.compute.mem{id=5}, 120) >= 96"));

    final SubAlarm subAlarm = new SubAlarm("42", "4242", subExpr);

    long t1 = 0;
    final SubAlarmStats stats = new SubAlarmStats(subAlarm, t1 + subExpr.getAlarmSubExpression().getPeriod());
    for (int i = 0; i < 360; i++) {
      t1++;
      stats.getStats().addValue(1.0, t1);
      if ((t1 % 60) == 2) {
        stats.evaluateAndSlideWindow(t1, 1);
        if (i <= subExpr.getAlarmSubExpression().getPeriod()) {
          // Haven't waited long enough to evaluate
          assertEquals(stats.getSubAlarm().getState(), AlarmState.UNDETERMINED);
        } else {
          assertEquals(stats.getSubAlarm().getState(), AlarmState.ALARM);
        }
      }
    }
  }
}
