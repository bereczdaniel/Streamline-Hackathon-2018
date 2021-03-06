package eu.streamline.hackathon.flink.scala.job.logic

import java.util.Properties

import com.google.gson.Gson
import eu.streamline.hackathon.common.data.GDELTEvent
import eu.streamline.hackathon.flink.scala.job.IO.GDELTSource
import eu.streamline.hackathon.flink.scala.job.logic.relation.scores.RelationScoring
import eu.streamline.hackathon.flink.scala.job.utils.Types.{CountryBasedInteraction, LightPostLoad, StateRequest}
import org.apache.flink.api.common.functions.{MapFunction, RichFlatMapFunction}
import org.apache.flink.api.common.serialization
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.co.RichCoFlatMapFunction
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.connectors.kafka.{FlinkKafkaConsumer011, FlinkKafkaProducer011}
import org.apache.flink.util.Collector

import scala.collection.mutable

object RelationScoreStream {

  def pipeline(pathToGDELT: String, port: String, incrementalTopic: String, fullStateTopic: String, stateReqTopic: String, lambda: Double): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    val source = GDELTSource
      .read(env, pathToGDELT)
      .filter(event => event.actor1Code_countryCode != null && event.actor2Code_countryCode != null)

    val properties = new Properties()
    properties.setProperty("bootstrap.servers", "localhost:"+port)
    properties.setProperty("group.id", "hackathon")

    val fullStateRequest = env
      .addSource(new FlinkKafkaConsumer011[String](stateReqTopic, new serialization.SimpleStringSchema(), properties))
      .filter(event => event == "yes")
      .map(_=> StateRequest())

    val common = generate(source, fullStateRequest, lambda)

    val fullState =  common
      .flatMap(new RichFlatMapFunction[Either[LightPostLoad, Array[(String, String, Double)]], String] {
        override def flatMap(value: Either[LightPostLoad, Array[(String, String, Double)]], out: Collector[String]): Unit = {
          value match {
            case Left(_) =>
            case Right(update) => out.collect(new Gson().toJson(update))
          }
        }
      })

    val incremental = common
      .flatMap(new RichFlatMapFunction[Either[LightPostLoad, Array[(String, String, Double)]], String] {
        override def flatMap(value: Either[LightPostLoad, Array[(String, String, Double)]], out: Collector[String]): Unit = {
          value match {
            case Left(update) =>
              out.collect(new Gson().toJson(update))
              //With TimeCharacteristic = ProcessingTime changes would be too fast
              Thread.sleep(30)
            case Right(_) =>
          }
        }
      })

    fullState
      .addSink(new FlinkKafkaProducer011[String]("localhost:"+port, fullStateTopic, new SimpleStringSchema()))

    incremental
      .addSink(new FlinkKafkaProducer011[String]("localhost:"+port, incrementalTopic, new SimpleStringSchema()))

    env.execute()
  }

  def generate(src: DataStream[GDELTEvent],stateRequest: DataStream[StateRequest], lambda: Double): DataStream[Either[LightPostLoad,  Array[(String, String, Double)]]] = {
    src
      .map(new MapFunction[GDELTEvent, CountryBasedInteraction] {

        private var lastTimeStamp: Long = 1490738400000L
        override def map(event: GDELTEvent): CountryBasedInteraction = {
          val (a1,a2) = {
            if(event.actor1Code_countryCode > event.actor2Code_countryCode)
              (event.actor1Code_countryCode, event.actor2Code_countryCode)
            else
              (event.actor2Code_countryCode, event.actor1Code_countryCode)
          }

          CountryBasedInteraction(
            a1, a2,
            event.quadClass,
            try{
              lastTimeStamp = event.day.getTime
              event.day.getTime
            }
            catch {
              case _: NullPointerException => lastTimeStamp
            },
            Map(1 -> 0.01, 2 -> 0.02, 3 -> -0.01, 4 -> -0.02),
            RelationScoring.simpleQuadTranslate
          )
        }
      })
      .keyBy(event => (event.actor1CountryCode, event.actor2CountryCode))
      .connect(stateRequest.broadcast)
      .flatMap(new RichCoFlatMapFunction[CountryBasedInteraction, StateRequest, Either[LightPostLoad,  Array[(String, String, Double)]]] {
        private lazy val state: mutable.HashMap[(String, String), (Double, Double, Long)] = new mutable.HashMap[(String, String), (Double, Double, Long)]()

        override def flatMap1(value: CountryBasedInteraction, out: Collector[Either[LightPostLoad, Array[(String, String, Double)]]]): Unit = {
          val key = (value.actor1CountryCode, value.actor2CountryCode)
          val agg = state.getOrElseUpdate(key, (0.0, 0.0, value.ts))
          val score = calculateUpdate(agg._1, value.score, agg._3, value.ts, lambda)
          val norm = calculateUpdate(agg._2, 1.0, agg._3, value.ts, lambda)

          state.update(key, (score, norm, value.ts))

          out.collect(Left(LightPostLoad(value.actor1CountryCode, value.actor2CountryCode, score / norm)))
        }

        override def flatMap2(value: StateRequest, out: Collector[Either[LightPostLoad, Array[(String, String, Double)]]]): Unit =
          out.collect(Right((for( (k,v) <- state) yield (k._1, k._2, v._1)).toArray))
      })
  }

  def calculateUpdate(aggScore: Double, currentScore: Double, lastTS: Long, currentTS: Long, lambda: Double): Double = {
    var score = math.max(math.min( aggScore * scala.math.exp(-lambda * (currentTS - lastTS)), 100.0), -100.0)
    score =
      if(score.isNaN)
        0.0
      else
        score

    score + currentScore
  }
}
