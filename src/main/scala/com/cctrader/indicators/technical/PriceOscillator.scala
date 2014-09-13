package com.cctrader.indicators.technical

import com.cctrader.data.MarketDataSet
import com.cctrader.indicators.InputIndicator

/**
 *
 */
class PriceOscillator(fastN: Int, slowN: Int) extends InputIndicator{
  /**
   * Calculating the indicator.
   *
   * @param t index of time now in the marketDataSet
   * @param data the marketDataSet
   * @return
   */
  override def apply(t: Int, data: MarketDataSet): Double = {
    val fastMovingAverage = new MovingAveragePrice(fastN)
    val slowMovingAverage = new MovingAveragePrice(slowN)

    (fastMovingAverage.apply(t, data)- slowMovingAverage.apply(t, data)) / fastMovingAverage.apply(t, data)
  }
}
