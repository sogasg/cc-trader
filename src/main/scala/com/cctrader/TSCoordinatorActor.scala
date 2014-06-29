package com.cctrader

import java.util.{Date, UUID}

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.cctrader.data._

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 *
 */
trait TSCoordinatorActor extends Actor with ActorLogging {

  val id = UUID.randomUUID()
  val name: String
  val dataAvailable: DataReady
  val dataActor: ActorRef
  var tradingSystemActor: ActorRef = _
  var nextTradingSystem: ActorRef = _
  var tradingSystemTime: Date
  val marketDataSettings: MarketDataSettings
  var mode = Mode.TESTING
  var marketDataSet: MarketDataSet = null
  var liveDataActor: ActorRef = _
  val numberOfLivePointsAtTheTimeForBackTest: Int
  val signalWriter: SignalWriterTrait
  var transferToNextSystemDate: Date
  var nextSystemReady: Boolean
  val tsNumberOfPointsToProcessBeforeStartTrainingNewSystem: Int
  var numberOfPointsProcessedByCurrentSystem = 0
  var hasRunningTS = false
  var messageDPCount = 0
  var countTradingSystemsUsed = 0

  dataActor ! marketDataSettings

  /**
   * Should define the TradingSystemActors props.
   * Ex: DummyTSActor.props(MarketDataSet(marketDataSet.iterator.toList, marketDataSet.settings), signalWriter)
   * @return the props to use when creating an instance of the tradingSystemActor
   */
  def tsProps: Props

  implicit val timeout = Timeout(10 minutes)

  def startTradingSystemActor: ActorRef = context.actorOf(tsProps, name + "-ts-" + countTradingSystemsUsed)

  def newCopyOfMarketDataSet(setToCopy: MarketDataSet): MarketDataSet = MarketDataSet(setToCopy.list.clone().toList, setToCopy.settings.copy())

  def startAndTrainNewSystem(marketDataSetForTraining: MarketDataSet) {
    countTradingSystemsUsed = countTradingSystemsUsed + 1
    // starting a new system
    log.debug("Start and train, nextTradingSystem actor")
    nextTradingSystem = startTradingSystemActor
    if (mode == Mode.TESTING) {
      //wait for training to complete
      val askFuture = nextTradingSystem ? StartTraining(marketDataSetForTraining)
      val trainingDone = Await.result(askFuture, timeout.duration).asInstanceOf[TrainingDone]

      transferToNextSystemDate = new Date(tradingSystemTime.getTime + trainingDone.trainingTimeInMilliSec) //cant use the data collected during training. (Before the TS was ready)
      nextSystemReady = true
    }
    else {
      // LIVE
      nextTradingSystem ! StartTraining(marketDataSetForTraining)
      nextTradingSystem ! Mode.LIVE
    }
    // if this is the first system
    if (!hasRunningTS) {
      if (mode == Mode.TESTING) {
        //tradingSystemActor = nextTradingSystem
        liveDataActor ! RequestLiveBTData(tradingSystemTime, numberOfLivePointsAtTheTimeForBackTest)
      }
      else {
        liveDataActor ! RequestLiveData(tradingSystemTime)
      }
    }
  }

  override def receive: Receive = {
    //received data for training. First time
    case init: Initialize =>
      marketDataSet = newCopyOfMarketDataSet(init.marketDataSet)
      liveDataActor = init.liveDataActorRef
      log.info("Received Initialize message with marketDataSet: size:" + marketDataSet.size + ", fromDate" + marketDataSet.fromDate
        + ", toDate" + marketDataSet.toDate)
      startAndTrainNewSystem(newCopyOfMarketDataSet(init.marketDataSet))

    case trainingDone: TrainingDone =>
      // some system is finished with training and ready to start trading
      // this is only used when mode is LIVE, else the startAndTrainNewSystem is awaiting this message
      nextSystemReady = true

    case newDataPoint: DataPoint =>
      log.debug("Received: newDataPoint")
      if (tradingSystemTime.after(newDataPoint.date)) {
        throw new Exception("The *new* dataPoint is older then the last. Last:" + tradingSystemTime + ", this:" + newDataPoint.date)
      }
      messageDPCount = messageDPCount + 1
      tradingSystemTime = newDataPoint.date
      marketDataSet.addDataPoint(newDataPoint)

      // start using nextSystem and kill old
      if (nextSystemReady && (mode == Mode.LIVE || newDataPoint.date.after(transferToNextSystemDate))) {
        if (hasRunningTS) {
          log.debug("Sending PoisonPill to (current) tradingSystem")
          tradingSystemActor ! PoisonPill
        }
        log.debug("Transferring to nextTradingSystemActor")
        tradingSystemActor = nextTradingSystem
        tradingSystemActor ! AkkOn(numberOfLivePointsAtTheTimeForBackTest, messageDPCount)
        tradingSystemActor ! newCopyOfMarketDataSet(marketDataSet)
        numberOfPointsProcessedByCurrentSystem = 0
        nextSystemReady = false
        hasRunningTS = true
      }
      if (numberOfPointsProcessedByCurrentSystem == tsNumberOfPointsToProcessBeforeStartTrainingNewSystem) {
        log.debug("Starts a new tradingSystem")
        startAndTrainNewSystem(newCopyOfMarketDataSet(marketDataSet))
      }
      if (hasRunningTS) {
        numberOfPointsProcessedByCurrentSystem = numberOfPointsProcessedByCurrentSystem + 1
        tradingSystemActor ! newDataPoint
      }

    case Mode.LIVE =>
      mode = Mode.LIVE
      tradingSystemActor ! Mode.LIVE

    case akk: AkkOn =>
      log.debug("Received: AkkOn")
      messageDPCount = 0
      liveDataActor ! RequestLiveBTData(tradingSystemTime, numberOfLivePointsAtTheTimeForBackTest)

  }

}