package com.cctrader.indicators.technical

import com.cctrader.data.MarketDataSet
import com.cctrader.indicators.InputIndicator

/**
 *
 * @param n the number of indexes to "look-back"
 */
class StochasticK(n: Int) extends InputIndicator{
  /**
   * Calculating the indicator.
   *
   * @param t index of time now in the marketDataSet
   * @param data the marketDataSet
   * @return Stochastic %K
   */
  def apply(t: Int, data: MarketDataSet): Double = {
    // finding the lowest low and highest high
    var highestHighPrice = data(t-n).high
    var lowestLowPrice = data(t-n).low
    for (i <- (t-n+1) to t) {
      if(data(i).high > highestHighPrice){
        highestHighPrice = data(i).high
      }
      if (data(i).low < lowestLowPrice) {
        lowestLowPrice = data(i).low
      }
    }

    // calculating Stochastic %K
    val stockK = (data(t).close - lowestLowPrice) / (highestHighPrice-lowestLowPrice)
    //scaling
    stockK-0.5
  }
}
