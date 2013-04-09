package com.hpcloud.maas.util.stats;

import javax.annotation.concurrent.NotThreadSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hpcloud.maas.util.time.TimeResolution;

/**
 * A time based sliding window containing statistics for a fixed number of slots of a fixed length.
 * The window provides a fixed size view over the total number of slots in the window.
 * 
 * @author Jonathan Halterman
 */
@NotThreadSafe
public class SlidingWindowStats {
  private static final Logger LOG = LoggerFactory.getLogger(SlidingWindowStats.class);

  private final TimeResolution timescale;
  private final long slotWidth;
  private final int numViewSlots;
  private final long windowLength;
  private final Slot[] slots;

  private long viewEndTimestamp;
  private long slotEndTimestamp;
  private long windowEndTimestamp;
  private int lastViewIndex;
  private int windowBeginIndex;

  private static class Slot {
    private long timestamp;
    private Statistic stat;

    private Slot(long timestamp, Statistic stat) {
      this.timestamp = timestamp;
      this.stat = stat;
    }

    @Override
    public String toString() {
      return timestamp + "=" + stat;
    }
  }

  /**
   * Creates a time based SlidingWindowStats containing a fixed {@code numSlots} representing a view
   * up to the {@code initialViewTimestamp}, and an additional {@code numFutureSlots} for timestamps
   * beyond the current window view.
   * 
   * It is recommended to make the {@code viewEndTimestamp} one time unit less than the current
   * time, so that as the window slides to the right any added values will slide all the way across
   * the view.
   * 
   * @param statType to calculate values for
   * @param timeResolution to adjust timestamps with
   * @param slotWidth time-based width of the slot
   * @param numViewSlots the number of viewable slots
   * @param numFutureSlots the number of future slots to allow values for
   * @param viewEndTimestamp timestamp to end view at
   */
  public SlidingWindowStats(Class<? extends Statistic> statType, TimeResolution timeResolution,
      long slotWidth, int numViewSlots, int numFutureSlots, long viewEndTimestamp) {
    this.timescale = timeResolution;
    this.slotWidth = slotWidth;
    this.numViewSlots = numViewSlots;
    this.windowLength = (numViewSlots + numFutureSlots) * slotWidth;

    this.viewEndTimestamp = timeResolution.adjust(viewEndTimestamp);
    slotEndTimestamp = this.viewEndTimestamp;
    windowEndTimestamp = this.viewEndTimestamp + (numFutureSlots * slotWidth);
    lastViewIndex = numViewSlots - 1;

    slots = new Slot[numViewSlots + numFutureSlots];
    long timestamp = windowEndTimestamp - slotWidth;
    for (int i = numViewSlots + numFutureSlots - 1; i > -1; i--, timestamp -= slotWidth)
      slots[i] = createSlot(timestamp, statType);
  }

  /** Returns a new slot for the {@code timestamp} and {@code statType}. */
  private static Slot createSlot(long timestamp, Class<? extends Statistic> statType) {
    try {
      return new Slot(timestamp, statType.newInstance());
    } catch (Exception e) {
      LOG.error("Failed to initialize slot", e);
      return null;
    }
  }

  /**
   * Adds the {@code value} to the statistics for the slot associated with the {@code timestamp},
   * else <b>does nothing</b> if the {@code timestamp} is outside of the window.
   * 
   * @param value to add
   * @param timestamp to add value for
   */
  public void addValue(double value, long timestamp) {
    timestamp = timescale.adjust(timestamp);
    int index = indexOfTime(timestamp);
    if (index == -1)
      LOG.warn("Timestamp {} is outside of window {}", timestamp, toString());
    else {
      slots[index].stat.addValue(value);
      LOG.trace("Added value for {}. New window {}", timestamp, toString());
    }
  }

  /** Returns the number of slots in the window. */
  public int getSlotCount() {
    return slots.length;
  }

  /** Returns the window's slot width. */
  public long getSlotWidth() {
    return slotWidth;
  }

  /**
   * Returns the timestamps represented by the current position of the sliding window increasing
   * from oldest to newest.
   */
  public long[] getTimestamps() {
    long[] timestamps = new long[numViewSlots];
    long timestamp = windowEndTimestamp - ((slots.length - 1) * slotWidth);
    for (int i = 0; i < numViewSlots; i++, timestamp += slotWidth)
      timestamps[i] = timestamp;
    return timestamps;
  }

