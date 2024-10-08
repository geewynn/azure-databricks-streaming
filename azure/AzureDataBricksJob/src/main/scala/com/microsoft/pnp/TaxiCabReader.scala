package com.microsoft.pnp

import com.datastax.spark.connector.cql.CassandraConnector
import org.apache.spark.eventhubs.{EventHubsConf, EventPosition}
import org.apache.spark.metrics.source.{AppAccumulators, AppMetrics}
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types.{StringType, StructType}
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import org.apache.spark.{SparkConf, SparkEnv}

object TaxiCabReader {
  private def withExpr(expr: Expression): Column = new Column(expr)

  def main(args: Array[String]) {
    val conf = new JobConfiguration(args)
    val rideEventHubConnectionString = getSecret(
      conf.secretScope(), conf.taxiRideEventHubSecretName())
    val fareEventHubConnectionString = getSecret(
      conf.secretScope(), conf.taxiFareEventHubSecretName())

    val cassandraEndPoint = conf.cassandraHost()

    val cassandraUserName = getSecret(
      conf.secretScope(), conf.cassandraUserSecretName())
    val cassandraPassword = getSecret(
      conf.secretScope(), conf.cassandraPasswordSecretName())

    val spark = SparkSession
      .builder()
      .appName("TaxiCabReader")
      .getOrCreate()

    import spark.implicits._

    val sparkConfForCassandraDriver = new SparkConf(true)
      .set("spark.cassandra.connection.host", cassandraEndPoint)
      .set("spark.cassandra.connection.port", "10350")
      .set("spark.cassandra.connection.ssl.enabled", "true")
      .set("spark.cassandra.auth.username", cassandraUserName)
      .set("spark.cassandra.auth.password", cassandraPassword)
      .set("spark.master", "local[10]")
      .set("spark.cassandra.output.batch.size.rows", "1")
      .set("spark.cassandra.connection.remoteConnectionsPerExecutor", "2")
      .set("spark.cassandra.output.concurrent.writes", "5")
      .set("spark.cassandra.output.batch.grouping.buffer.size", "300")
      .set("spark.cassandra.connection.keepAliveMS", "5000")

    val connector = CassandraConnector(sparkConfForCassandraDriver)

    @transient val appMetrics = new AppMetrics(spark.sparkContext)
    appMetrics.registerGauge("metrics.malformedrides", AppAccumulators.getRideInstance(spark.sparkContext))
    appMetrics.registerGauge("metrics.malformedfares", AppAccumulators.getFareInstance(spark.sparkContext))
    SparkEnv.get.metricsSystem.registerSource(appMetrics)

    @transient lazy val NeighborhoodFinder = GeoFinder.createGeoFinder(conf.neighborhoodFileURL())

    val neighborhoodFinder = (lon: Double, lat: Double) => {
      NeighborhoodFinder.getNeighborhood(lon, lat).get()
    }
    val to_neighborhood = spark.udf.register("neighborhoodFinder", neighborhoodFinder)

    spark.streams.addListener(new StreamingMetricsListener())

    val rideEventHubOptions = EventHubsConf(rideEventHubConnectionString)
      .setConsumerGroup(conf.taxiRideConsumerGroup())
      .setStartingPosition(EventPosition.fromStartOfStream)
    val rideEvents = spark.readStream
      .format("eventhubs")
      .options(rideEventHubOptions.toMap)
      .load()

    val fareEventHubOptions = EventHubsConf(fareEventHubConnectionString)
      .setConsumerGroup(conf.taxiFareConsumerGroup())
      .setStartingPosition(EventPosition.fromStartOfStream)
    val fareEvents = spark.readStream
      .format("eventhubs")
      .options(fareEventHubOptions.toMap)
      .load()

    val transformedRides = processRideData(spark, rideEvents)
    val transformedFares = processFareData(spark, fareEvents)

    val mergedTaxiTrip = transformedRides.join(transformedFares, Seq("medallion", "hackLicense", "vendorId", "pickupTime"))

    val maxAvgFarePerNeighborhood = calculateMaxAvgFarePerNeighborhood(mergedTaxiTrip, conf.windowInterval())

    maxAvgFarePerNeighborhood
      .writeStream
      .queryName("maxAvgFarePerNeighborhood_cassandra_insert")
      .outputMode(OutputMode.Append())
      .foreach(new CassandraSinkForeach(connector))
      .start()
      .awaitTermination()
  }