  /**
   * Returns the value for the window slot associated with {@code timestamp}.
   * 
   * @param timestamp to get value for
   * @throws IllegalStateException if no value is within the window for the {@code timestamp}
   */
  public double getValue(long timestamp) {
    timestamp = timescale.adjust(timestamp);
    int index = indexOfTime(timestamp);
    if (index == -1)
      throw new IllegalStateException(timestamp + " is outside of the window");
    return slots[index].stat.value();
  }

  /**
   * Returns the values for the window up to and including the {@code timestamp}. Values for
   * uninitialized slots will be Double.NaN.
   * 
   * @param timestamp to get value for
   * @throws IllegalStateException if no value is within the window for the {@code timestamp}
   */
  public double[] getValuesUpTo(long timestamp) {
    timestamp = timescale.adjust(timestamp);
    int endIndex = indexOfTime(timestamp);
    if (endIndex == -1)
      throw new IllegalStateException(timestamp + " is outside of the window");
    double[] values = new double[lengthToIndex(endIndex)];
    for (int i = 0, index = windowBeginIndex; i < values.length; i++, index = indexAfter(index))
      if (slots[index] != null)
        values[i] = slots[index].stat.value();
    return values;
  }

  /**
   * Returns the values of the sliding view increasing from oldest to newest.
   */
  public double[] getViewValues() {
    double[] values = new double[numViewSlots];
    for (int i = 0, index = windowBeginIndex; i < numViewSlots; i++, index = indexAfter(index))
      if (slots[index] != null)
        values[i] = slots[index].stat.value();
    return values;
  }

  /**
   * Returns the values of the sliding window increasing from oldest to newest.
   */
  public double[] getWindowValues() {
    double[] values = new double[slots.length];
    for (int i = 0, index = windowBeginIndex; i < slots.length; i++, index = indexAfter(index))
      if (slots[index] != null)
        values[i] = slots[index].stat.value();
    return values;
  }

  /**
   * Slides window's view to the slot for the {@code timestamp}, erasing values for any slots along
   * the way.
   * 
   * @param timestamp slide view to
   */
  public void slideViewTo(long timestamp) {
    timestamp = timescale.adjust(timestamp);
    if (timestamp <= viewEndTimestamp)
      return;
    long timeDiff = timestamp - slotEndTimestamp;
    int slotsToAdvance = (int) (timeDiff / slotWidth);
    slotsToAdvance += timeDiff % slotWidth == 0 ? 0 : 1;

    for (int i = 0; i < slotsToAdvance; i++) {
      windowBeginIndex = indexAfter(windowBeginIndex);
      Slot slot = slots[indexOf(slots.length - 1)];
      slot.timestamp = windowEndTimestamp;
      slot.stat.reset();

      slotEndTimestamp += slotWidth;
      windowEndTimestamp += slotWidth;
      lastViewIndex = indexAfter(lastViewIndex);
    }

    viewEndTimestamp = timestamp;
  }

  /**
   * Returns a logical view of the sliding window with increasing timestamps from left to right.
   */
  @Override
  public String toString() {
    StringBuilder b = new StringBuilder();
    b.append("SlidingWindowStats [[");
    for (int i = 0, index = windowBeginIndex; i < slots.length; i++, index = indexAfter(index)) {
      if (i == numViewSlots)
        b.append("], ");
      else if (i != 0)
        b.append(", ");
      b.append(slots[index]);
    }

    return b.append(']').toString();
  }

  /**
   * Returns the physical index of the logical {@code slotIndex} calculated from the
   * {@code windowBeginIndex}.
   */
  int indexOf(int slotIndex) {
    int offset = windowBeginIndex + slotIndex;
    if (offset >= slots.length)
      offset -= slots.length;
    return offset;
  }

  /**
   * Returns physical index of the slot associated with the {@code timestamp}, else -1 if the
   * {@code timestamp} is outside of the window. Slots increase in time from left to right,
   * wrapping.
   */
  int indexOfTime(long timestamp) {
    if (timestamp < windowEndTimestamp) {
      long windowStartTimestamp = windowEndTimestamp - windowLength;
      int timeDiff = (int) (timestamp - windowStartTimestamp);
      if (timeDiff >= 0) {
        int logicalIndex = (int) (timeDiff / slotWidth);
        return indexOf(logicalIndex);
      }
    }

    return -1;
  }

  /** Returns the length of the window up to and including the physical {@code slotIndex}. */
  int lengthToIndex(int slotIndex) {
    if (windowBeginIndex <= slotIndex)
      return slotIndex - windowBeginIndex + 1;
    else
      return slotIndex + slots.length - windowBeginIndex + 1;
  }

  /** Returns the physical index for the slot logically positioned after the {@code index}. */
  private int indexAfter(int index) {
    return ++index == slots.length ? 0 : index;
  }
}