  def processRideData(spark: SparkSession, rideEvents: DataFrame): DataFrame = {
    import spark.implicits._

    rideEvents
      .select(
        $"body"
          .cast(StringType)
          .as("messageData"),
        from_json($"body".cast(StringType), RideSchema)
          .as("ride"))
      .transform(ds => {
        ds.withColumn(
          "errorMessage",
          when($"ride".isNull,
            lit("Error decoding JSON"))
            .otherwise(lit(null))
        )
      })
      .filter(r => {
        if (r.isNullAt(r.fieldIndex("errorMessage"))) {
          true
        }
        else {
          AppAccumulators.getRideInstance(spark.sparkContext).add(1)
          false
        }
      })
      .select(
        $"ride.*",
        to_neighborhood($"ride.pickupLon", $"ride.pickupLat")
          .as("pickupNeighborhood"),
        to_neighborhood($"ride.dropoffLon", $"ride.dropoffLat")
          .as("dropoffNeighborhood")
      )
      .withWatermark("pickupTime", conf.taxiRideWatermarkInterval())
  }

  def processFareData(spark: SparkSession, fareEvents: DataFrame): DataFrame = {
    import spark.implicits._

    val csvOptions = Map("header" -> "true", "multiLine" -> "true")
    fareEvents
      .select(
        $"body"
          .cast(StringType)
          .as("messageData"),
        from_csv($"body".cast(StringType), FareSchema, csvOptions)
          .as("fare"))
      .transform(ds => {
        ds.withColumn(
          "errorMessage",
          when($"fare".isNull,
            lit("Error decoding CSV"))
            .when(to_timestamp($"fare.pickupTimeString", "yyyy-MM-dd HH:mm:ss").isNull,
              lit("Error parsing pickupTime"))
            .otherwise(lit(null))
        )
      })
      .transform(ds => {
        ds.withColumn(
          "pickupTime",
          when($"fare".isNull,
            lit(null))
            .otherwise(to_timestamp($"fare.pickupTimeString", "yyyy-MM-dd HH:mm:ss"))
        )
      })
      .filter(r => {
        if (r.isNullAt(r.fieldIndex("errorMessage"))) {
          true
        }
        else {
          AppAccumulators.getFareInstance(spark.sparkContext).add(1)
          false
        }
      })
      .select(
        $"fare.*",
        $"pickupTime"
      )
      .withWatermark("pickupTime", conf.taxiFareWatermarkInterval())
  }

  def calculateMaxAvgFarePerNeighborhood(mergedTaxiTrip: DataFrame, windowInterval: String): DataFrame = {
    mergedTaxiTrip.selectExpr("medallion", "hackLicense", "vendorId", "pickupTime", "rateCode", "storeAndForwardFlag", "dropoffTime", "passengerCount", "tripTimeInSeconds", "tripDistanceInMiles", "pickupLon", "pickupLat", "dropoffLon", "dropoffLat", "paymentType", "fareAmount", "surcharge", "mtaTax", "tipAmount", "tollsAmount", "totalAmount", "pickupNeighborhood", "dropoffNeighborhood")
      .groupBy(window($"pickupTime", windowInterval), $"pickupNeighborhood")
      .agg(
        count("*").as("rideCount"),
        sum($"fareAmount").as("totalFareAmount"),
        sum($"tipAmount").as("totalTipAmount"),
        (sum($"fareAmount")/count("*")).as("averageFareAmount"),
        (sum($"tipAmount")/count("*")).as("averageTipAmount")
      )
      .select($"window.start", $"window.end", $"pickupNeighborhood", $"rideCount", $"totalFareAmount", $"totalTipAmount", $"averageFareAmount", $"averageTipAmount")
  }
}